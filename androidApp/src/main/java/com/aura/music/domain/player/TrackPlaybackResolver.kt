package com.aura.music.domain.player

import android.content.Context
import android.util.Log
import com.aura.music.data.network.BuildConfig
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
    private val onPurgeTrack: (suspend (String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    constructor(
        context: Context,
        database: AuraDatabase,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    ) : this(
        downloadsDir = File(context.filesDir, "downloads"),
        onPurgeTrack = { trackId -> database.trackDao().deleteTrackMediaLinksByTrackId(trackId) },
        scope = scope
    )

    companion object {
        private const val TAG = "TrackPlaybackResolver"
    }

    private fun logWarn(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.w(TAG, message, throwable)
            } else {
                Log.w(TAG, message)
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
            if (UriPathParser.isContentUri(contentUri)) {
                // MediaStore Content URI (Scoped Storage)
                return PlayableResolution.ScopedContent(contentUri)
            }

            val localFile = UriPathParser.parseLocalFile(contentUri)

            if (localFile != null && localFile.exists()) {
                if (AudioIntegrityValidator.isValidAudioFile(localFile, checkMetadata = false)) {
                    return PlayableResolution.LocalFile(contentUri, localFile)
                } else {
                    logWarn("Local file for track ${track.trackId} is corrupt or 0-byte (${localFile.path}). Purging and falling back to cloud...")
                    try {
                        localFile.delete()
                    } catch (e: Exception) {
                        logWarn("Failed to delete corrupt file: ${localFile.absolutePath}", e)
                    }
                    if (onPurgeTrack != null) {
                        scope.launch {
                            try {
                                onPurgeTrack.invoke(track.trackId)
                            } catch (e: Exception) {
                                logWarn("Failed to purge corrupt track link in DB", e)
                            }
                        }
                    }
                }
            } else {
                // File does not exist anymore (dead link)
                logWarn("Local file for track ${track.trackId} does not exist (${localFile?.path ?: contentUri}). Purging dead link in DB...")
                if (onPurgeTrack != null) {
                    scope.launch {
                        try {
                            onPurgeTrack.invoke(track.trackId)
                        } catch (e: Exception) {
                            logWarn("Failed to purge dead track link in DB", e)
                        }
                    }
                }
            }
        }

        // Check if physical file exists in private downloads dir even if contentUri was not set in memory
        val altFile = File(downloadsDir, "${track.trackId.replace(':', ';')}.mp3")
        if (altFile.exists()) {
            if (AudioIntegrityValidator.isValidAudioFile(altFile, checkMetadata = false)) {
                val uri = altFile.toURI().toString()
                return PlayableResolution.LocalFile(uri, altFile)
            } else {
                logWarn("Private download file for ${track.trackId} is corrupt. Purging...")
                try {
                    altFile.delete()
                } catch (e: Exception) {
                    logWarn("Failed to delete corrupt private download file", e)
                }
            }
        }

        if (track.trackId.isNotBlank()) {
            val streamUrl = "${BuildConfig.API_BASE_URL.trimEnd('/')}/me/sync/files/${track.trackId}"
            return PlayableResolution.CloudStream(streamUrl)
        }

        return PlayableResolution.NotAvailable("Identifiant de piste manquant")
    }
}
