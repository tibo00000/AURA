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
import com.aura.music.data.repository.SyncRepository
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class PlaylistSourceReference(val provider: String, val externalId: String) {
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

class PlaylistSourceRepository(
    private val database: AuraDatabase,
    private val api: AuraApiService,
    private val auth: AuthSessionManager,
    private val sync: SyncRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(playlistId: String) = auth.sessionScope.map { it?.userId }.distinctUntilChanged()
        .flatMapLatest { owner ->
            if (owner == null) flowOf(null)
            else database.playlistDao().observePlaylistSyncState(playlistId, owner)
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
    )
}
