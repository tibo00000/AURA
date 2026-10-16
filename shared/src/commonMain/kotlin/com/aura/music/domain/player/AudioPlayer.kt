package com.aura.music.domain.player

interface AudioPlayer {
    fun play(uri: String)
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun getDuration(): Long
    fun getCurrentPosition(): Long
    fun isPlaying(): Boolean
    fun setVolume(volume: Float)
    fun getVolume(): Float

    var onCompletionListener: (() -> Unit)?
    var onErrorListener: ((String) -> Unit)?
}
