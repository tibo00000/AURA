package com.aura.music.ui.screens

import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.network.PlaylistAcquisitionStatus
import org.junit.Assert.*
import org.junit.Test

class PlaylistTrackPresentationTest {
    private val pending = PlaylistAcquisitionStatus("track", "pending", "missing")
    private val job = DownloadJobEntity("job", "track", "fixture", "running", progressPercent = 40f, createdAt = 1, updatedAt = 1)

    @Test fun associatedPlaylistLabelsDoNotFallBackToLocalWhenPinned() {
        assertEquals("Playlist Spotify", playlistOriginLabel("spotify"))
        assertEquals("Playlist Deezer · Épinglée", playlistOriginLabel("deezer", true))
        assertEquals("Playlist Aura", playlistOriginLabel(null))
        assertEquals("Playlist Aura", playlistOriginLabel("unknown"))
    }

    @Test fun localAudioIsReadyEvenWhenAnotherCloudIdentityIsPending() {
        assertEquals(TrackDownloadStatus.Downloaded, playlistTrackDownloadStatus(true, false, pending, job))
    }

    @Test fun waitingForAdmissionDoesNotPretendToBeAnActiveDownload() {
        assertEquals(TrackDownloadStatus.NotDownloaded, playlistTrackDownloadStatus(false, false, pending, null))
        assertTrue(playlistCloudPending(false, false, pending))
        assertEquals(TrackDownloadStatus.Downloading(), playlistTrackDownloadStatus(false, false, pending.copy(status = "admitted", jobId = "job", jobStatus = "running"), null))
        assertEquals(TrackDownloadStatus.Downloading(0.4f), playlistTrackDownloadStatus(false, false, pending, job))
    }

    @Test fun resolvedCloudDoesNotShowAStaleJobAsDownloading() {
        assertEquals(TrackDownloadStatus.NotDownloaded, playlistTrackDownloadStatus(false, true, pending, job))
        assertFalse(playlistCloudPending(false, true, pending))
        assertFalse(playlistCloudPending(true, false, pending))
    }

    @Test fun versionChoiceRemainsActionableAndFailureDoesNotBecomeAvailability() {
        assertEquals(TrackDownloadStatus.RequiresResolution("job"), playlistTrackDownloadStatus(false, false, pending.copy(status = "admitted", jobStatus = "requires_resolution", jobId = "job"), null))
        assertTrue(playlistTrackDownloadStatus(false, false, pending.copy(status = "failed"), null) is TrackDownloadStatus.Failed)
    }

    @Test fun onlyConfirmedJobsShowAnActiveQueueOrTransfer() {
        val queued = pending.copy(status = "admitted", jobId = "job", jobStatus = "queued")
        assertFalse(playlistCloudPending(false, false, queued))
        assertEquals(TrackDownloadStatus.Queued, playlistTrackDownloadStatus(false, false, queued, null))
        assertFalse(playlistCloudPending(false, false, pending.copy(status = "failed")))
        assertEquals(TrackDownloadStatus.Downloading(1f), playlistTrackDownloadStatus(false, false, pending, job.copy(progressPercent = 150f)))
    }

    @Test fun missingAuthorizedSourceRemainsVisibleWithoutInventingAJobOrCloudFile() {
        val blocked = pending.copy(status = "source_not_authorized", errorCode = "authorized_audio_source_missing")
        assertEquals("Source audio indisponible", playlistCloudUnavailableReason(false, false, blocked))
        assertFalse(playlistCloudPending(false, false, blocked))
        assertEquals(TrackDownloadStatus.NotDownloaded, playlistTrackDownloadStatus(false, false, blocked, null))
        assertNull(playlistCloudUnavailableReason(true, false, blocked))
        assertNull(playlistCloudUnavailableReason(false, true, blocked))
        assertNull(playlistCloudUnavailableReason(false, false, blocked, job))
        assertNull(playlistCloudUnavailableReason(false, false, pending))
        assertEquals("Téléchargement cloud annulé", playlistCloudUnavailableReason(false, false, blocked.copy(status = "cancelled")))
    }
}
