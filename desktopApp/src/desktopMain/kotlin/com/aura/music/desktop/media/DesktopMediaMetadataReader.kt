package com.aura.music.desktop.media

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        val detected = detectAudioExtension(file, null)
        val ext = if (detected != "mp3" && file.extension.equals("mp3", ignoreCase = true)) detected else file.extension.lowercase()
        return when (ext) {
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
        var id3TagSize = 0

        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(10)
                raf.readFully(header)

                if (header[0] == 'I'.toByte() && header[1] == 'D'.toByte() && header[2] == '3'.toByte()) {
                    val majorVersion = header[3].toInt()
                    val tagSize = decodeSyncSafeSize(header, 6)
                    id3TagSize = tagSize + 10
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

        val durationMs = estimateMp3DurationMs(file, id3TagSize)
        val fallback = fallbackMetadata(file)
        return ExtractedAudioMetadata(
            title = title?.trim()?.ifBlank { fallback.title } ?: fallback.title,
            artist = artist?.trim()?.ifBlank { fallback.artist } ?: fallback.artist,
            album = album?.trim()?.ifBlank { null },
            trackNumber = trackNumber,
            year = year,
            durationMs = durationMs,
            localCoverUri = coverUri
        )
    }

    private fun estimateMp3DurationMs(file: File, id3TagSize: Int): Long {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val totalLength = file.length()
                val audioDataSize = (totalLength - id3TagSize).coerceAtLeast(0L)
                if (audioDataSize <= 0L) return 0L

                val buffer = ByteArray(8192)
                raf.seek(id3TagSize.toLong())
                val bytesRead = raf.read(buffer)
                if (bytesRead < 4) return (audioDataSize * 8000L) / (256L * 1000L)

                for (i in 0 until bytesRead - 4) {
                    val b0 = buffer[i].toInt() and 0xFF
                    val b1 = buffer[i + 1].toInt() and 0xFF
                    val b2 = buffer[i + 2].toInt() and 0xFF

                    if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                        val mpegVersion = (b1 shr 3) and 0x03
                        val layer = (b1 shr 1) and 0x03
                        val bitrateIndex = (b2 shr 4) and 0x0F
                        val sampleRateIndex = (b2 shr 2) and 0x03

                        if (mpegVersion == 3 && layer == 1 && bitrateIndex in 1..14 && sampleRateIndex in 0..2) {
                            val sampleRates = intArrayOf(44100, 48000, 32000)
                            val sampleRate = sampleRates[sampleRateIndex]
                            val bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
                            val bitrateKbps = bitrates[bitrateIndex]

                            // Check Xing / Info VBR header
                            val channelMode = (buffer[i + 3].toInt() and 0xC0) shr 6
                            val xingOffset = i + 4 + if (channelMode == 3) 17 else 32
                            if (xingOffset + 12 <= bytesRead) {
                                val xingHeader = String(buffer, xingOffset, 4, StandardCharsets.ISO_8859_1)
                                if (xingHeader == "Xing" || xingHeader == "Info") {
                                    val flags = readUInt32BE(buffer, xingOffset + 4)
                                    val hasFrames = (flags and 0x01L) != 0L
                                    if (hasFrames && sampleRate > 0) {
                                        val frameCount = readUInt32BE(buffer, xingOffset + 8)
                                        val duration = (frameCount * 1152L * 1000L) / sampleRate
                                        if (duration > 0L) return duration
                                    }
                                }
                            }

                            if (bitrateKbps > 0) {
                                return (audioDataSize * 8000L) / (bitrateKbps * 1000L)
                            }
                        }
                    }
                }
                return (audioDataSize * 8000L) / (256L * 1000L)
            }
        } catch (e: Exception) {
            return 0L
        }
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

            // Recherche binaire robuste du header JPEG (0xFF, 0xD8, 0xFF) ou PNG (0x89, 0x50, 0x4E, 0x47)
            var imageStart = -1
            for (i in 0 until frameData.size - 3) {
                val b0 = frameData[i].toInt() and 0xFF
                val b1 = frameData[i + 1].toInt() and 0xFF
                val b2 = frameData[i + 2].toInt() and 0xFF
                val b3 = frameData[i + 3].toInt() and 0xFF

                if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                    imageStart = i
                    break
                }
                if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                    imageStart = i
                    break
                }
            }

            if (imageStart != -1 && imageStart < frameData.size) {
                val imageBytes = frameData.copyOfRange(imageStart, frameData.size)
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
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var year: Int? = null
        var durationMs = 0L
        var coverUri: String? = null

        try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                    magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
                ) {
                    return fallbackMetadata(file)
                }

                var isLastBlock = false
                while (!isLastBlock && raf.filePointer < raf.length()) {
                    val header = ByteArray(4)
                    if (raf.read(header) < 4) break
                    isLastBlock = (header[0].toInt() and 0x80) != 0
                    val blockType = header[0].toInt() and 0x7F
                    val blockSize = ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)

                    if (blockSize <= 0) break

                    when (blockType) {
                        0 -> { // STREAMINFO
                            if (blockSize >= 18) {
                                val streamInfo = ByteArray(blockSize)
                                raf.readFully(streamInfo)
                                // Sample rate (20 bits), channels (3 bits), bits per sample (5 bits), total samples (36 bits)
                                val b10 = streamInfo[10].toLong() and 0xFF
                                val b11 = streamInfo[11].toLong() and 0xFF
                                val b12 = streamInfo[12].toLong() and 0xFF
                                val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)

                                val b13 = streamInfo[13].toLong() and 0xFF
                                val b14 = streamInfo[14].toLong() and 0xFF
                                val b15 = streamInfo[15].toLong() and 0xFF
                                val b16 = streamInfo[16].toLong() and 0xFF
                                val b17 = streamInfo[17].toLong() and 0xFF
                                val totalSamples = ((b13 and 0x0F) shl 32) or (b14 shl 24) or (b15 shl 16) or (b16 shl 8) or b17

                                if (sampleRate > 0) {
                                    durationMs = (totalSamples * 1000L) / sampleRate
                                }
                            } else {
                                raf.skipBytes(blockSize)
                            }
                        }
                        4 -> { // VORBIS_COMMENT
                            val commentData = ByteArray(blockSize)
                            raf.readFully(commentData)
                            val bb = ByteBuffer.wrap(commentData).order(ByteOrder.LITTLE_ENDIAN)
                            if (bb.remaining() >= 4) {
                                val vendorLength = bb.int
                                if (vendorLength in 0..bb.remaining()) {
                                    bb.position(bb.position() + vendorLength)
                                    if (bb.remaining() >= 4) {
                                        val userCommentListLength = bb.int
                                        for (c in 0 until userCommentListLength) {
                                            if (bb.remaining() < 4) break
                                            val commentLength = bb.int
                                            if (commentLength < 0 || commentLength > bb.remaining()) break
                                            val commentBytes = ByteArray(commentLength)
                                            bb.get(commentBytes)
                                            val comment = String(commentBytes, StandardCharsets.UTF_8)
                                            val eqIdx = comment.indexOf('=')
                                            if (eqIdx != -1) {
                                                val key = comment.substring(0, eqIdx).uppercase()
                                                val value = comment.substring(eqIdx + 1).trim()
                                                when (key) {
                                                    "TITLE" -> if (title.isNullOrBlank()) title = value
                                                    "ARTIST" -> if (artist.isNullOrBlank()) artist = value
                                                    "ALBUM" -> if (album.isNullOrBlank()) album = value
                                                    "TRACKNUMBER" -> if (trackNumber == null) trackNumber = value.split('/').firstOrNull()?.toIntOrNull()
                                                    "DATE", "YEAR" -> if (year == null) year = value.take(4).toIntOrNull()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        6 -> { // PICTURE
                            if (coverUri == null && blockSize in 32..(20 * 1024 * 1024)) {
                                val picData = ByteArray(blockSize)
                                raf.readFully(picData)
                                val bb = ByteBuffer.wrap(picData).order(ByteOrder.BIG_ENDIAN)
                                if (bb.remaining() >= 8) {
                                    bb.int // picture type
                                    val mimeLength = bb.int
                                    if (mimeLength in 0..bb.remaining()) {
                                        bb.position(bb.position() + mimeLength)
                                        if (bb.remaining() >= 4) {
                                            val descLength = bb.int
                                            if (descLength in 0..bb.remaining()) {
                                                bb.position(bb.position() + descLength)
                                                if (bb.remaining() >= 20) {
                                                    bb.position(bb.position() + 16) // width, height, color depth, colors used
                                                    val dataLength = bb.int
                                                    if (dataLength in 1..bb.remaining()) {
                                                        val imageBytes = ByteArray(dataLength)
                                                        bb.get(imageBytes)
                                                        val hash = hashString("${file.absolutePath}_cover")
                                                        val coverFile = File(coversDir, "$hash.jpg")
                                                        coverFile.writeBytes(imageBytes)
                                                        coverUri = coverFile.toURI().toString()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                raf.skipBytes(blockSize)
                            }
                        }
                        else -> {
                            raf.skipBytes(blockSize)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        val fallback = fallbackMetadata(file)
        return ExtractedAudioMetadata(
            title = title?.trim()?.ifBlank { fallback.title } ?: fallback.title,
            artist = artist?.trim()?.ifBlank { fallback.artist } ?: fallback.artist,
            album = album?.trim()?.ifBlank { null },
            trackNumber = trackNumber,
            year = year,
            durationMs = durationMs,
            localCoverUri = coverUri
        )
    }

    // =======================================================================
    // PARSER MP4 / M4A (Atoms)
    // =======================================================================

    private fun readMp4Metadata(file: File): ExtractedAudioMetadata {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var year: Int? = null
        var durationMs = 0L
        var coverUri: String? = null

        try {
            RandomAccessFile(file, "r").use { raf ->
                parseMp4Atoms(
                    raf = raf,
                    start = 0L,
                    end = raf.length(),
                    onMetadata = { t, a, al, tr, yr, d, c ->
                        if (t != null) title = t
                        if (a != null) artist = a
                        if (al != null) album = al
                        if (tr != null) trackNumber = tr
                        if (yr != null) year = yr
                        if (d > 0L) durationMs = d
                        if (c != null) coverUri = c
                    },
                    sourceFile = file
                )
            }
        } catch (e: Exception) {
            // ignore
        }

        val fallback = fallbackMetadata(file)
        return ExtractedAudioMetadata(
            title = title?.trim()?.ifBlank { fallback.title } ?: fallback.title,
            artist = artist?.trim()?.ifBlank { fallback.artist } ?: fallback.artist,
            album = album?.trim()?.ifBlank { null },
            trackNumber = trackNumber,
            year = year,
            durationMs = durationMs,
            localCoverUri = coverUri
        )
    }

    private fun parseMp4Atoms(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        onMetadata: (title: String?, artist: String?, album: String?, track: Int?, year: Int?, duration: Long, cover: String?) -> Unit,
        sourceFile: File
    ) {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val header = ByteArray(8)
            if (raf.read(header) < 8) break
            var size = readUInt32BE(header, 0)
            val type = String(header, 4, 4, StandardCharsets.ISO_8859_1)

            val headerSize: Long
            if (size == 1L) {
                // Extended 64-bit size
                val ext = ByteArray(8)
                if (raf.read(ext) < 8) break
                size = readUInt64BE(ext, 0)
                headerSize = 16L
            } else if (size == 0L) {
                size = end - pos
                headerSize = 8L
            } else {
                headerSize = 8L
            }

            if (size < headerSize) break
            val atomEnd = pos + size
            val payloadStart = pos + headerSize
            val payloadSize = size - headerSize

            when (type) {
                "moov", "trak", "mdia", "minf", "stbl", "udta" -> {
                    // Container atoms: recurse
                    parseMp4Atoms(raf, payloadStart, atomEnd, onMetadata, sourceFile)
                }
                "meta" -> {
                    // meta atom has 4 bytes flags/version before child atoms
                    val metaChildStart = payloadStart + 4
                    if (metaChildStart < atomEnd) {
                        parseMp4Atoms(raf, metaChildStart, atomEnd, onMetadata, sourceFile)
                    }
                }
                "ilst" -> {
                    parseIlst(raf, payloadStart, atomEnd, onMetadata, sourceFile)
                }
                "mvhd" -> {
                    if (payloadSize >= 20) {
                        raf.seek(payloadStart)
                        val mvhdBuf = ByteArray(payloadSize.coerceAtMost(36).toInt())
                        raf.readFully(mvhdBuf)
                        val version = mvhdBuf[0].toInt()
                        var durationMs = 0L
                        if (version == 0 && mvhdBuf.size >= 24) {
                            val timescale = readUInt32BE(mvhdBuf, 12)
                            val duration = readUInt32BE(mvhdBuf, 16)
                            if (timescale > 0L) durationMs = (duration * 1000L) / timescale
                        } else if (version == 1 && mvhdBuf.size >= 36) {
                            val timescale = readUInt32BE(mvhdBuf, 20)
                            val duration = readUInt64BE(mvhdBuf, 24)
                            if (timescale > 0L) durationMs = (duration * 1000L) / timescale
                        }
                        if (durationMs > 0L) {
                            onMetadata(null, null, null, null, null, durationMs, null)
                        }
                    }
                }
                "mdat" -> {
                    // Skip mdat in O(1) without reading into memory!
                }
            }

            pos = atomEnd
        }
    }

    private fun parseIlst(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        onMetadata: (title: String?, artist: String?, album: String?, track: Int?, year: Int?, duration: Long, cover: String?) -> Unit,
        sourceFile: File
    ) {
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val header = ByteArray(8)
            if (raf.read(header) < 8) break
            val size = readUInt32BE(header, 0)
            val tag = String(header, 4, 4, StandardCharsets.ISO_8859_1)

            if (size < 8) break
            val tagEnd = pos + size
            val tagPayloadStart = pos + 8

            // Each ilst child contains a "data" atom
            raf.seek(tagPayloadStart)
            val dataHeader = ByteArray(8)
            if (raf.read(dataHeader) == 8) {
                val dataSize = readUInt32BE(dataHeader, 0)
                val dataType = String(dataHeader, 4, 4, StandardCharsets.ISO_8859_1)
                if (dataType == "data" && dataSize >= 16) {
                    val typeIndicator = ByteArray(4)
                    raf.readFully(typeIndicator)
                    val locale = ByteArray(4)
                    raf.readFully(locale)
                    val valueSize = (dataSize - 16).coerceAtMost(10 * 1024 * 1024).toInt()
                    val valueBytes = ByteArray(valueSize)
                    raf.readFully(valueBytes)

                    when (tag) {
                        "\u00a9nam" -> onMetadata(String(valueBytes, StandardCharsets.UTF_8), null, null, null, null, 0L, null)
                        "\u00a9ART", "aART" -> onMetadata(null, String(valueBytes, StandardCharsets.UTF_8), null, null, null, 0L, null)
                        "\u00a9alb" -> onMetadata(null, null, String(valueBytes, StandardCharsets.UTF_8), null, null, 0L, null)
                        "\u00a9day" -> onMetadata(null, null, null, null, String(valueBytes, StandardCharsets.UTF_8).take(4).toIntOrNull(), 0L, null)
                        "trkn" -> {
                            if (valueBytes.size >= 4) {
                                val trk = ((valueBytes[2].toInt() and 0xFF) shl 8) or (valueBytes[3].toInt() and 0xFF)
                                onMetadata(null, null, null, trk, null, 0L, null)
                            }
                        }
                        "covr" -> {
                            val hash = hashString("${sourceFile.absolutePath}_cover")
                            val coverFile = File(coversDir, "$hash.jpg")
                            coverFile.writeBytes(valueBytes)
                            onMetadata(null, null, null, null, null, 0L, coverFile.toURI().toString())
                        }
                    }
                }
            }

            pos = tagEnd
        }
    }

    fun detectAudioExtension(file: File, contentType: String? = null): String {
        if (contentType != null) {
            val lower = contentType.lowercase()
            if (lower.contains("mp4") || lower.contains("m4a") || lower.contains("aac")) return "m4a"
            if (lower.contains("flac")) return "flac"
            if (lower.contains("wav")) return "wav"
            if (lower.contains("ogg")) return "ogg"
            if (lower.contains("mpeg") || lower.contains("mp3")) return "mp3"
        }
        if (file.exists() && file.length() >= 8) {
            try {
                file.inputStream().use { stream ->
                    val header = ByteArray(12)
                    val read = stream.read(header)
                    if (read >= 8) {
                        if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) {
                            return "m4a"
                        }
                        if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                            return "mp3"
                        }
                        if (header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() && header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()) {
                            return "flac"
                        }
                        if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()) {
                            return "wav"
                        }
                        if (header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() && header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()) {
                            return "ogg"
                        }
                        val b0 = header[0].toInt() and 0xFF
                        val b1 = header[1].toInt() and 0xFF
                        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                            return "mp3"
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return "mp3"
    }

    private fun indexOf(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty()) return 0
        outer@ for (i in 0..source.size - target.size) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readUInt32BE(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)
    }

    private fun readUInt64BE(buf: ByteArray, offset: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = (res shl 8) or (buf[offset + i].toLong() and 0xFF)
        }
        return res
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
