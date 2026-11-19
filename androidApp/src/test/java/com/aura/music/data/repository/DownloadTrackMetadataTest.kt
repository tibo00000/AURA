package com.aura.music.data.repository

import com.aura.music.data.local.TrackEntity
import com.aura.music.data.network.DownloadJobResponseData
import com.aura.music.data.network.DownloadTrackMetadataDto
import org.junit.Assert.*
import org.junit.Test

class DownloadTrackMetadataTest {
    private val track = TrackEntity(
        id = "trk_fixture", primaryArtistId = null, albumId = null,
        title = "Piste ixture", normalizedTitle = "piste", displayArtistName = "Téléchargement",
        canonicalAudioSourceType = "downloaded", isLiked = true, isDownloadedByAura = true,
        createdAt = 1, updatedAt = 1,
    )
    private val item = DownloadJobResponseData("job_fixture", track.id, "youtube", "succeeded",
        createdAt = "2026-11-19T00:00:00Z", updatedAt = "2026-11-19T00:01:00Z",
        metadata = DownloadTrackMetadataDto("CAFé CON RON", "Bad Bunny", "Album", 180_000, "cover"), audioAvailable = false)

    @Test fun repairsGeneratedLabelAndPreservesLocalAudioAndFavourite() {
        val result = reconcileDownloadTrack(track, item, 10)
        assertEquals("CAFé CON RON", result.title)
        assertEquals("Bad Bunny", result.displayArtistName)
        assertEquals("downloaded", result.canonicalAudioSourceType)
        assertTrue(result.isLiked); assertTrue(result.isDownloadedByAura)
        assertEquals(result, reconcileDownloadTrack(result, item, 20))
    }
    @Test fun preservesManualTitleArtistAlbumAndTimestamp() {
        val manual = track.copy(title = "Mon titre", displayArtistName = "Mon artiste", displayAlbumTitle = "Mon album")
        assertEquals(manual, reconcileDownloadTrack(manual, item, 10))
        val partialManual = track.copy(displayArtistName = "Mon artiste", displayAlbumTitle = "Mon album")
        val result = reconcileDownloadTrack(partialManual, item, 10)
        assertEquals("Mon artiste", result.displayArtistName)
        assertEquals("Mon album", result.displayAlbumTitle)
    }
    @Test fun handlesMissingMetadataAndDoesNotMarkAnUnknownTrackAsCloudReady() {
        val result = reconcileDownloadTrack(null, item.copy(metadata = null), 10)
        assertEquals("Piste ixture", result.title)
        assertEquals("cloud_only", result.canonicalAudioSourceType)
    }
}
