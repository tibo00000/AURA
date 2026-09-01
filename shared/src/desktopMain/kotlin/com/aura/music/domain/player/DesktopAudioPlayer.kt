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

    override fun play(uri: String) {
        stop()
        try {
            val mediaUrl = if (uri.startsWith("file:") || uri.startsWith("http:") || uri.startsWith("https:")) {
                uri
            } else {
                File(uri).toURI().toString()
            }
            
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

    override fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
    }

    override fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.dispose()
        mediaPlayer = null
        isPlaying = false
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer?.seek(javafx.util.Duration.millis(positionMs.toDouble()))
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
        mediaPlayer?.volume = volume.toDouble().coerceIn(0.0, 1.0)
    }

    override fun getVolume(): Float {
        return mediaPlayer?.volume?.toFloat() ?: 1f
    }
}
