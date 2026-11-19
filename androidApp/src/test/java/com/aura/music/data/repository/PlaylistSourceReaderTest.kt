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
                "offset=1" -> page("0123456789ABCDEFGHIJKM", "Second", null, 2, offset = 1)
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
    fun spotifyRejectsGapsAndRepeatedOffsetsEvenWhenTheTotalWouldMatch() = runBlocking {
        for ((nextOffset, returnedOffset) in listOf(2 to 2, 1 to 0)) {
            val reader = PlaylistSourceReader({ "fixture-token" }) { uri, _ ->
                when (uri.query) {
                    "fields=snapshot_id" -> JSONObject("""{"snapshot_id":"fixture"}""")
                    "limit=50" -> page("0123456789ABCDEFGHIJKN", "First", "$endpoint?offset=$nextOffset", 2)
                    else -> page("0123456789ABCDEFGHIJKM", "Unexpected", null, 2, offset = returnedOffset)
                }
            }
            assertTrue(runCatching { reader.read(link) }.exceptionOrNull() is PlaylistSourceReadException)
        }
    }

    @Test
    fun spotifyChecksItemShapeWithoutTreatingDocumentedNullAsAnError() = runBlocking {
        for (value in listOf("null", "42")) {
            val reader = PlaylistSourceReader({ "fixture-token" }) { uri, _ ->
                if (uri.query == "fields=snapshot_id") JSONObject("""{"snapshot_id":"fixture"}""")
                else JSONObject("""{"total":1,"offset":0,"next":null,"items":[{"item":$value}]}""")
            }
            val result = runCatching { reader.read(link) }
            if (value == "null") assertTrue(result.getOrThrow().isEmpty())
            else assertTrue(result.exceptionOrNull() is PlaylistSourceReadException)
        }
    }

    @Test
    fun deezerReadsTheSameContiguousInventoryTwiceAndKeepsMetadata() = runBlocking {
        val base = "https://api.deezer.com/playlist/123/tracks"
        val calls = mutableListOf<URI>()
        val reader = PlaylistSourceReader({ null }) { uri, token ->
            assertEquals(null, token)
            calls += uri
            val id = if (uri.query == "limit=100") 19 else 97
            JSONObject("""
                {"total":2,"data":[{"id":$id,"title":"Title $id","artist":{"name":"Artist"},
                  "duration":90,"album":{"title":"Album","cover_big":"https://cdn-images.dzcdn.net/cover.jpg"}}]}
            """.trimIndent()).put("next", if (id == 19) "${base.replace("https:", "http:")}?index=1" else JSONObject.NULL)
        }
        val tracks = reader.read(PlaylistSourceReference("deezer", "123"))
        assertEquals(listOf("19", "97"), tracks.map { it.providerId })
        assertEquals(90000L, tracks.last().durationMs)
        assertEquals("Album", tracks.last().album)
        assertEquals(listOf("$base?limit=100", "$base?index=1", "$base?limit=100", "$base?index=1"),
            calls.map(URI::toString))
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

    @Test
    fun structuredCreditsKeepPunctuationAndPrincipalBeforeContributors() = runBlocking {
        val reader = PlaylistSourceReader({ "fixture" }) { uri, _ ->
            if (uri.host == "api.spotify.com") JSONObject("""{"id":"0123456789ABCDEFGHIJKN","name":"Song","artists":[{"id":"0123456789ABCDEFGHIJKL","name":"Tyler, The Creator"},{"id":"0123456789ABCDEFGHIJKM","name":"Guest"}]}""")
            else JSONObject("""{"id":19,"title":"Song","artist":{"id":1,"name":"Principal"},"contributors":[{"id":2,"name":"Guest"},{"id":1,"name":"Principal"}]}""")
        }
        val spotify = reader.readTrackMetadata("trk_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("v1:track:spotify:0123456789ABCDEFGHIJKN".toByteArray()))!!
        assertEquals(listOf("Tyler, The Creator","Guest"), spotify.artists.map { it.name })
        val deezer = reader.readTrackMetadata("track:deezer:19")!!
        assertEquals(listOf("Principal","Guest"), deezer.artists.map { it.name })
        assertEquals(listOf("primary","performer"), deezer.artists.map { it.role })
    }

    @Test
    fun metadataLookupDistinguishesRemovedSourcesFromTemporaryFailures() = runBlocking {
        val removed = PlaylistSourceReader({ null }) { _,_ -> throw PlaylistSourceReadException("Removed", retryable=false) }
        assertEquals(null,removed.readTrackMetadata("track:deezer:19"))
        val offline = PlaylistSourceReader({ null }) { _,_ -> throw PlaylistSourceReadException("Offline") }
        assertTrue(runCatching { offline.readTrackMetadata("track:deezer:19") }.exceptionOrNull() is PlaylistSourceReadException)
    }

    private fun page(trackId: String, title: String, next: String?, total: Int, offset: Int = 0): JSONObject = JSONObject("""
        {"total":$total,"offset":$offset,"items":[{"item":{
            "id":"$trackId","name":"$title","artists":[{"name":"Artist"}],"duration_ms":210000,
            "album":{"name":"Album","images":[{"url":"https://i.scdn.co/image/test"}]}
        }}]}
    """.trimIndent()).put("next", next ?: JSONObject.NULL)
}
