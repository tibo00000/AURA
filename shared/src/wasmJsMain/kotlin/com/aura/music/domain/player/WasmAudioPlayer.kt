package com.aura.music.domain.player

import org.w3c.dom.HTMLAudioElement
import kotlinx.browser.document

class WasmAudioPlayer : AudioPlayer {
    private val audio = document.createElement("audio") as HTMLAudioElement
    private var isPlaying = false

    override var onCompletionListener: (() -> Unit)? = null
    override var onErrorListener: ((String) -> Unit)? = null

    init {
        audio.crossOrigin = "anonymous"
        audio.onended = {
            isPlaying = false
            onCompletionListener?.invoke()
        }
        audio.addEventListener("error", { _ ->
            isPlaying = false
            val errorDetails = audio.error?.let { "Code d'erreur HTML5: ${it.code}" } ?: "HTML5 Audio playback error"
            onErrorListener?.invoke(errorDetails)
        })
    }

    override fun play(uri: String) {
        try {
            if (audio.src != uri) {
                audio.src = uri
            }
            isPlaying = true
            playAudioSafely(audio)
        } catch (e: Exception) {
            isPlaying = false
            onErrorListener?.invoke(e.message ?: "Failed to play on Web")
        }
    }

    override fun resume() {
        try {
            if (audio.src.isNotBlank()) {
                isPlaying = true
                playAudioSafely(audio)
            }
        } catch (e: Exception) {
            isPlaying = false
            onErrorListener?.invoke(e.message ?: "Failed to resume on Web")
        }
    }

    override fun hasLoadedMedia(): Boolean = audio.src.isNotBlank()

    override fun pause() {
        audio.pause()
        isPlaying = false
    }

    override fun stop() {
        audio.pause()
        audio.src = ""
        isPlaying = false
    }

    override fun seekTo(positionMs: Long) {
        audio.currentTime = positionMs / 1000.0
    }

    override fun getDuration(): Long {
        val d = audio.duration
        return if (d.isNaN() || d.isInfinite()) 0L else (d * 1000.0).toLong()
    }

    override fun getCurrentPosition(): Long {
        val t = audio.currentTime
        return if (t.isNaN() || t.isInfinite()) 0L else (t * 1000.0).toLong()
    }

    override fun isPlaying(): Boolean = isPlaying

    override fun setVolume(volume: Float) {
        audio.volume = volume.toDouble().coerceIn(0.0, 1.0)
    }

    override fun getVolume(): Float {
        return audio.volume.toFloat()
    }
}

private fun playAudioSafely(audio: HTMLAudioElement): Unit = js("""
    try {
        var p = audio.play();
        if (p && typeof p.catch === 'function') {
            p.catch(function(err) {
                console.warn("Autoplay / audio playback restriction:", err);
            });
        }
    } catch(e) {
        console.warn("Audio play exception:", e);
    }
""")

private fun Double.isInfinite(): Boolean = this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY
