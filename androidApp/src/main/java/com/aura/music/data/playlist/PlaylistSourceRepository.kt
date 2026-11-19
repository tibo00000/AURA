package com.aura.music.data.playlist

import androidx.room3.deferredTransaction
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
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
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.net.URI
import java.time.Instant
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    internal suspend fun requireImportReady(owner: AuthSessionManager.Scope) {
        if (!auth.isCurrent(owner)) throw CancellationException("Playlist session changed")
        if (database.userSettingsDao().getSettings()?.syncEnabled != true) {
            throw PlaylistSourceReadException("Active la synchronisation Aura pour importer une playlist avec sa source.")
        }
    }

    internal suspend fun pullImportedPlaylist(deviceId: String, owner: AuthSessionManager.Scope) {
        if (!auth.isCurrent(owner)) throw CancellationException("Playlist session changed")
        if (!sync.performSync(deviceId, force = true, scope = owner)) {
            throw PlaylistSourceReadException("La playlist est enregistrée sur Aura. Sa récupération sur cet appareil reste à confirmer.")
        }
    }

    internal suspend fun resolveKnownLocalAudio(rows: List<com.aura.music.data.local.PlaylistTrackRow>): List<com.aura.music.data.local.PlaylistTrackRow> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val identities = rows.filter { it.contentUri.isNullOrBlank() }
                .mapNotNull { PlaylistRecordingIdentity.parse(it.trackId) }.toSet()
            if (identities.isEmpty()) return@withContext rows
            val ids = identities.flatMap { it.aliases() }.distinct()
            val local = ids.chunked(400).flatMap { database.trackDao().getTracksByIds(it) }
                .filter { !it.contentUri.isNullOrBlank() }
                .groupBy { PlaylistRecordingIdentity.parse(it.id) }
            rows.map { row ->
                val copy = local[PlaylistRecordingIdentity.parse(row.trackId)]?.firstOrNull()
                if (row.contentUri.isNullOrBlank() && copy != null) row.copy(contentUri = copy.contentUri) else row
            }
        }

    internal suspend fun getAcquisitions(
        playlistId: String,
        scope: AuthSessionManager.Scope = auth.captureScope(),
    ): List<PlaylistAcquisitionStatus> {
        val bearer = auth.validSession(scope = scope).bearer
        return confirmedAcquisitions(scope, api.getPlaylistAcquisitions(bearer, playlistId))
    }

    internal suspend fun requestAcquisitions(
        playlistId: String,
        deviceId: String,
        retryTrackId: String? = null,
        scope: AuthSessionManager.Scope = auth.captureScope(),
    ): List<PlaylistAcquisitionStatus> {
        if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
        val syncMessage = "Synchronise les modifications locales de cette playlist avant de demander la disponibilité cloud."
        if (!canRequestAcquisitions(playlistId, scope.userId) && !sync.performSync(deviceId, force = true, scope = scope)) {
            if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
            throw PlaylistSourceReadException(syncMessage)
        }
        val bearer = auth.validSession(scope = scope).bearer
        if (!canRequestAcquisitions(playlistId, scope.userId)) throw PlaylistSourceReadException(syncMessage)
        if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
        return confirmedAcquisitions(scope, api.requestPlaylistAcquisitions(bearer, playlistId, retryTrackId))
    }

    private fun confirmedAcquisitions(
        scope: AuthSessionManager.Scope,
        response: AuraResponse<List<PlaylistAcquisitionStatus>>,
    ): List<PlaylistAcquisitionStatus> {
        if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
        return response.data?.takeIf { response.error == null && response.meta?.partialFailure != true }
            ?: throw PlaylistSourceReadException("Le bilan cloud est temporairement indisponible.")
    }

    private suspend fun canRequestAcquisitions(playlistId: String, userId: String): Boolean =
        database.useReaderConnection { reader ->
            reader.deferredTransaction {
                val state = database.playlistDao().getPlaylistSyncState(playlistId)
                state != null && state.userId == userId && state.appliedRevision != null &&
                    !hasUnresolvedEdits(playlistId, userId)
            }
        }

    private suspend fun hasUnresolvedEdits(playlistId: String, userId: String): Boolean =
        database.syncOutboxDao().getUnresolvedPlaylistOperations().any { operation ->
            (operation.userId == null || operation.userId == userId) &&
                (operation.entityId == playlistId || runCatching {
                    Json.decodeFromString<JsonObject>(operation.payloadJson)["playlist_id"]
                }.getOrNull() == JsonPrimitive(playlistId))
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
        if (!sync.performSync(deviceId, force = true, scope = scope)) {
            throw PlaylistSourceReadException("Synchronise les modifications locales avant de réessayer.")
        }
        val current = dao.getPlaylistSyncState(playlistId)
        if (current?.userId != scope.userId || current.sourceRevision != state.sourceRevision) {
            throw PlaylistSourceReadException("La source de la playlist a changé. Réessaie.")
        }
        val cloudResponse = api.listSyncFiles(auth.validSession(scope = scope).bearer)
        if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
        if (cloudResponse.error != null || cloudResponse.meta?.partialFailure == true || cloudResponse.data == null)
            throw PlaylistSourceReadException("La bibliothèque cloud ne peut pas être vérifiée. Réessaie.")
        val cloudIds = cloudResponse.data!!.items.mapTo(hashSetOf()) { it.trackId }
        val candidates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val rows = database.trackDao().getAllTracks()
            rows.filter { !it.contentUri.isNullOrBlank() || it.id in cloudIds }.groupBy { row -> metadataMatchKey(row.title, row.artistName) }
        }
        val request = pending ?: PlaylistRefreshRequest(
            requestId = UUID.randomUUID().toString(),
            sourceRevision = state.sourceRevision,
            tracks = tracks.map {
                val key = metadataMatchKey(it.title, it.artist)
                it.copy(requiresConfirmation = candidates[key].orEmpty().any { row -> PlaylistRecordingIdentity.parse(row.id) != it.recordingIdentity() })
            },
            confirmedDistinct = confirmedDistinct,
            confirmedLinks = confirmedLinks,
        )
        val serializedRequest = Json.encodeToString(request)
        database.useWriterConnection { writer ->
            writer.immediateTransaction {
                if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
                val latest = dao.getPlaylistSyncState(playlistId)
                if (latest?.userId != scope.userId || latest.sourceRevision != request.sourceRevision) {
                    throw PlaylistSourceReadException("La source de la playlist a changé. Réessaie.")
                }
                if (pending == null && hasUnresolvedEdits(playlistId, scope.userId)) {
                    throw PlaylistSourceReadException("Une modification locale de cette playlist est encore en attente.")
                }
                if (latest.pendingRefreshJson != serializedRequest) {
                    dao.upsertPlaylistSyncState(latest.copy(pendingRefreshJson = serializedRequest))
                }
            }
        }
        val response = api.commitPlaylistRefresh(auth.validSession(scope = scope).bearer, playlistId, request)
        if (response.status == HttpStatusCode.Conflict) {
            clearPendingRefresh(playlistId, scope, request)
            sync.triggerManualSync()
            if (response.headers["X-Aura-Conflict"] == "playlist_link_changed") {
                throw PlaylistSourceReadException("Cette copie a été modifiée ou retirée. Choisis à nouveau la copie à réutiliser.")
            }
            throw PlaylistSourceReadException("La source a changé sur un autre appareil. Réessaie après la synchronisation.")
        }
        if (response.status == HttpStatusCode.UnprocessableEntity) {
            clearPendingRefresh(playlistId, scope, request)
            throw PlaylistSourceReadException("Le fournisseur a renvoyé des données que la playlist ne peut pas importer.")
        }
        if (!response.status.isSuccess()) throw PlaylistSourceReadException("Aura n’a pas pu confirmer l’actualisation.")
        val envelope = response.body<AuraResponse<PlaylistRefreshResult>>()
        val result = envelope.data
        if (envelope.error != null || envelope.meta?.partialFailure == true || result == null ||
            result.requestId != request.requestId || result.sourceRevision != state.sourceRevision ||
            runCatching { Instant.parse(result.completedAt) }.isFailure
        ) {
            throw PlaylistSourceReadException("Aura n’a pas pu confirmer l’actualisation.")
        }
        database.useWriterConnection { writer ->
            writer.immediateTransaction {
                if (!auth.isCurrent(scope)) throw CancellationException("Playlist session changed")
                val latest = dao.getPlaylistSyncState(playlistId)
                if (latest?.userId == scope.userId) {
                    val updated = latest.acknowledgeRefresh(serializedRequest, result)
                    if (updated != latest) dao.upsertPlaylistSyncState(updated)
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
                sync.performSync(deviceId, force = true, scope = scope)
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

internal fun PlaylistSyncStateEntity.acknowledgeRefresh(
    serializedRequest: String,
    result: PlaylistRefreshResult,
): PlaylistSyncStateEntity {
    if (sourceRevision != result.sourceRevision || pendingRefreshJson != serializedRequest) return this
    val current = lastRefreshJson?.let {
        runCatching { Json.decodeFromString<PlaylistRefreshResult>(it) }.getOrNull()
    }
    val newer = current?.takeIf { it.sourceRevision == sourceRevision }?.let {
        runCatching { Instant.parse(it.completedAt) }.getOrNull()
    }?.let { it > Instant.parse(result.completedAt) } == true
    return copy(
        lastRefreshJson = if (newer) lastRefreshJson else Json.encodeToString(result),
        pendingRefreshJson = null,
    )
}
