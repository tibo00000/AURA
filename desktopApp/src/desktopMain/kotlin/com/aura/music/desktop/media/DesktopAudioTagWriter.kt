package com.aura.music.desktop.media

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Moteur d'écriture atomique et sécurisée de tags ID3v2.3 pour les fichiers MP3 sur Desktop.
 * Garantit l'intégrité binaire du flux audio via écriture sur fichier temporaire et bascule atomique.
 */
object DesktopAudioTagWriter {

    data class TagUpdate(
        val title: String,
        val artist: String,
        val album: String?,
        val trackNumber: Int?,
        val year: Int?,
        val newCoverBytes: ByteArray? = null,
        val coverMimeType: String = "image/jpeg"
    )

    fun writeMp3Tags(file: File, update: TagUpdate): Boolean {
        if (!file.exists() || !file.canWrite() || file.extension.lowercase() != "mp3") {
            return false
        }

        // Fichier temporaire créé dans le MÊME répertoire pour garantir l'atomicité sur le même volume
        val tempFile = File(file.parentFile, "${file.nameWithoutExtension}_tmp_${System.currentTimeMillis()}.tmp")

        try {
            val audioStartOffset = findAudioStartOffset(file)

            ByteArrayOutputStream().use { tagStream ->
                // 1. Construit les frames ID3v2.3
                val framesStream = ByteArrayOutputStream()

                // TIT2
                writeTextFrame(framesStream, "TIT2", update.title)
                // TPE1
                writeTextFrame(framesStream, "TPE1", update.artist)
                // TALB
                if (!update.album.isNullOrBlank()) {
                    writeTextFrame(framesStream, "TALB", update.album)
                }
                // TRCK
                if (update.trackNumber != null && update.trackNumber > 0) {
                    writeTextFrame(framesStream, "TRCK", update.trackNumber.toString())
                }
                // TYER
                if (update.year != null && update.year > 0) {
                    writeTextFrame(framesStream, "TYER", update.year.toString())
                }
                // APIC
                if (update.newCoverBytes != null && update.newCoverBytes.isNotEmpty()) {
                    writeApicFrame(framesStream, update.newCoverBytes, update.coverMimeType)
                }

                val framesBytes = framesStream.toByteArray()

                // 2. En-tête ID3v2.3 (10 octets)
                tagStream.write("ID3".toByteArray(StandardCharsets.ISO_8859_1))
                tagStream.write(3) // Major version 2.3
                tagStream.write(0) // Revision
                tagStream.write(0) // Flags
                tagStream.write(encodeSyncSafeSize(framesBytes.size))

                // 3. Écrit les frames
                tagStream.write(framesBytes)

                val id3Bytes = tagStream.toByteArray()

                // 4. Écrit le nouveau fichier (ID3v2 + flux audio original)
                FileOutputStream(tempFile).use { out ->
                    out.write(id3Bytes)
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(audioStartOffset)
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (raf.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            // 5. Remplacement atomique (avec fallback en cas d'AtomicMoveNotSupportedException)
            val sourcePath = tempFile.toPath()
            val targetPath = file.toPath()

            try {
                Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                // Fallback de sécurité sur les systèmes ou partitions où ATOMIC_MOVE n'est pas supporté
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                tempFile.delete()
            }

            return true
        } catch (e: Exception) {
            System.err.println("Erreur lors de l'écriture des tags ID3v2.3 sur ${file.name}: ${e.message}")
            if (tempFile.exists()) {
                tempFile.delete()
            }
            return false
        }
    }

    private fun findAudioStartOffset(file: File): Long {
        if (file.length() < 10) return 0L
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.readFully(header)
            if (header[0] == 'I'.toByte() && header[1] == 'D'.toByte() && header[2] == '3'.toByte()) {
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)
                return 10L + tagSize
            }
        }
        return 0L
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val frameData = ByteArray(1 + textBytes.size)
        frameData[0] = 3 // UTF-8 encoding flag
        System.arraycopy(textBytes, 0, frameData, 1, textBytes.size)

        out.write(frameId.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(encodeInt32(frameData.size))
        out.write(0) // Flag 1
        out.write(0) // Flag 2
        out.write(frameData)
    }

    private fun writeApicFrame(out: ByteArrayOutputStream, imageBytes: ByteArray, mimeType: String) {
        val apicStream = ByteArrayOutputStream()
        apicStream.write(0) // ISO-8859-1 encoding for mime and description
        apicStream.write(mimeType.toByteArray(StandardCharsets.ISO_8859_1))
        apicStream.write(0) // Null terminator for mime
        apicStream.write(3) // Picture type: Cover (front)
        apicStream.write("Cover".toByteArray(StandardCharsets.ISO_8859_1))
        apicStream.write(0) // Null terminator for description
        apicStream.write(imageBytes)

        val frameData = apicStream.toByteArray()
        out.write("APIC".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(encodeInt32(frameData.size))
        out.write(0) // Flag 1
        out.write(0) // Flag 2
        out.write(frameData)
    }

    private fun encodeSyncSafeSize(size: Int): ByteArray {
        return byteArrayOf(
            ((size shr 21) and 0x7F).toByte(),
            ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(),
            (size and 0x7F).toByte()
        )
    }

    private fun encodeInt32(size: Int): ByteArray {
        return byteArrayOf(
            ((size shr 24) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte()
        )
    }
}
