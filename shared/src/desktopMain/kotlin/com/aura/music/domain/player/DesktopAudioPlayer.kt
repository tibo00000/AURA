package com.aura.music.domain.player

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class DesktopAudioPlayer : AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    override var onCompletionListener: (() -> Unit)? = null
    override var onErrorListener: ((String) -> Unit)? = null

    init {
        try {
            // Initialize JavaFX Platform in headless mode or if not already started
            Platform.startup {}
        } catch (e: Exception) {
            // Already initialized, ignore
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
                    } catch (e: Exception) {
                        onErrorListener?.invoke(e.message ?: "JavaFX error")
                    }
                }
            }
        } catch (e: Exception) {
            onErrorListener?.invoke(e.message ?: "JavaFX Platform error")
        }
    }

    override fun play(uri: String) {
        val mediaUrl = if (uri.startsWith("file:") || uri.startsWith("http:") || uri.startsWith("https:")) {
            uri
        } else {
            File(uri).toURI().toString()
        }

        runOnFxThread {
            stopInternal()
            try {
                val media = Media(mediaUrl)
                mediaPlayer = MediaPlayer(media).apply {
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

    override fun pause() {
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
            isPlaying = false
        }
    }

    override fun stop() {
        runOnFxThread {
            stopInternal()
        }
    }

    override fun seekTo(positionMs: Long) {
        runOnFxThread {
            mediaPlayer?.seek(javafx.util.Duration.millis(positionMs.toDouble()))
        }
    }

    override fun getDuration(): Long {
        val duration = mediaPlayer?.media?.duration
        if (duration == null || duration.isUnknown) return 0L
        return duration.toMillis().milliseconds.inWholeMilliseconds
    }

    override fun getCurrentPosition(): Long {
        val currentTime = mediaPlayer?.currentTime ?: return 0L
        return currentTime.toMillis().milliseconds.inWholeMilliseconds
    }

    override fun isPlaying(): Boolean = isPlaying

    override fun setVolume(volume: Float) {
        runOnFxThread {
            mediaPlayer?.volume = volume.toDouble().coerceIn(0.0, 1.0)
        }
    }

    override fun getVolume(): Float {
        return mediaPlayer?.volume?.toFloat() ?: 1f
    }
}
