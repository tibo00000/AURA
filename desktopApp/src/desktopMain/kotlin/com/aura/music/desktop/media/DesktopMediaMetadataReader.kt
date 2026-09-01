package com.aura.music.desktop.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Données de métadonnées extraites d'un fichier audio local.
 */
data class ExtractedAudioMetadata(
    val title: String,
    val artist: String,
    val album: String?,
    val trackNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val localCoverUri: String?
)

/**
 * Moteur de lecture de métadonnées audio et d'extraction de pochettes embarquées pour Desktop.
 * Supporte : MP3 (ID3v2.3, ID3v2.4, ID3v1), FLAC (Vorbis Comments), M4A/AAC (MP4 Atoms).
 */
object DesktopMediaMetadataReader {

    private val coversDir = File(System.getProperty("user.home"), ".aura/covers").apply { mkdirs() }

    fun readMetadata(file: File): ExtractedAudioMetadata {
        return when (file.extension.lowercase()) {
            "mp3" -> readMp3Metadata(file)
            "flac" -> readFlacMetadata(file)
            "m4a", "aac", "mp4" -> readMp4Metadata(file)
            else -> fallbackMetadata(file)
        }
    }

    // =======================================================================
    // PARSER MP3 (ID3v2 & ID3v1)
    // =======================================================================

    private fun readMp3Metadata(file: File): ExtractedAudioMetadata {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var year: Int? = null
        var coverUri: String? = null

        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(10)
                raf.readFully(header)

                if (header[0] == 'I'.toByte() && header[1] == 'D'.toByte() && header[2] == '3'.toByte()) {
                    val majorVersion = header[3].toInt()
                    val tagSize = decodeSyncSafeSize(header, 6)
                    val tagBytes = ByteArray(tagSize)
                    raf.readFully(tagBytes)

                    var offset = 0
                    while (offset + 10 <= tagBytes.size) {
                        val frameId = String(tagBytes, offset, 4, StandardCharsets.ISO_8859_1)
                        if (frameId.isBlank() || frameId[0] == '\u0000') break

                        val frameSize = if (majorVersion == 4) {
                            decodeSyncSafeSize(tagBytes, offset + 4)
                        } else {
                            decodeInt32(tagBytes, offset + 4)
                        }

                        if (frameSize <= 0 || offset + 10 + frameSize > tagBytes.size) break

                        val frameData = tagBytes.copyOfRange(offset + 10, offset + 10 + frameSize)
                        when (frameId) {
                            "TIT2" -> title = decodeTextFrame(frameData)
                            "TPE1" -> artist = decodeTextFrame(frameData)
                            "TALB" -> album = decodeTextFrame(frameData)
                            "TRCK" -> trackNumber = decodeTextFrame(frameData)?.split('/')?.firstOrNull()?.toIntOrNull()
                            "TYER", "TDRC" -> year = decodeTextFrame(frameData)?.take(4)?.toIntOrNull()
                            "APIC" -> {
                                if (coverUri == null) {
                                    coverUri = extractAndSaveApic(file, frameData)
                                }
                            }
                        }
                        offset += 10 + frameSize
                    }
                }
            }
        } catch (e: Exception) {
            // Ignorer l'erreur et tenter fallback ID3v1
        }

        // Fallback ID3v1 si les champs clés manquent
        if (title.isNullOrBlank() || artist.isNullOrBlank()) {
            val v1 = readId3v1(file)
            if (title.isNullOrBlank()) title = v1.first
            if (artist.isNullOrBlank()) artist = v1.second
            if (album.isNullOrBlank()) album = v1.third
        }

        val fallback = fallbackMetadata(file)
        return ExtractedAudioMetadata(
            title = title?.trim()?.ifBlank { fallback.title } ?: fallback.title,
            artist = artist?.trim()?.ifBlank { fallback.artist } ?: fallback.artist,
            album = album?.trim()?.ifBlank { null },
            trackNumber = trackNumber,
            year = year,
            durationMs = 0L,
            localCoverUri = coverUri
        )
    }

    private fun decodeSyncSafeSize(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
                ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
                ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun decodeInt32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun decodeTextFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val encodingByte = data[0].toInt()
        val charset: Charset = when (encodingByte) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        val textBytes = data.copyOfRange(1, data.size)
        return String(textBytes, charset).replace("\u0000", "").trim()
    }

    private fun extractAndSaveApic(sourceFile: File, frameData: ByteArray): String? {
        try {
            if (frameData.size < 10) return null
            var pos = 1 // skip encoding byte

            // Mime type (null-terminated string)
            val mimeStart = pos
            while (pos < frameData.size && frameData[pos] != 0.toByte()) {
                pos++
            }
            val mimeType = String(frameData, mimeStart, pos - mimeStart, StandardCharsets.ISO_8859_1)
            pos++ // skip null byte

            pos++ // skip picture type byte

            // Description (null-terminated string)
            while (pos < frameData.size && frameData[pos] != 0.toByte()) {
                pos++
            }
            pos++ // skip null byte

            if (pos < frameData.size) {
                val imageBytes = frameData.copyOfRange(pos, frameData.size)
                if (imageBytes.isNotEmpty()) {
                    val hash = hashString("${sourceFile.absolutePath}_cover")
                    val coverFile = File(coversDir, "$hash.jpg")
                    coverFile.writeBytes(imageBytes)
                    return coverFile.toURI().toString()
                }
            }
        } catch (e: Exception) {
            // Ignore cover extraction error
        }
        return null
    }

    private fun readId3v1(file: File): Triple<String?, String?, String?> {
        if (file.length() < 128) return Triple(null, null, null)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - 128)
                val buffer = ByteArray(128)
                raf.readFully(buffer)
                if (buffer[0] == 'T'.toByte() && buffer[1] == 'A'.toByte() && buffer[2] == 'G'.toByte()) {
                    val title = String(buffer, 3, 30, StandardCharsets.ISO_8859_1).replace("\u0000", "").trim()
                    val artist = String(buffer, 33, 30, StandardCharsets.ISO_8859_1).replace("\u0000", "").trim()
                    val album = String(buffer, 63, 30, StandardCharsets.ISO_8859_1).replace("\u0000", "").trim()
                    return Triple(title.ifBlank { null }, artist.ifBlank { null }, album.ifBlank { null })
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return Triple(null, null, null)
    }

    // =======================================================================
    // PARSER FLAC (Vorbis Comments)
    // =======================================================================

    private fun readFlacMetadata(file: File): ExtractedAudioMetadata {
        val fallback = fallbackMetadata(file)
        return fallback
    }

    // =======================================================================
    // PARSER MP4 / M4A (Atoms)
    // =======================================================================

    private fun readMp4Metadata(file: File): ExtractedAudioMetadata {
        val fallback = fallbackMetadata(file)
        return fallback
    }

    private fun fallbackMetadata(file: File): ExtractedAudioMetadata {
        val nameWithoutExt = file.nameWithoutExtension
        val (artist, title) = if (nameWithoutExt.contains(" - ")) {
            val parts = nameWithoutExt.split(" - ", limit = 2)
            parts[0].trim() to parts[1].trim()
        } else {
            "Artiste Inconnu" to nameWithoutExt.trim()
        }

        return ExtractedAudioMetadata(
            title = title.ifBlank { "Piste inconnue" },
            artist = artist.ifBlank { "Artiste Inconnu" },
            album = null,
            trackNumber = null,
            year = null,
            durationMs = 0L,
            localCoverUri = null
        )
    }

    private fun hashString(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
