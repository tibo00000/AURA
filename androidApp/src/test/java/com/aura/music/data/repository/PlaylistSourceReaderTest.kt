package com.aura.music.data.repository

import android.app.Application
import com.aura.music.data.playlist.PlaylistSourceReader
import com.aura.music.data.playlist.PlaylistSourceReadException
import com.aura.music.data.playlist.PlaylistSourceReference
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistSourceReaderTest {
    private val link = PlaylistSourceReference("spotify", "0123456789ABCDEFGHIJKL")
    private val endpoint = "https://api.spotify.com/v1/playlists/${link.externalId}/items"

    @Test
    fun spotifyReadsEveryPageAndKeepsMusicalMetadata() = runBlocking {
        val reader = PlaylistSourceReader({ "fixture-token" }) { uri, token ->
            assertEquals("fixture-token", token)
            when (uri.query) {
                "fields=snapshot_id" -> JSONObject("""{"snapshot_id":"fixture"}""")
                "limit=50" -> page("0123456789ABCDEFGHIJKN", "First", "$endpoint?offset=1", 2)
                "offset=1" -> page("0123456789ABCDEFGHIJKM", "Second", null, 2)
                else -> error("Unexpected page $uri")
            }
        }
        val tracks = reader.read(link)
        assertEquals(listOf("First", "Second"), tracks.map { it.title })
        assertEquals("Artist", tracks.last().artist)
        assertEquals("Album", tracks.last().album)
        assertEquals(210000L, tracks.last().durationMs)
        assertEquals("https://i.scdn.co/image/test", tracks.last().coverUri)
    }

    @Test
    fun incompleteAndUnexpectedUrlsCannotProduceACommitInventory() = runBlocking {
        for (next in listOf(null, "https://example.org/a")) {
            val calls = mutableListOf<URI>()
            val reader = PlaylistSourceReader({ "fixture-token" }) { uri, _ ->
                calls += uri
                if (uri.query == "fields=snapshot_id") JSONObject("""{"snapshot_id":"fixture"}""")
                else page("0123456789ABCDEFGHIJKN", "First", next, 2)
            }
            val failure = runCatching { reader.read(link) }.exceptionOrNull()
            assertTrue(failure is PlaylistSourceReadException)
            assertEquals(2, calls.size)
        }
    }

    @Test
    fun deezerFailsClosedWhenTheTwoInventoriesDiffer() = runBlocking {
        var reads = 0
        val reader = PlaylistSourceReader({ null }) { _, token ->
            assertEquals(null, token)
            JSONObject("""{"total":1,"data":[{"id":${++reads},"title":"Title","artist":{"name":"Artist"}}]}""")
        }
        assertTrue(runCatching { reader.read(PlaylistSourceReference("deezer", "123")) }
            .exceptionOrNull() is PlaylistSourceReadException)
        assertEquals(2, reads)
    }

    private fun page(trackId: String, title: String, next: String?, total: Int): JSONObject = JSONObject("""
        {"total":$total,"items":[{"item":{
            "id":"$trackId","name":"$title","artists":[{"name":"Artist"}],"duration_ms":210000,
            "album":{"name":"Album","images":[{"url":"https://i.scdn.co/image/test"}]}
        }}]}
    """.trimIndent()).put("next", next ?: JSONObject.NULL)
}
