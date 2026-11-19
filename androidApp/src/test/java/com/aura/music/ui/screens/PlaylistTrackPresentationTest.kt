package com.aura.music.ui.screens

import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.network.PlaylistAcquisitionStatus
import org.junit.Assert.*
import org.junit.Test

class PlaylistTrackPresentationTest {
    private val pending = PlaylistAcquisitionStatus("track", "pending", "missing")
    private val job = DownloadJobEntity("job", "track", "fixture", "running", progressPercent = 40f, createdAt = 1, updatedAt = 1)

    @Test fun bulkCloudFeedbackCountsSearchesJobsChoicesAndFilesSeparately() {
        val rows = listOf(pending, pending.copy(trackId = "active", jobStatus = "queued"),
            pending.copy(trackId = "ambiguous", status = "source_not_authorized", errorCode = "audio_source_ambiguous"),
            pending.copy(trackId = "ready", availability = "available"),
            pending.copy(trackId = "failed", status = "failed"))
        val text = playlistCloudRequestFeedback(rows, null)
        assertTrue(text.contains("1 recherche demandée"))
        assertTrue(text.contains("1 téléchargement en cours"))
        assertTrue(text.contains("1 version à identifier"))
        assertTrue(text.contains("1 déjà disponible"))
        assertTrue(text.contains("1 titre non récupéré"))
    }

    @Test fun aManuallySelectedVersionShowsItsActiveJobInsteadOfTheOldAmbiguity() {
        val ambiguous = pending.copy(status = "source_not_authorized", errorCode = "audio_source_ambiguous", audioTrackId = "audio")
        val display = playlistAcquisitionForDisplay(ambiguous, mapOf("audio" to job))
        assertEquals("running", display.jobStatus)
        assertEquals(job.id, display.jobId)
        assertNull(display.errorCode)
        assertEquals(ambiguous.copy(availability = "available"), playlistAcquisitionForDisplay(ambiguous.copy(availability = "available"), mapOf("audio" to job)))
    }

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

    @Test fun ambiguousAudioIsNotMislabelledAsAnUnavailableOrQueuedFile() {
        val ambiguous = pending.copy(status = "source_not_authorized", errorCode = "audio_source_ambiguous")
        assertEquals("Version audio à identifier", playlistCloudUnavailableReason(false, false, ambiguous))
        assertEquals("Aucun fichier audio trouvé", playlistCloudUnavailableReason(false, false,
            ambiguous.copy(errorCode = "audio_source_unresolved")))
        assertTrue(playlistCloudRequestFeedback(listOf(ambiguous), "track").contains("Aucun téléchargement"))
        assertTrue(playlistCloudRequestFeedback(listOf(pending), "track").contains("après identification"))
        assertTrue(playlistCloudRequestFeedback(listOf(pending.copy(jobStatus = "queued")), "track").contains("ajouté à la file"))
    }
}
