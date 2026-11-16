package com.aura.music.ui.screens

import com.aura.music.data.network.PlaylistAcquisitionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAcquisitionStatusTest {
    private val intent = PlaylistAcquisitionStatus("track", "pending", "missing")

    @Test
    fun pollsPendingActiveAndRetriedIntents() {
        assertTrue(intent.isAcquisitionInProgress())
        assertTrue(intent.copy(status = "admitted", jobStatus = "running").isAcquisitionInProgress())
        assertTrue(intent.copy(status = "admitted", jobStatus = "failed", errorCode = "retry_requested")
            .isAcquisitionInProgress())
        assertTrue(intent.copy(status = "admitted", jobStatus = "failed", errorCode = "acquisition_temporarily_unavailable")
            .isAcquisitionInProgress())
    }

    @Test
    fun stopsPollingResolvedOrActionableIntents() {
        assertFalse(intent.copy(availability = "available").isAcquisitionInProgress())
        assertFalse(intent.copy(status = "admitted", jobStatus = "requires_resolution").isAcquisitionInProgress())
        assertFalse(intent.copy(status = "failed").isAcquisitionInProgress())
        assertFalse(intent.copy(status = "source_not_authorized").isAcquisitionInProgress())
        assertFalse(intent.copy(status = "not_requested").isAcquisitionInProgress())
    }
}
