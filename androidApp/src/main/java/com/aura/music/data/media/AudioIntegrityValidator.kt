package com.aura.music.data.media

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileInputStream

object AudioIntegrityValidator {
    private const val TAG = "AudioIntegrityValidator"
    const val MIN_AUDIO_SIZE_BYTES = 2048L

    private fun logWarn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
            // Test environment fallback
        }
    }

    private fun logErr(message: String, throwable: Throwable? = null) {
        try {
            Log.e(TAG, message, throwable)
        } catch (_: Throwable) {
            // Test environment fallback
        }
    }

    /**
     * Inspects magic bytes and file length to ensure the file is a genuine audio container.
     */
    fun isValidAudioFile(file: File, checkMetadata: Boolean = true): Boolean {
        if (!file.exists() || !file.isFile) return false
        val length = file.length()
        if (length < MIN_AUDIO_SIZE_BYTES) {
            logWarn("Audio file rejected: size too small ($length bytes) for ${file.name}")
            return false
        }

        try {
            val header = ByteArray(64)
            val bytesRead = FileInputStream(file).use { it.read(header) }
            if (bytesRead < 16) return false

            // Reject if it starts with HTML or JSON error payloads
            val headerStr = String(header, 0, bytesRead.coerceAtMost(32)).lowercase()
            if (headerStr.startsWith("<!doc") || headerStr.startsWith("<html") || headerStr.startsWith("{\"err") || headerStr.startsWith("error")) {
                logWarn("Audio file rejected: detected HTML/JSON error payload in ${file.name}")
                return false
            }

            // Magic bytes detection
            val isMp3 = (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
                    ((header[0].toInt() and 0xFF) == 0xFF && (header[1].toInt() and 0xE0) == 0xE0)
            val isM4a = bytesRead >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            val isOgg = bytesRead >= 4 && header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                    header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()
            val isWebm = bytesRead >= 4 && (header[0].toInt() and 0xFF) == 0x1A && (header[1].toInt() and 0xFF) == 0x45 &&
                    (header[2].toInt() and 0xFF) == 0xDF && (header[3].toInt() and 0xFF) == 0xA3
            val isFlac = bytesRead >= 4 && header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()

            if (!isMp3 && !isM4a && !isOgg && !isWebm && !isFlac) {
                // If it's large (> 100 KB) with a recognized audio extension, allow as fallback
                val ext = file.extension.lowercase()
                if (length < 100 * 1024 || ext !in setOf("mp3", "m4a", "ogg", "opus", "webm", "flac")) {
                    logWarn("Audio file rejected: unknown magic bytes for ${file.name}")
                    return false
                }
            }

            if (checkMetadata) {
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val duration = durationStr?.toLongOrNull() ?: 0L
                        if (duration <= 0L) {
                            logWarn("Audio file rejected: MediaMetadataRetriever reported 0ms duration for ${file.name}")
                            return false
                        }
                    } finally {
                        try {
                            retriever.release()
                        } catch (_: Throwable) {
                            // ignore
                        }
                    }
                } catch (e: Throwable) {
                    logWarn("MediaMetadataRetriever failed to read ${file.name}: ${e.message}")
                    return false
                }
            }

            return true
        } catch (e: Throwable) {
            logErr("Error checking audio integrity for ${file.name}", e)
            return false
        }
    }
}
