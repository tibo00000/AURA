package com.aura.music.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionReadinessTest {
    @Test fun initializationFailureReleasesAllPendingAdmissionsAndRejectsNewOnes() = runTest {
        val ready = PlaybackSessionReadiness()
        val a = async { runCatching { ready.await() } }; val b = async { runCatching { ready.await() } }
        runCurrent()
        assertFalse(ready.initialize { throw IllegalStateException("synthetic Keystore failure") })
        assertTrue(a.await().isFailure); assertTrue(b.await().isFailure)
        assertTrue(runCatching { ready.await() }.isFailure)
    }
    @Test fun stalledInitializationTimesOutWithoutAdmittingMedia() = runTest {
        val ready = PlaybackSessionReadiness(100)
        val started = CompletableDeferred<Unit>()
        val initializing = async { ready.initialize { started.complete(Unit); awaitCancellation() } }
        started.await()
        val admission = async { runCatching { ready.await() } }
        advanceTimeBy(101); runCurrent()
        assertFalse(initializing.await()); assertTrue(admission.await().isFailure)
    }
    @Test fun waiterHasOwnDeadlineAndLateInitializerCannotReopenIt() = runTest {
        val ready = PlaybackSessionReadiness(100)
        val admission = async { runCatching { ready.await() } }
        advanceTimeBy(101); runCurrent()
        assertTrue(admission.await().isFailure)
        assertFalse(ready.initialize {})
        assertTrue(runCatching { ready.await() }.isFailure)
    }
    @Test fun serviceDestructionAndInitializerCancellationBothFinishPendingWaits() = runTest {
        val ready = PlaybackSessionReadiness()
        val initializing = launch { ready.initialize { awaitCancellation() } }
        val admission = async { runCatching { ready.await() } }
        runCurrent(); initializing.cancelAndJoin()
        assertTrue(admission.await().isFailure)
        val closing = PlaybackSessionReadiness()
        val pending = async { runCatching { closing.await() } }
        runCurrent(); closing.close()
        assertTrue(pending.await().isFailure); assertFalse(closing.initialize {})
    }
    @Test fun successfulInstallationAloneAdmitsWaitingRequests() = runTest {
        val ready = PlaybackSessionReadiness()
        var installed = false
        val admission = async { ready.await(); assertTrue(installed) }
        runCurrent(); assertFalse(admission.isCompleted)
        assertTrue(ready.initialize { installed = true })
        admission.await(); ready.await()
    }
}
