package com.aura.music.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Fail closed, including cold Keystore failure, service destruction and stalled initialization. */
internal class PlaybackSessionReadiness(private val timeoutMillis: Long = 30_000L) {
    private val ready = CompletableDeferred<Unit>()

    suspend fun initialize(install: suspend () -> Unit): Boolean = try {
        withTimeout(timeoutMillis) { install() }
        ready.complete(Unit)
    } catch (failure: Exception) {
        ready.completeExceptionally(IllegalStateException("Session audio indisponible"))
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        false
    }

    suspend fun await() {
        try { withTimeout(timeoutMillis) { ready.await() } }
        catch (failure: TimeoutCancellationException) {
            val unavailable = IllegalStateException("Initialisation audio expirée")
            ready.completeExceptionally(unavailable)
            throw unavailable
        }
    }

    fun close() { ready.completeExceptionally(IllegalStateException("Service audio arrêté")) }
}
