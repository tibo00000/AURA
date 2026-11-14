package com.aura.music.data.playlist

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.core.AuthSessionManager
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.PlaylistSyncStateEntity
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.AuraResponse
import com.aura.music.data.network.PlaylistSourceRequest
import com.aura.music.data.network.PlaylistSourceResponse
import com.aura.music.data.network.PlaylistAcquisitionStatus
import com.aura.music.data.network.PlaylistRefreshRequest
import com.aura.music.data.network.PlaylistRefreshResult
import com.aura.music.data.repository.SyncRepository
import com.aura.music.domain.search.SearchNormalizer
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PlaylistSourceReference(val provider: String, val externalId: String) {
    fun isCanonical(): Boolean = when (provider) {
        "spotify" -> spotifyId.matches(externalId)
        "deezer" -> deezerId.matches(externalId)
        else -> false
    }

    companion object {
        private val spotifyId = Regex("[A-Za-z0-9]{22}")
        private val deezerId = Regex("[1-9][0-9]{0,19}")
        private val locale = Regex("[a-z]{2}(?:-[a-z]{2})?", RegexOption.IGNORE_CASE)

        fun spotify(id: String): PlaylistSourceReference? =
            id.takeIf(spotifyId::matches)?.let { PlaylistSourceReference("spotify", it) }

        fun parse(input: String): PlaylistSourceReference? {
            val link = input.trim()
            if (link.startsWith("spotify:playlist:")) return spotify(link.removePrefix("spotify:playlist:"))
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (!"https".equals(uri.scheme, ignoreCase = true) || uri.rawUserInfo != null || uri.port != -1) return null
            val parts = (uri.rawPath ?: return null).trim('/').split('/')
            val path = when (uri.host?.lowercase()) {
                "open.spotify.com" -> parts.drop(if (parts.first().startsWith("intl-")) 1 else 0)
                "deezer.com", "www.deezer.com" -> parts.drop(if (locale.matches(parts.first())) 1 else 0)
                else -> return null
            }
            if (path.size != 2 || path[0] != "playlist") return null
            return if (uri.host.equals("open.spotify.com", ignoreCase = true)) spotify(path[1])
            else path[1].takeIf(deezerId::matches)?.let { PlaylistSourceReference("deezer", it) }
        }
    }
}

