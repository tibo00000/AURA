package com.aura.music.domain.player

import android.content.Context
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.media.AudioIntegrityValidator
import com.aura.music.data.media.UriPathParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

sealed interface PlayableResolution {
    data class LocalFile(val uri: String, val file: File) : PlayableResolution
    data class ScopedContent(val contentUri: String) : PlayableResolution
    data class CloudStream(val streamUrl: String) : PlayableResolution
    data class NotAvailable(val reason: String) : PlayableResolution
}

class TrackPlaybackResolver(
    private val downloadsDir: File,
    private val onPurgeTrack: (suspend (String, String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    constructor(
        context: Context,
        database: AuraDatabase,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) : this(
        downloadsDir = File(context.filesDir, "downloads"),
        onPurgeTrack = { trackId, uri ->
            val observed = database.trackDao().getMediaLinkByTrackId(trackId)
            if (observed?.contentUri == uri) {
                com.aura.music.data.media.TrackMediaCoordinator.shared.purgeObserved(
                    database, File(context.filesDir, "downloads"), observed)
            }
        },
        scope = scope
    )

    companion object {
        private const val TAG = "TrackPlaybackResolver"
    }

    private fun logWarn(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                android.util.Log.w(TAG, message, throwable)
            } else {
                android.util.Log.w(TAG, message)
            }
        } catch (_: Throwable) {
            // Test environment fallback
        }
    }

    /**
     * Synchronously resolves the playable audio source for a given QueuedTrack.
     * 1. If contentUri is present and points to a valid, non-corrupt local file -> LocalFile.
     * 2. If contentUri is a Scoped Storage MediaStore content URI -> ScopedContent.
     * 3. If contentUri points to a corrupted or dead file link -> purges it asynchronously and falls back.
     * 4. Checks private downloads cache on disk.
     * 5. If not local -> CloudStream via authenticated proxy URL.
     * 6. If trackId is blank -> NotAvailable.
     */
    fun resolve(track: QueuedTrack): PlayableResolution {
        val contentUri = track.contentUri

        if (!contentUri.isNullOrBlank()) {
            if (UriPathParser.isContentUri(contentUri)) return PlayableResolution.ScopedContent(contentUri)
            val local = UriPathParser.parseLocalFile(contentUri)
            if (local != null && AudioIntegrityValidator.isValidAudioFile(local, checkMetadata = false)) {
                return PlayableResolution.LocalFile(contentUri, local)
            }
        }
        // A valid replacement wins BEFORE scheduling any cleanup of an obsolete URI.
        val altFile = File(downloadsDir, "${track.trackId.replace(':', ';')}.mp3")
        if (AudioIntegrityValidator.isValidAudioFile(altFile, checkMetadata = false)) {
            return PlayableResolution.LocalFile(altFile.toURI().toString(), altFile)
        }
        if (!contentUri.isNullOrBlank() && onPurgeTrack != null) {
            scope.launch {
                try { onPurgeTrack.invoke(track.trackId, contentUri) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { logWarn("Failed conditional media-link cleanup", e) }
            }
        }

        if (track.trackId.isNotBlank()) {
            val streamUrl = "${com.aura.music.data.network.BuildConfig.API_BASE_URL.trimEnd('/')}/me/sync/files/${track.trackId}"
            return PlayableResolution.CloudStream(streamUrl)
        }

        return PlayableResolution.NotAvailable("Identifiant de piste manquant")
    }
}
