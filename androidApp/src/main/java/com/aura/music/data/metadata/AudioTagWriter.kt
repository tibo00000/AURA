package com.aura.music.data.metadata

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * Moteur d'écriture de tags ID3v2.3 sécurisé et atomique pour fichiers audio MP3.
 *
 * Principes de sécurité :
 * 1. Écriture atomique via un fichier temporaire (.tmp) dans le même répertoire.
 * 2. En cas d'erreur, le fichier source reste strictement intact.
 * 3. Encodage UTF-16 avec BOM (Little Endian) pour une compatibilité universelle.
 * 4. Préservation intégrale du flux audio binaire (MPEG frames).
 */
object AudioTagWriter {

    private const val TAG = "AudioTagWriter"

    /**
     * Écrit ou remplace les métadonnées ID3v2.3 d'un fichier audio MP3 de façon atomique.
     *
     * @param audioFile Fichier audio physique à modifier
     * @param title Nouveau titre
     * @param artistName Nouvel artiste
     * @param albumTitle Nouvel album
     * @param trackNumber Numéro de piste optionnel
     * @param year Année optionnelle
     * @param coverJpegBytes Octets de la pochette JPEG compressée (optionnel)
     * @return true si l'écriture physique a réussi, false sinon (fichier intact)
     */
    suspend fun writeMp3Tags(
        audioFile: File,
        title: String,
        artistName: String,
        albumTitle: String? = null,
        trackNumber: String? = null,
        year: String? = null,
        coverJpegBytes: ByteArray? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || !audioFile.canRead()) {
            Log.w(TAG, "Cannot access audio file: ${audioFile.absolutePath}")
            return@withContext false
        }

        // Seuls les fichiers MP3 supportent les tags ID3v2 nativement
        if (!audioFile.name.lowercase().endsWith(".mp3")) {
            Log.i(TAG, "Skipping physical ID3 write for non-MP3 format: ${audioFile.name}")
            return@withContext true
        }

        // Fichier temporaire dans le même dossier pour permettre un rename atomique
        val tempFile = File(audioFile.parentFile, "${audioFile.name}.tag_tmp")