class PlaylistSourceRepository internal constructor(
    private val database: AuraDatabase,
    private val api: AuraApiService,
    private val auth: AuthSessionManager,
    private val sync: SyncRepository,
    private val sourceReader: PlaylistSourceReader,
) {
    private val refreshMutex = Mutex()

    suspend fun getAcquisitions(playlistId: String): List<PlaylistAcquisitionStatus> =
        acquisitions(playlistId, enqueue = false, retryTrackId = null)

    suspend fun requestAcquisitions(playlistId: String, retryTrackId: String? = null): List<PlaylistAcquisitionStatus> =
        acquisitions(playlistId, enqueue = true, retryTrackId = retryTrackId)

    private suspend fun acquisitions(
        playlistId: String, enqueue: Boolean, retryTrackId: String?,
    ): List<PlaylistAcquisitionStatus> {
        val scope = auth.captureScope()
        val bearer = auth.validSession(scope = scope).bearer
        val response = if (enqueue) api.requestPlaylistAcquisitions(bearer, playlistId, retryTrackId)
        else api.getPlaylistAcquisitions(bearer, playlistId)
        if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
        return response.data?.takeIf { response.error == null && response.meta?.partialFailure != true }
            ?: throw PlaylistSourceReadException("Le bilan cloud est temporairement indisponible.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(playlistId: String) = auth.sessionScope.map { it?.userId }.distinctUntilChanged()
        .flatMapLatest { owner ->
            if (owner == null) flowOf(null)
            else database.playlistDao().observePlaylistSyncState(playlistId, owner)
        }

    suspend fun refresh(
        playlistId: String,
        deviceId: String,
        confirmedDistinct: List<String> = emptyList(),
        confirmedLinks: Map<String, String> = emptyMap(),
    ): PlaylistRefreshResult = refreshMutex.withLock {
        if (!auth.isLoggedIn.value) throw PlaylistSourceReadException("Connecte-toi à Aura pour actualiser cette playlist.")
        val scope = auth.captureScope()
        val dao = database.playlistDao()
        val state = dao.getPlaylistSyncState(playlistId)
        val provider = state?.sourceProvider
        val externalId = state?.sourceExternalId
        if (state == null || state.userId != scope.userId || provider == null || externalId == null) {
            throw PlaylistSourceReadException("Associe une source à cette playlist avant de l’actualiser.")
        }
        val pending = state.pendingRefreshJson?.let { Json.decodeFromString<PlaylistRefreshRequest>(it) }
        if (pending != null && (confirmedDistinct.isNotEmpty() || confirmedLinks.isNotEmpty())) {
            throw PlaylistSourceReadException("Rejoue d’abord l’actualisation précédente pour confirmer son résultat.")
        }
        val tracks = pending?.tracks ?: sourceReader.read(PlaylistSourceReference(provider, externalId))
        if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
        // A pending manual removal must reach the server before automatic additions.
        if (!sync.performSync(deviceId, force = true)) {
            throw PlaylistSourceReadException("Synchronise les modifications locales avant de réessayer.")
        }
        val current = dao.getPlaylistSyncState(playlistId)
        if (current?.userId != scope.userId || current.sourceRevision != state.sourceRevision) {
            throw PlaylistSourceReadException("La source de la playlist a changé. Réessaie.")
        }
        val localIdentities = dao.getPlaylistTracks(playlistId).mapTo(mutableSetOf()) { row ->
            SearchNormalizer.normalize(row.title) to SearchNormalizer.normalize(row.artistName.orEmpty())
        }
        val request = pending ?: PlaylistRefreshRequest(
            requestId = UUID.randomUUID().toString(),
            sourceRevision = state.sourceRevision,
            tracks = tracks.map {
                val key = SearchNormalizer.normalize(it.title) to SearchNormalizer.normalize(it.artist)
                it.copy(requiresConfirmation = key in localIdentities)
            },
            confirmedDistinct = confirmedDistinct,
            confirmedLinks = confirmedLinks,
        )
        database.useWriterConnection { writer ->
            writer.immediateTransaction {
                if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
                val latest = dao.getPlaylistSyncState(playlistId)
                if (latest?.userId != scope.userId || latest.sourceRevision != request.sourceRevision) {
                    throw PlaylistSourceReadException("La source de la playlist a changé. Réessaie.")
                }
                val unresolved = database.syncOutboxDao().getUnresolvedPlaylistOperations()
                if (pending == null && unresolved.any { operation ->
                    (operation.userId == null || operation.userId == scope.userId) && (
                        operation.entityId == playlistId ||
                            runCatching { Json.parseToJsonElement(operation.payloadJson) }
                                .getOrNull()?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("playlist_id") }
                                == kotlinx.serialization.json.JsonPrimitive(playlistId)
                        )
                }) throw PlaylistSourceReadException("Une modification locale de cette playlist est encore en attente.")
                val serialized = Json.encodeToString(request)
                if (latest.pendingRefreshJson != serialized) {
                    dao.upsertPlaylistSyncState(latest.copy(pendingRefreshJson = serialized))
                }
            }
        }
        val response = api.commitPlaylistRefresh(auth.validSession(scope = scope).bearer, playlistId, request)
        if (response.status == HttpStatusCode.Conflict) {
            clearPendingRefresh(playlistId, scope, request)
            sync.triggerManualSync()
            throw PlaylistSourceReadException("La source a changé sur un autre appareil. Réessaie après la synchronisation.")
        }
        if (response.status == HttpStatusCode.UnprocessableEntity) {
            clearPendingRefresh(playlistId, scope, request)
            throw PlaylistSourceReadException("Le fournisseur a renvoyé des données que la playlist ne peut pas importer.")
        }
        if (!response.status.isSuccess()) throw PlaylistSourceReadException("Aura n’a pas pu confirmer l’actualisation.")
        val result = response.body<AuraResponse<PlaylistRefreshResult>>().data
        if (result == null || result.requestId != request.requestId || result.sourceRevision != state.sourceRevision) {
            throw PlaylistSourceReadException("Aura n’a pas pu confirmer l’actualisation.")
        }
        database.useWriterConnection { writer ->
            writer.immediateTransaction {
                if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
                val latest = dao.getPlaylistSyncState(playlistId)
                if (latest?.userId == scope.userId && latest.sourceRevision == state.sourceRevision) {
                    dao.upsertPlaylistSyncState(latest.copy(
                        lastRefreshJson = Json.encodeToString(result), pendingRefreshJson = null,
                    ))
                }
                if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
            }
        }
        sync.triggerManualSync()
        result
    }

    private suspend fun clearPendingRefresh(
        playlistId: String, scope: AuthSessionManager.Scope, request: PlaylistRefreshRequest,
    ) {
        database.useWriterConnection { writer ->
            writer.immediateTransaction {
                if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
                val dao = database.playlistDao()
                val state = dao.getPlaylistSyncState(playlistId)
                if (state?.userId == scope.userId && state.pendingRefreshJson == Json.encodeToString(request)) {
                    dao.upsertPlaylistSyncState(state.copy(pendingRefreshJson = null))
                }
                if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
            }
        }
    }

    suspend fun set(
        playlistId: String,
        deviceId: String,
        reference: PlaylistSourceReference?,
        expectedRevision: Long,
    ): String? {
        if (!auth.isLoggedIn.value) return "Connecte-toi à Aura pour associer cette playlist."
        return try {
            val scope = auth.captureScope()
            val dao = database.playlistDao()
            var state = dao.getPlaylistSyncState(playlistId)
            if (state == null) {
                sync.performSync(deviceId, force = true)
                state = dao.getPlaylistSyncState(playlistId)
            }
            if (state?.userId != scope.userId || !auth.isCurrent(scope)) {
                return "Synchronise cette playlist avec Aura avant de modifier sa source."
            }
            val response = api.setPlaylistSource(
                auth.validSession(scope = scope).bearer,
                playlistId,
                PlaylistSourceRequest(reference?.provider, reference?.externalId, expectedRevision),
            )
            if (response.status == HttpStatusCode.Conflict) {
                sync.triggerManualSync()
                return "La source a changé sur un autre appareil. Réessaie après la synchronisation."
            }
            if (!response.status.isSuccess()) return "Aura n’a pas pu confirmer la modification de la source."
            val envelope = response.body<AuraResponse<PlaylistSourceResponse>>()
            val source = envelope.data
            if (envelope.error != null || source == null || source.provider != reference?.provider ||
                source.externalId != reference?.externalId || source.sourceRevision < expectedRevision
            ) return "Aura n’a pas pu confirmer la modification de la source."
            var superseded = false
            database.useWriterConnection { writer ->
                writer.immediateTransaction {
                    if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
                    val latest = dao.getPlaylistSyncState(playlistId)
                    if (latest != null && latest.userId == scope.userId && latest.sourceRevision <= source.sourceRevision) {
                        dao.upsertPlaylistSyncState(latest.withSource(source))
                    } else {
                        superseded = true
                    }
                    if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
                }
            }
            sync.triggerManualSync()
            if (superseded) "La source ou la playlist a changé pendant l’enregistrement." else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "La modification de la source n’a pas pu être confirmée. Réessaie après la synchronisation."
        }
    }

    private fun PlaylistSyncStateEntity.withSource(source: PlaylistSourceResponse) = copy(
        sourceProvider = source.provider,
        sourceExternalId = source.externalId,
        sourceRevision = source.sourceRevision,
        lastRefreshJson = lastRefreshJson.takeIf { source.sourceRevision == sourceRevision },
        pendingRefreshJson = pendingRefreshJson.takeIf { source.sourceRevision == sourceRevision },
    )
}
