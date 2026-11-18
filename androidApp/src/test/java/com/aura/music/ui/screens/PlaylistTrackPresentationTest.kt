package com.aura.music.ui.screens

import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.network.PlaylistAcquisitionStatus
import org.junit.Assert.*
import org.junit.Test

class PlaylistTrackPresentationTest {
    private val pending = PlaylistAcquisitionStatus("track", "pending", "missing")
    private val job = DownloadJobEntity("job", "track", "fixture", "running", progressPercent = .4f, createdAt = 1, updatedAt = 1)

    @Test fun localAudioIsReadyEvenWhenAnotherCloudIdentityIsPending() {
        assertEquals(TrackDownloadStatus.Downloaded, playlistTrackDownloadStatus(true, false, pending, job))
    }

    @Test fun playlistIntentUsesTheImportQueueIndicatorBeforeAJobExists() {
        assertEquals(TrackDownloadStatus.Queued, playlistTrackDownloadStatus(false, false, pending, null))
        assertEquals(TrackDownloadStatus.Downloading(), playlistTrackDownloadStatus(false, false, pending.copy(status = "admitted", jobStatus = "running"), null))
        assertEquals(TrackDownloadStatus.Downloading(.4f), playlistTrackDownloadStatus(false, false, pending, job))
    }

    @Test fun resolvedCloudDoesNotShowAStaleJobAsDownloading() {
        assertEquals(TrackDownloadStatus.NotDownloaded, playlistTrackDownloadStatus(false, true, pending, job))
        assertFalse(showPlaylistCloudSummary(listOf(pending.copy(availability = "available"))))
        assertTrue(showPlaylistCloudSummary(listOf(pending)))
        assertTrue(showPlaylistCloudSummary(listOf(pending.copy(status = "failed"))))
        assertFalse(showPlaylistCloudSummary(emptyList()))
    }

    @Test fun versionChoiceRemainsActionableAndFailureDoesNotBecomeAvailability() {
        assertEquals(TrackDownloadStatus.RequiresResolution("job"), playlistTrackDownloadStatus(false, false, pending.copy(status = "admitted", jobStatus = "requires_resolution", jobId = "job"), null))
        assertTrue(playlistTrackDownloadStatus(false, false, pending.copy(status = "failed"), null) is TrackDownloadStatus.Failed)
    }

    @Test fun summaryDistinguishesWaitingFromATransferAndKeepsErrorsVisible() {
        assertEquals("Cloud · 1 disponible(s) · 1 en attente", playlistCloudSummaryLabel(listOf(pending.copy(availability = "available"), pending)))
        assertEquals("Cloud · 1 téléchargement(s)", playlistCloudSummaryLabel(listOf(pending.copy(status = "admitted", jobStatus = "running"))))
        assertEquals("Cloud · 1 à vérifier", playlistCloudSummaryLabel(listOf(pending.copy(status = "failed"))))
    }
}
