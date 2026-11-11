package com.aura.music.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.aura.music.core.PrivateStreamPolicy
import com.aura.music.data.local.TrackListRow
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
    suspend fun resolve(controller: MediaSession.ControllerInfo, items: List<MediaItem>): List<MediaItem> {
        if (!trust.allows(controller)) throw SecurityException("Contrôleur non autorisé")
        return withContext(Dispatchers.IO) {
            items.map { item ->
                val query = item.requestMetadata.searchQuery
                val track = if (item.mediaId.isEmpty() && !query.isNullOrBlank()) {
                    search(query.take(512)).firstOrNull()
                } else {
                    if (!PrivateStreamPolicy.validTrackId(item.mediaId)) throw SecurityException("Média non résolu")
                    lookup(item.mediaId)
                }
                if (track != null) {
                    val mapped = track.toMediaItem(context)
                    val resolved = if (track.contentUri.isNullOrBlank())
                        mapped.buildUpon().setUri(streams.uriFor(track.id)).build() else mapped
                    val uri = resolved.localConfiguration?.uri ?: throw SecurityException("Source absente")
                    val local = uri.scheme in setOf("content", "file") ||
                        (uri.scheme == null && uri.toString().startsWith("/"))
                    if (!local &&
                        !runCatching { streams.allows(URI(uri.toString())) }.getOrDefault(false)) {
                        throw SecurityException("Source non autorisée")
                    }
                    resolved // Never copy caller URI, DRM, subtitles, requestMetadata or credentials.
                } else {
                    // The app can play online results before insertion in Room. External callers cannot.
                    val uri = item.localConfiguration?.uri
                    if (!trust.isInternal(controller) || uri == null ||
                        runCatching { streams.trackId(URI(uri.toString())) }.getOrNull() != item.mediaId) {
                        throw SecurityException("Média non résolu")
                    }
                    MediaItem.Builder().setMediaId(item.mediaId).setUri(streams.uriFor(item.mediaId))
                        .setMediaMetadata(item.mediaMetadata).build()
                }
            }
        }
    }
}
