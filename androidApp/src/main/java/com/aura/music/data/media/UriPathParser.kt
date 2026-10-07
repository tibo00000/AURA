package com.aura.music.data.media

import java.io.ByteArrayOutputStream
import java.io.File

object UriPathParser {

    /**
     * Identifies whether a given URI is an Android Scoped Storage / MediaStore content URI.
     */
    fun isContentUri(uri: String): Boolean = uri.startsWith("content://")

    /**
     * Decodes ONLY percent-encoded sequences (%XX) without converting '+' to spaces.
     * Unlike [java.net.URLDecoder.decode], this preserves literal '+' characters in filenames.
     */
    fun decodePercent(input: String): String {
        if (!input.contains('%')) return input
        val bytes = ByteArrayOutputStream(input.length)
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            if (c == '%' && i + 2 < n) {
                val hex = input.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    bytes.write(byteVal)
                    i += 3
                    continue
                }
            }
            // Write individual UTF-8 bytes for literal characters
            val charBytes = c.toString().toByteArray(Charsets.UTF_8)
            bytes.write(charBytes, 0, charBytes.size)
            i++
        }
        return bytes.toString("UTF-8")
    }

    /**
     * Resolves a local File object from a URI string without any Android SDK dependency (pure JVM).
     * Returns null if uriString is null/blank or a content:// URI.
     */
    fun parseLocalFile(uriString: String?): File? {
        if (uriString.isNullOrBlank() || isContentUri(uriString)) return null

        var rawPath = when {
            uriString.startsWith("file://") -> uriString.removePrefix("file://")
            uriString.startsWith("file:") -> uriString.removePrefix("file:")
            else -> uriString
        }

        // On Windows JVM test runners, file:/C:/path can result in /C:/path
        if (rawPath.length >= 3 && rawPath[0] == '/' && rawPath[1].isLetter() && rawPath[2] == ':') {
            rawPath = rawPath.substring(1)
        }

        val directFile = File(rawPath)
        if (directFile.exists()) return directFile

        if (rawPath.contains('%')) {
            try {
                var decodedPath = decodePercent(rawPath)
                if (decodedPath.length >= 3 && decodedPath[0] == '/' && decodedPath[1].isLetter() && decodedPath[2] == ':') {
                    decodedPath = decodedPath.substring(1)
                }
                val decodedFile = File(decodedPath)
                if (decodedFile.exists()) return decodedFile
                return decodedFile
            } catch (_: Exception) {
                // Ignore and fall back to directFile
            }
        }

        return directFile
    }
}
