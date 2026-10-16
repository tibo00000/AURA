package com.aura.music.domain.player

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class DesktopAudioPlayer : AudioPlayer {
    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var currentMediaUrl: String? = null
    @Volatile
    private var isPlaying = false
    @Volatile
    private var currentVolume: Float = 1.0f
    @Volatile
    private var pendingSeekMs: Long? = null
    @Volatile
    private var isMediaReady: Boolean = false

    override var onCompletionListener: (() -> Unit)? = null
    override var onErrorListener: ((String) -> Unit)? = null

    init {
        try {
            // Initialize JavaFX Platform in headless mode or if not already started
            Platform.startup {}
        } catch (e: Throwable) {
            // Already initialized, or caught Error/Exception
        }
    }

    private fun runOnFxThread(action: () -> Unit) {
        try {
            if (Platform.isFxApplicationThread()) {
                action()
            } else {
                Platform.runLater {
                    try {
                        action()
                    } catch (e: Throwable) {
                        onErrorListener?.invoke(e.message ?: "JavaFX error")
                    }
                }
            }
        } catch (e: Throwable) {
            onErrorListener?.invoke(e.message ?: "JavaFX Platform error")
        }
    }

    private fun normalizeMediaUrl(uri: String): String {
        return if (uri.startsWith("file:") || uri.startsWith("http:") || uri.startsWith("https:")) {
            uri
        } else {
            File(uri).toURI().toString()
        }
    }

    override fun play(uri: String) {
        val mediaUrl = normalizeMediaUrl(uri)
        currentMediaUrl = mediaUrl
        pendingSeekMs = null
        isMediaReady = false
        isPlaying = true

        runOnFxThread {
            stopInternal()
            currentMediaUrl = mediaUrl
            try {
                val media = Media(mediaUrl)
                val targetVol = currentVolume.toDouble().coerceIn(0.0, 1.0)
                mediaPlayer = MediaPlayer(media).apply {
                    volume = targetVol
                    setOnReady {
                        isMediaReady = true
                        this@apply.volume = currentVolume.toDouble().coerceIn(0.0, 1.0)
                        val seekTarget = pendingSeekMs
                        if (seekTarget != null) {
                            pendingSeekMs = null
                            this@apply.seek(javafx.util.Duration.millis(seekTarget.toDouble()))
                        }
                    }
                    setOnEndOfMedia {
                        isPlaying = false
                        onCompletionListener?.invoke()
                    }
                    setOnError {
                        isPlaying = false
                        onErrorListener?.invoke(getError()?.message ?: "JavaFX Media Error")
                    }
                    this@apply.play()
                }
                isPlaying = true
            } catch (e: Exception) {
                isPlaying = false
                onErrorListener?.invoke(e.message ?: "Failed to play native audio")
            }
        }
    }

    override fun resume() {
        val existingPlayer = mediaPlayer
        if (existingPlayer != null) {
            isPlaying = true
            runOnFxThread {
                val player = mediaPlayer
                if (player != null) {
                    player.volume = currentVolume.toDouble().coerceIn(0.0, 1.0)
                    val seekTarget = pendingSeekMs
                    if (seekTarget != null && isMediaReady) {
                        pendingSeekMs = null
                        player.seek(javafx.util.Duration.millis(seekTarget.toDouble()))
                    }
                    player.play()
                    isPlaying = true
                }
            }
        } else {
            val url = currentMediaUrl
            if (url != null) {
                play(url)
            }
        }
    }

    override fun hasLoadedMedia(): Boolean = mediaPlayer != null

    fun isSameMediaLoaded(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return mediaPlayer != null
        val normalized = try { normalizeMediaUrl(uri) } catch (_: Exception) { uri }
        return mediaPlayer != null && currentMediaUrl == normalized
    }

    override fun pause() {
        isPlaying = false
        runOnFxThread {
            mediaPlayer?.pause()
            isPlaying = false
        }
    }

    private fun stopInternal() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.dispose()
        } catch (e: Exception) {
            // ignore
        } finally {
            mediaPlayer = null
            currentMediaUrl = null
            isMediaReady = false
            pendingSeekMs = null
            isPlaying = false
        }
    }

    override fun stop() {
        isPlaying = false
        runOnFxThread {
            stopInternal()
        }
    }

    override fun seekTo(positionMs: Long) {
        val clampedMs = positionMs.coerceAtLeast(0L)
        pendingSeekMs = clampedMs
        runOnFxThread {
            val player = mediaPlayer
            if (player != null && (isMediaReady || player.status == MediaPlayer.Status.READY || player.status == MediaPlayer.Status.PLAYING || player.status == MediaPlayer.Status.PAUSED)) {
                pendingSeekMs = null
                player.seek(javafx.util.Duration.millis(clampedMs.toDouble()))
            }
        }
    }

    override fun getDuration(): Long {
        val duration = mediaPlayer?.media?.duration
        if (duration == null || duration.isUnknown) return 0L
        return duration.toMillis().milliseconds.inWholeMilliseconds
    }

    override fun getCurrentPosition(): Long {
        val pending = pendingSeekMs
        if (pending != null && !isMediaReady) return pending
        val currentTime = mediaPlayer?.currentTime ?: return pending ?: 0L
        return currentTime.toMillis().milliseconds.inWholeMilliseconds
    }

    override fun isPlaying(): Boolean = isPlaying

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        currentVolume = clamped
        runOnFxThread {
            mediaPlayer?.volume = clamped.toDouble()
        }
    }

    override fun getVolume(): Float {
        return currentVolume
    }
}
