package com.aura.music.desktop.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class DesktopAudioTagWriterTest {

    @Test
    fun testMp3TagWriteAndReadRoundTrip() {
        val tempMp3 = File.createTempFile("test_audio_", ".mp3")
        try {
            // Crée un fichier MP3 synthétique minimal avec des trames MP3 factices
            FileOutputStream(tempMp3).use { out ->
                // Trame audio MPEG 1 Layer III sync word (0xFF 0xFB)
                val dummyAudioData = ByteArray(4096) { 0x55.toByte() }
                dummyAudioData[0] = 0xFF.toByte()
                dummyAudioData[1] = 0xFB.toByte()
                out.write(dummyAudioData)
            }

            val update = DesktopAudioTagWriter.TagUpdate(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                album = "A Night at the Opera",
                trackNumber = 4,
                year = 1975
            )

            val writeSuccess = DesktopAudioTagWriter.writeMp3Tags(tempMp3, update)
            assertTrue("L'écriture des tags ID3v2.3 doit réussir", writeSuccess)

            // Relecture avec le reader de métadonnées
            val readMetadata = DesktopMediaMetadataReader.readMetadata(tempMp3)
            assertEquals("Bohemian Rhapsody", readMetadata.title)
            assertEquals("Queen", readMetadata.artist)
            assertEquals("A Night at the Opera", readMetadata.album)
            assertEquals(4, readMetadata.trackNumber)
            assertEquals(1975, readMetadata.year)
        } finally {
            tempMp3.delete()
        }
    }
}
