package com.aura.music.core

import android.net.Uri
import java.net.URI

/** One configured HTTPS origin, one private streaming route, no caller-selected URL. */
internal class PrivateStreamPolicy(base: URI) {
    private val origin = base
    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
    private val validOrigin = base.scheme.equals("https", true) && base.host != null &&
        base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null &&
        base.rawPath.orEmpty() in setOf("", "/") && port(base) in 1..65535

    fun trackId(uri: URI): String? {
        if (!validOrigin || !uri.scheme.equals("https", true) ||
            !uri.host.orEmpty().equals(origin.host, true) || port(uri) != port(origin) ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath ?: return null
        val prefix = "/me/sync/files/"
        if (!path.startsWith(prefix)) return null
        val encoded = path.substring(prefix.length)
        if (encoded.isEmpty() || '/' in encoded) return null
        val id = Uri.decode(encoded)
        return id.takeIf(::validTrackId)
    }

    fun allows(uri: URI) = trackId(uri) != null

    fun uriFor(id: String): Uri {
        require(validOrigin && validTrackId(id)) { "Source audio non autorisée" }
        return Uri.parse(origin.toASCIIString().trimEnd('/') + "/me/sync/files/" + Uri.encode(id))
    }

    companion object {
        fun validTrackId(id: String): Boolean = id.isNotBlank() && id.length <= 512 &&
            id != "." && id != ".." && id.none { it <= ' ' || it == '\u007f' || it in "/\\?#%" }
    }
}
