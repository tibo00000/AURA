package com.aura.music.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UriPathParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIsContentUri() {
        assertTrue(UriPathParser.isContentUri("content://media/external/audio/media/123"))
        assertFalse(UriPathParser.isContentUri("file:///path/to/song.mp3"))
        assertFalse(UriPathParser.isContentUri("/storage/emulated/0/Music/song.mp3"))
        assertFalse(UriPathParser.isContentUri("https://aura.app/stream"))
    }

    @Test
    fun testDecodePercentPreservesLiteralPlusSigns() {
        // Essential test: URLDecoder converts '+' to ' ', which breaks filenames with '+'
        val input = "Artiste%20-%20Titre%20+%20Remix.mp3"
        val decoded = UriPathParser.decodePercent(input)
        assertEquals("Artiste - Titre + Remix.mp3", decoded)
    }

    @Test
    fun testDecodePercentAccentedCharacters() {
        // UTF-8 multi-byte encoding for "moteurbrûlant"
        val input = "moteurbr%C3%BBlant.mp3"
        val decoded = UriPathParser.decodePercent(input)
        assertEquals("moteurbrûlant.mp3", decoded)
    }

    @Test
    fun testDecodePercentNoOpWhenNoPercent() {
        val input = "simple_track_name_123.mp3"
        assertEquals(input, UriPathParser.decodePercent(input))
    }

    @Test
    fun testParseLocalFileReturnsNullForContentUriOrEmpty() {
        assertNull(UriPathParser.parseLocalFile(null))
        assertNull(UriPathParser.parseLocalFile(""))
        assertNull(UriPathParser.parseLocalFile("   "))
        assertNull(UriPathParser.parseLocalFile("content://media/external/audio/media/42"))
    }

    @Test
    fun testParseLocalFileWithExistingFileDirectAndEncoded() {
        val testFile = tempFolder.newFile("song with spaces.mp3")
        testFile.writeText("audio content")

        // 1. Direct file path
        val resolvedDirect = UriPathParser.parseLocalFile(testFile.absolutePath)
        assertNotNull(resolvedDirect)
        assertTrue(resolvedDirect!!.exists())
        assertEquals(testFile.absolutePath, resolvedDirect.absolutePath)

        // 2. URI with file://
        val uri = testFile.toURI().toString()
        val resolvedUri = UriPathParser.parseLocalFile(uri)
        assertNotNull(resolvedUri)
        assertTrue(resolvedUri!!.exists())
    }

    @Test
    fun testParseLocalFileWithPlusInFilename() {
        val testFile = tempFolder.newFile("Track+Remix.mp3")
        testFile.writeText("audio content")

        val uri = testFile.toURI().toString()
        val resolved = UriPathParser.parseLocalFile(uri)
        assertNotNull(resolved)
        assertTrue(resolved!!.exists())
        assertEquals(testFile.name, resolved.name)
    }
}
