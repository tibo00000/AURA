package com.aura.music.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioIntegrityValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDummyFile(name: String, header: ByteArray, totalSize: Int = 4096): File {
        val file = tempFolder.newFile(name)
        val content = ByteArray(totalSize)
        System.arraycopy(header, 0, content, 0, header.size.coerceAtMost(totalSize))
        file.writeBytes(content)
        return file
    }

    @Test
    fun testValidMp3WithId3Header() {
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00, 0x00)
        val file = createDummyFile("test.mp3", header, totalSize = 3000)
        assertTrue(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testValidMp3WithFrameSyncHeader() {
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x44.toByte())
        val file = createDummyFile("test_sync.mp3", header, totalSize = 3000)
        assertTrue(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testValidM4aWithFtyp() {
        val header = byteArrayOf(0x00, 0x00, 0x00, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val file = createDummyFile("test.m4a", header, totalSize = 4096)
        assertTrue(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testValidOgg() {
        val header = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
        val file = createDummyFile("test.ogg", header, totalSize = 4096)
        assertTrue(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testValidWebm() {
        val header = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())
        val file = createDummyFile("test.webm", header, totalSize = 4096)
        assertTrue(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testValidFlac() {
        val header = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
        val file = createDummyFile("test.flac", header, totalSize = 4096)
        assertTrue(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testRejectFileTooSmall() {
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03)
        val file = createDummyFile("small.mp3", header, totalSize = 1000) // Less than 2048 bytes
        assertFalse(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testRejectHtmlPayload() {
        val htmlContent = "<!DOCTYPE html><html><body>Error 404 Not Found</body></html>".toByteArray()
        val file = tempFolder.newFile("error_404.mp3")
        file.writeBytes(htmlContent + ByteArray(3000))
        assertFalse(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testRejectJsonErrorPayload() {
        val jsonContent = "{\"error\": \"Download failed\", \"status\": 500}".toByteArray()
        val file = tempFolder.newFile("error_500.mp3")
        file.writeBytes(jsonContent + ByteArray(3000))
        assertFalse(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }

    @Test
    fun testRejectNonExistentFile() {
        val nonExistentFile = File(tempFolder.root, "does_not_exist.mp3")
        assertFalse(AudioIntegrityValidator.isValidAudioFile(nonExistentFile, checkMetadata = false))
    }

    @Test
    fun testRejectCorruptedGarbageFile() {
        val garbageHeader = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val file = createDummyFile("corrupt.bin", garbageHeader, totalSize = 3000)
        assertFalse(AudioIntegrityValidator.isValidAudioFile(file, checkMetadata = false))
    }
}
