package com.aura.music.domain.player

import org.w3c.dom.HTMLAudioElement
import kotlinx.browser.document

class WasmAudioPlayer : AudioPlayer {
    private val audio = document.createElement("audio") as HTMLAudioElement
    private var isPlaying = false

    override var onCompletionListener: (() -> Unit)? = null
    override var onErrorListener: ((String) -> Unit)? = null

    init {
        audio.onended = {
            isPlaying = false
            onCompletionListener?.invoke()
        }
        audio.onerror = { _, _, _, _, _ ->
            isPlaying = false
            onErrorListener?.invoke("HTML5 Audio playback error")
            null
        }
    }

    override fun play(uri: String) {
        try {
            audio.src = uri
            audio.play()
            isPlaying = true
        } catch (e: Exception) {
            isPlaying = false
            onErrorListener?.invoke(e.message ?: "Failed to play on Web")
        }
    }

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
private fun Double.isInfinite(): Boolean = this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY
