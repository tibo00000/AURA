package com.aura.music.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.aura.music.core.PrivateStreamPolicy
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.player.QueueState
import com.aura.music.domain.player.PLAYLIST_OCCURRENCE_KEY
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.occurrenceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** Admission resolves the ENTIRE list before a caller may change QueueManager or ExoPlayer. */
internal class ControllerMediaResolver(
    private val context: Context,
    private val trust: ControllerTrustPolicy,
    private val streams: PrivateStreamPolicy,
    private val lookup: suspend (String) -> TrackListRow?,
    private val search: suspend (String) -> List<TrackListRow>
) {
    suspend fun resolve(
        controller: MediaSession.ControllerInfo,
        items: List<MediaItem>,
        queue: QueueState? = null,
    ): List<MediaItem> {
        if (!trust.allows(controller)) throw SecurityException("Contrôleur non autorisé")
        val internal = trust.isInternal(controller)
        return withContext(Dispatchers.IO) {
            items.map { item ->
                if (internal && queue != null) {
                    queue.preparedOccurrence(item)?.let { return@map checkSource(it.toPlaylistMediaItem(context)) }
                }
                val query = item.requestMetadata.searchQuery
                val track = if (item.mediaId.isEmpty() && !query.isNullOrBlank()) {
                    search(query.take(512)).firstOrNull()
                } else {
                    if (!PrivateStreamPolicy.validTrackId(item.mediaId)) throw SecurityException("Média non résolu")
                    lookup(item.mediaId)
                }
                if (track != null) {
                    val mapped = track.toMediaItem(context)
                    val requestedCloud = if (internal) item.localConfiguration?.uri?.let {
                        runCatching { streams.trackId(URI(it.toString())) }.getOrNull() == track.id
                    } == true else false
                    val resolved = if (track.contentUri.isNullOrBlank() || requestedCloud)
                        mapped.buildUpon().setUri(streams.uriFor(track.id)).build() else mapped
                    checkSource(resolved)
                    val occurrence = if (internal) item.occurrenceId() else null
                    if (occurrence == null) resolved
                    else resolved.buildUpon().setMediaMetadata(resolved.mediaMetadata.buildUpon()
                        .setExtras(android.os.Bundle(resolved.mediaMetadata.extras ?: android.os.Bundle()).apply {
                            putString(PLAYLIST_OCCURRENCE_KEY, occurrence)
                        }).build()).build()
                } else {
                    // The app can play online results before insertion in Room. External callers cannot.
                    val uri = item.localConfiguration?.uri
                    if (!internal || uri == null ||
                        runCatching { streams.trackId(URI(uri.toString())) }.getOrNull() != item.mediaId) {
                        throw SecurityException("Média non résolu")
                    }
                    MediaItem.Builder().setMediaId(item.mediaId).setUri(streams.uriFor(item.mediaId))
                        .setMediaMetadata(item.mediaMetadata).build()
                }
            }
        }
    }

    private fun QueueState.preparedOccurrence(item: MediaItem): QueuedTrack? {
        val playlist = context?.takeIf { it.type == "playlist" } ?: return null
        val occurrenceId = item.occurrenceId() ?: return null
        fun matches(track: QueuedTrack): Boolean = track.playlistCloudAvailable != null &&
            track.internalId == occurrenceId && track.trackId == item.mediaId

        return currentTrack?.takeIf(::matches) ?: priorityQueue.firstOrNull(::matches)
            ?: playlist.tracks.firstOrNull(::matches) ?: history.lastOrNull(::matches)
    }

    private fun checkSource(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: throw SecurityException("Source absente")
        val local = uri.scheme == "content" || uri.scheme == "file" ||
            (uri.scheme == null && uri.toString().startsWith("/"))
        if (!local && !runCatching { streams.allows(URI(uri.toString())) }.getOrDefault(false)) {
            throw SecurityException("Source non autorisée")
        }
        return item
    }
}
