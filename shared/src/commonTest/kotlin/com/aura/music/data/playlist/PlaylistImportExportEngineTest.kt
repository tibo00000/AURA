package com.aura.music.data.playlist

import com.aura.music.data.local.TrackListRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistImportExportEngineTest {

    @Test
    fun testParseM3uStandard() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:230,Daft Punk - Get Lucky
            music/daft_punk_get_lucky.mp3
            #EXTINF:185,The Weeknd - Blinding Lights
            C:\Music\The Weeknd\Blinding Lights.mp3
        """.trimIndent()

        val parsed = PlaylistImportExportEngine.parseM3u(m3uContent)
        assertEquals(2, parsed.size)
        assertEquals("Get Lucky", parsed[0].rawTitle)
        assertEquals("Daft Punk", parsed[0].rawArtist)
        assertEquals(230, parsed[0].durationSeconds)

        assertEquals("Blinding Lights", parsed[1].rawTitle)
        assertEquals("The Weeknd", parsed[1].rawArtist)
        assertEquals(185, parsed[1].durationSeconds)
    }

    @Test
    fun testParseM3uWithBomAndSimplePaths() {
        val m3uContent = "\uFEFF#EXTM3U\nQueen - Bohemian Rhapsody.mp3\nPink Floyd - Time.flac"
        val parsed = PlaylistImportExportEngine.parseM3u(m3uContent)
        assertEquals(2, parsed.size)
        assertEquals("Queen - Bohemian Rhapsody", parsed[0].rawTitle)
        assertEquals("Pink Floyd - Time", parsed[1].rawTitle)
    }

    @Test
    fun testParseM3uWithoutArtistSeparatorOrDuration() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:invalid_duration,Intro Instrumental
            /home/user/music/intro_file.ogg
        """.trimIndent()

        val parsed = PlaylistImportExportEngine.parseM3u(m3uContent)
        assertEquals(1, parsed.size)
        assertEquals("Intro Instrumental", parsed[0].rawTitle)
        assertNull(parsed[0].rawArtist)
        assertNull(parsed[0].durationSeconds)
    }

    @Test
    fun testReconciliationMatching() {
        val parsed = listOf(
            ParsedPlaylistItem("Get Lucky", "Daft Punk", 230),
            ParsedPlaylistItem("Unknown Song", "Unknown Artist", 100)
        )

        val localTracks = listOf(
            TrackListRow(
                id = "trk_1",
                artistId = "art_1",
                albumId = "alb_1",
                title = "Get Lucky (Radio Edit)",
                artistName = "Daft Punk feat. Pharrell Williams",
                albumTitle = "Random Access Memories",
                contentUri = "file:///music/get_lucky.mp3",
                durationMs = 230000L,
                coverUri = null,
                isLiked = false,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        val result = PlaylistImportExportEngine.reconcile("Test Playlist", parsed, localTracks)
        assertEquals(2, result.totalEntries)
        assertEquals(1, result.matchedTracks.size)
        assertEquals("trk_1", result.matchedTracks[0].id)
        assertEquals(1, result.unmatchedEntries.size)
        assertEquals("Unknown Song", result.unmatchedEntries[0].rawTitle)
    }

    @Test
    fun testExportM3u8RoundTrip() {
        val tracks = listOf(
            TrackListRow(
                id = "trk_1",
                artistId = "art_1",
                albumId = "alb_1",
                title = "Get Lucky",
                artistName = "Daft Punk",
                albumTitle = "Random Access Memories",
                contentUri = "file:///music/get_lucky.mp3",
                durationMs = 230000L,
                coverUri = null,
                isLiked = false,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        val m3u8 = PlaylistImportExportEngine.exportToM3u8("Hits", tracks)
        assertTrue(m3u8.contains("#EXTM3U"))
        assertTrue(m3u8.contains("#EXTINF:230,Daft Punk - Get Lucky"))
        assertTrue(m3u8.contains("file:///music/get_lucky.mp3"))

        val reParsed = PlaylistImportExportEngine.parseM3u(m3u8)
        assertEquals(1, reParsed.size)
        assertEquals("Get Lucky", reParsed[0].rawTitle)
        assertEquals("Daft Punk", reParsed[0].rawArtist)
        assertEquals(230, reParsed[0].durationSeconds)
    }

    @Test
    fun testExportAndParseJsonRoundTripAndErrorHandling() {
        val tracks = listOf(
            TrackListRow(
                id = "trk_1",
                artistId = "art_1",
                albumId = "alb_1",
                title = "Veridis Quo",
                artistName = "Daft Punk",
                albumTitle = "Discovery",
                contentUri = "file:///music/veridis_quo.flac",
                durationMs = 345000L,
                coverUri = null,
                isLiked = true,
                createdAt = 100L,
                updatedAt = 200L
            )
        )

        val jsonStr = PlaylistImportExportEngine.exportToJson("Synth Classics", tracks)
        val decoded = PlaylistImportExportEngine.parseJson(jsonStr)
        assertNotNull(decoded)
        assertEquals(1, decoded.version)
        assertEquals("Synth Classics", decoded.name)
        assertEquals(1, decoded.tracks.size)
        assertEquals("Veridis Quo", decoded.tracks[0].title)
        assertEquals("Daft Punk", decoded.tracks[0].artist)
        assertEquals("Discovery", decoded.tracks[0].album)
        assertEquals(345000L, decoded.tracks[0].durationMs)

        // Malformed JSON should safely return null without throwing
        assertNull(PlaylistImportExportEngine.parseJson("{not_valid_json"))
    }
}