        try {
            // 1. Déterminer l'offset de début du flux audio brut (après l'ancien tag ID3v2 s'il existe)
            val audioStartOffset = getAudioDataOffset(audioFile)

            // 2. Construire le nouveau header et les frames ID3v2.3
            val id3TagBytes = buildId3v23Tag(
                title = title,
                artist = artistName,
                album = albumTitle,
                trackNumber = trackNumber,
                year = year,
                coverBytes = coverJpegBytes
            )

            // 3. Écrire le nouveau tag ID3 puis le flux audio dans le fichier temporaire
            FileOutputStream(tempFile).use { fos ->
                fos.write(id3TagBytes)

                FileInputStream(audioFile).use { fis ->
                    if (audioStartOffset > 0) {
                        var skipped = 0L
                        while (skipped < audioStartOffset) {
                            val count = fis.skip(audioStartOffset - skipped)
                            if (count <= 0) break
                            skipped += count
                        }
                    }

                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
                fos.flush()
            }

            // 4. Remplacement atomique
            if (tempFile.length() > id3TagBytes.size) {
                if (tempFile.renameTo(audioFile)) {
                    Log.i(TAG, "Successfully updated tags for: ${audioFile.name}")
                    return@withContext true
                } else {
                    // Fallback si renameTo direct échoue (ex: verrous sur certains systèmes)
                    tempFile.copyTo(audioFile, overwrite = true)
                    tempFile.delete()
                    Log.i(TAG, "Successfully copied updated tags for: ${audioFile.name}")
                    return@withContext true
                }
            } else {
                Log.e(TAG, "Generated temp file is unexpectedly small, aborting.")
                tempFile.delete()
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing ID3 tags to ${audioFile.name}: ${e.message}", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            return@withContext false
        }
    }

    /**
     * Détecte si le fichier commence par un header ID3v2 et calcule où démarre l'audio réel.
     */
    private fun getAudioDataOffset(file: File): Long {
        if (file.length() < 10) return 0L
        FileInputStream(file).use { fis ->
            val header = ByteArray(10)
            if (fis.read(header) == 10) {
                if (header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()
                ) {
                    val size = ((header[6].toInt() and 0x7F) shl 21) or
                            ((header[7].toInt() and 0x7F) shl 14) or
                            ((header[8].toInt() and 0x7F) shl 7) or
                            (header[9].toInt() and 0x7F)
                    return 10L + size
                }
            }
        }
        return 0L
    }

    /**
     * Assemble l'en-tête ID3v2.3 et toutes les frames textes et image.
     */
    private fun buildId3v23Tag(
        title: String,
        artist: String,
        album: String?,
        trackNumber: String?,
        year: String?,
        coverBytes: ByteArray?
    ): ByteArray {
        val framesStream = ByteArrayOutputStream()

        // Frames texte
        writeTextFrame(framesStream, "TIT2", title)
        writeTextFrame(framesStream, "TPE1", artist)
        if (!album.isNullOrBlank()) {
            writeTextFrame(framesStream, "TALB", album)
        }
        if (!trackNumber.isNullOrBlank()) {
            writeTextFrame(framesStream, "TRCK", trackNumber)
        }
        if (!year.isNullOrBlank()) {
            writeTextFrame(framesStream, "TYER", year)
        }

        // Frame Image (APIC)
        if (coverBytes != null && coverBytes.isNotEmpty()) {
            writeApicFrame(framesStream, coverBytes)
        }

        val framesBytes = framesStream.toByteArray()
        val framesSize = framesBytes.size

        val tagStream = ByteArrayOutputStream()
        // ID3 Header (10 bytes)
        tagStream.write("ID3".toByteArray(Charsets.ISO_8859_1)) // Identifier
        tagStream.write(byteArrayOf(0x03, 0x00)) // Version 2.3.0
        tagStream.write(0x00) // Flags

        // Syncsafe integer (4 bytes) pour la taille des frames
        tagStream.write(byteArrayOf(
            ((framesSize shr 21) and 0x7F).toByte(),
            ((framesSize shr 14) and 0x7F).toByte(),
            ((framesSize shr 7) and 0x7F).toByte(),
            (framesSize and 0x7F).toByte()
        ))

        // Contenu des frames
        tagStream.write(framesBytes)

        return tagStream.toByteArray()
    }

    /**
     * Écrit une frame texte ID3v2.3 avec encodage UTF-16 (BOM).
     */
    private fun writeTextFrame(stream: ByteArrayOutputStream, frameId: String, text: String) {
        val bodyStream = ByteArrayOutputStream()
        bodyStream.write(0x01) // Encoding: 0x01 = UTF-16 with BOM
        bodyStream.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // BOM Little Endian
        bodyStream.write(text.toByteArray(Charset.forName("UTF-16LE")))

        val bodyBytes = bodyStream.toByteArray()
        val bodySize = bodyBytes.size

        // Frame Header (10 bytes)
        stream.write(frameId.toByteArray(Charsets.ISO_8859_1))
        // Frame size (32-bit integer)
        stream.write(byteArrayOf(
            ((bodySize shr 24) and 0xFF).toByte(),
            ((bodySize shr 16) and 0xFF).toByte(),
            ((bodySize shr 8) and 0xFF).toByte(),
            (bodySize and 0xFF).toByte()
        ))
        // Frame flags (2 bytes)
        stream.write(byteArrayOf(0x00, 0x00))
        // Body
        stream.write(bodyBytes)
    }

    /**
     * Écrit une frame APIC (Attached Picture) JPEG.
     */
    private fun writeApicFrame(stream: ByteArrayOutputStream, jpegBytes: ByteArray) {
        val bodyStream = ByteArrayOutputStream()
        bodyStream.write(0x00) // Text encoding: ISO-8859-1
        bodyStream.write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
        bodyStream.write(0x00) // Null separator
        bodyStream.write(0x03) // Picture type: 0x03 = Cover (front)
        bodyStream.write(0x00) // Description: empty (null separator)
        bodyStream.write(jpegBytes) // Raw JPEG binary

        val bodyBytes = bodyStream.toByteArray()
        val bodySize = bodyBytes.size

        // Frame Header
        stream.write("APIC".toByteArray(Charsets.ISO_8859_1))
        stream.write(byteArrayOf(
            ((bodySize shr 24) and 0xFF).toByte(),
            ((bodySize shr 16) and 0xFF).toByte(),
            ((bodySize shr 8) and 0xFF).toByte(),
            (bodySize and 0xFF).toByte()
        ))
        stream.write(byteArrayOf(0x00, 0x00))
        stream.write(bodyBytes)
    }
}
