package com.aura.music.data.repository

import com.aura.music.data.playlist.PlaylistSourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistSourceReferenceTest {
    private val spotifyId = "0123456789ABCDEFGHIJKL"

    @Test
    fun canonicalizesProviderLinksAndSpotifyUris() {
        val spotify = PlaylistSourceReference("spotify", spotifyId)
        assertEquals(spotify, PlaylistSourceReference.parse("https://open.spotify.com/intl-fr/playlist/$spotifyId?si=123"))
        assertEquals(spotify, PlaylistSourceReference.parse("spotify:playlist:$spotifyId"))
        assertEquals(PlaylistSourceReference("deezer", "12345"), PlaylistSourceReference.parse("https://www.deezer.com/fr/playlist/12345"))
    }

    @Test
    fun rejectsForeignHostsCredentialsAndNonPlaylistReferences() {
        listOf(
            "https://open.spotify.com.example.org/playlist/$spotifyId",
            "https://open.spotify.com@example.org/playlist/$spotifyId",
            "https://open.spotify.com/track/$spotifyId",
            "https://deezer.com/playlist/0123",
            "spotify:liked_songs",
            "https:invalid",
        ).forEach { assertNull(it, PlaylistSourceReference.parse(it)) }
    }
}
