package com.aura.music.data.repository

import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.PlaylistTrackMetadata
import com.aura.music.data.playlist.PlaylistRecordingIdentity
import com.aura.music.data.playlist.suggestedLibraryTracks
import com.aura.music.data.playlist.suggestedPlaylistLinks
import com.aura.music.data.playlist.playlistMatchCandidates
import org.junit.Assert.*
import org.junit.Test

class PlaylistRecordingIdentityTest {
    private val source = PlaylistTrackMetadata("spotify", "0123456789ABCDEFGHIJKL", "NUEVAYoL", "Bad Bunny", trackId = "source")
    private fun row(id: String, trackId: String = "local", title: String = "NUEVAYoL", artist: String = "Bad Bunny") =
        PlaylistTrackRow(id, "mix", trackId, 0, 1, title, artist, "Album", "content://media/42", 185000, null)
    private fun library(id: String) = TrackListRow(id, null, null, "NUEVAYoL", "Bad Bunny", "Album", "content://media/42", 185000, null, false)

    @Test fun catalogueAliasesHaveTheSameIdentityWithoutMetadata() {
        for (identity in listOf(PlaylistRecordingIdentity("spotify", source.providerId), PlaylistRecordingIdentity("deezer", "12345"))) {
            identity.aliases().forEach { assertEquals(it, identity, PlaylistRecordingIdentity.parse(it)) }
        }
        assertNotEquals(PlaylistRecordingIdentity("deezer", "12345"), PlaylistRecordingIdentity("spotify", source.providerId))
        listOf("track:local:12345", "trk_cloud_bad_bunny_nuevayol", "spotify:bad-id", "trk_invalid", "000123", "https://example.org/audio").forEach {
            assertNull(it, PlaylistRecordingIdentity.parse(it))
        }
    }

    @Test fun explicitBatchProposesOneOccurrenceWithoutChangingItsIdentity() {
        val original = row("occurrence-kept")
        assertEquals(mapOf("source" to "occurrence-kept"), suggestedPlaylistLinks(listOf(source), listOf(original)))
        assertEquals("local", original.trackId)
        assertEquals("content://media/42", original.contentUri)
    }

    @Test fun repeatedOccurrencesAndDistinctSourceVersionsRequireAnIndividualChoice() {
        assertTrue(suggestedPlaylistLinks(listOf(source), listOf(row("first"), row("second"))).isEmpty())
        assertTrue(suggestedPlaylistLinks(listOf(source, source.copy(providerId = "ABCDEFGHIJKLMNOPQRSTUV", trackId = "other-version")), listOf(row("first"))).isEmpty())
        assertTrue(suggestedPlaylistLinks(listOf(source), listOf(row("wrong", artist = "Another artist"))).isEmpty())
    }

    @Test fun LibrarySuggestionFindsTheDownloadedCopyOutsideThePlaylist() {
        val candidate = library("track:local:42")
        assertEquals(mapOf("source" to candidate), suggestedLibraryTracks(listOf(source), listOf(candidate), emptyList()))
        assertTrue(suggestedLibraryTracks(listOf(source), listOf(candidate, library("other-recording")), emptyList()).isEmpty())
        assertTrue(suggestedLibraryTracks(listOf(source), listOf(candidate), listOf(row("already-there"))).isEmpty())
    }

    @Test fun individualChoicesFilterUnrelatedTitlesAndKeepIntentionalOccurrencesAndDistinctCopies() {
        val first = row("first", "already-here")
        val second = row("second", "already-here")
        val cloudCopy = library("cloud-copy").copy(contentUri = null)
        val phoneCopy = library("phone-copy")
        val candidates = playlistMatchCandidates(source,
            listOf(first, row("wrong-title", title = "Pétunias"), second, row("wrong-artist", artist = "Another artist")),
            listOf(cloudCopy, library("already-here"), phoneCopy, phoneCopy, library("wrong").copy(title = "Tu connais")))
        assertEquals(listOf("first", "second"), candidates.playlist.map { it.playlistItemId })
        assertEquals(listOf("phone-copy", "cloud-copy"), candidates.library.map { it.id })
        assertTrue(playlistMatchCandidates(source.copy(artist = ""), listOf(first), listOf(phoneCopy)).library.isEmpty())
    }
}
