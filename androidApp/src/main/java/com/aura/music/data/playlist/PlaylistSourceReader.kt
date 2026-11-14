package com.aura.music.data.playlist

import com.aura.music.data.network.PlaylistTrackMetadata
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal class PlaylistSourceReadException(message: String) : IOException(message)

internal class PlaylistSourceReader(
    private val spotifyToken: suspend () -> String?,
    private val request: (URI, String?) -> JSONObject = ::readSourceJson,
) {
    suspend fun read(reference: PlaylistSourceReference): List<PlaylistTrackMetadata> = withContext(Dispatchers.IO) {
        if (!reference.isCanonical()) throw PlaylistSourceReadException("La référence de la playlist est invalide.")
        val spotify = reference.provider == "spotify"
        val id = reference.externalId
        val base = URI(if (spotify) "https://api.spotify.com/v1/playlists/$id" else "https://api.deezer.com/playlist/$id")
        val token = if (spotify) spotifyToken() ?: throw PlaylistSourceReadException("Reconnecte Spotify pour actualiser cette playlist.") else null
        val items = if (spotify) {
            val first = request(URI("$base?fields=snapshot_id"), token).requiredText("snapshot_id")
            val page = URI("$base/items?limit=50")
            val entries = readPages(page, page, "items", token)
            val last = request(URI("$base?fields=snapshot_id"), token).requiredText("snapshot_id")
            if (first != last) throw PlaylistSourceReadException("La playlist Spotify a changé pendant la lecture. Réessaie.")
            entries
        } else {
            val page = URI("$base/tracks?limit=100")
            val first = readPages(page, page, "data", null)
            val second = readPages(page, page, "data", null)
            if (first.map { it.requiredText("id") } != second.map { it.requiredText("id") }) {
                throw PlaylistSourceReadException("La playlist Deezer a changé pendant la lecture. Réessaie.")
            }
            second
        }
        items.mapNotNull { entry ->
            val track = if (spotify) {
                if (entry.optBoolean("is_local")) return@mapNotNull null
                val item = entry.optJSONObject("item") ?: entry.optJSONObject("track")
                if (item == null && (entry.has("item") || entry.has("track"))) return@mapNotNull null
                val value = item ?: throw PlaylistSourceReadException("Un élément Spotify est incomplet.")
                if (value.optString("type") == "episode" || value.optBoolean("is_local")) return@mapNotNull null
                value
            } else entry
            val album = track.optJSONObject("album")
            val artist = if (spotify) {
                val artists = track.getJSONArray("artists")
                artists.objects().joinToString(", ") { it.requiredText("name") }
            } else track.getJSONObject("artist").requiredText("name")
            if (artist.isBlank()) throw PlaylistSourceReadException("Un titre n’a pas de nom d’artiste.")
            val cover = if (spotify) album?.optJSONArray("images")?.optJSONObject(0)?.optionalText("url")
            else album?.optionalText("cover_big") ?: album?.optionalText("cover_medium")
            PlaylistTrackMetadata(
                provider = reference.provider,
                providerId = track.requiredText("id"),
                title = track.requiredText(if (spotify) "name" else "title"),
                artist = artist,
                album = album?.optionalText(if (spotify) "name" else "title"),
                durationMs = if (spotify) track.optLong("duration_ms", -1).takeIf { it >= 0 }
                else track.optLong("duration", -1).takeIf { it in 0..999999L }?.times(1000),
                coverUri = cover?.takeIf(allowedCover::matches),
            )
        }.distinctBy { it.providerId }
    }

    private suspend fun readPages(start: URI, expected: URI, field: String, token: String?): List<JSONObject> {
        var next: URI? = start
        var expectedTotal: Int? = null
        val visited = mutableSetOf<URI>()
        val entries = mutableListOf<JSONObject>()
        while (next != null) {
            coroutineContext.ensureActive()
            val uri = next
            if (uri.scheme != "https" || uri.host != expected.host || uri.path != expected.path ||
                uri.port != -1 || uri.rawUserInfo != null || !visited.add(uri) || visited.size > 250
            ) throw PlaylistSourceReadException("La pagination de la source est incohérente.")
            val page = request(uri, token)
            val total = page.getInt("total")
            if (total !in 0..10000 || (expectedTotal != null && total != expectedTotal)) {
                throw PlaylistSourceReadException("La liste des titres a changé pendant la lecture.")
            }
            expectedTotal = total
            entries += page.getJSONArray(field).objects()
            if (entries.size > total) throw PlaylistSourceReadException("La pagination de la source est incohérente.")
            next = page.optionalText("next")?.let { link ->
                val target = URI(link)
                // Deezer may emit HTTP pagination links. Keep the same host/path
                // checks on the next iteration and always send the request over TLS.
                if (token == null && target.scheme == "http" && target.host == expected.host && target.port == -1) {
                    URI("https://" + target.rawAuthority + target.rawPath +
                        (target.rawQuery?.let { "?$it" } ?: ""))
                } else target
            }
        }
        if (entries.size != expectedTotal) throw PlaylistSourceReadException("La liste des titres est incomplète. Réessaie.")
        return entries
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length(), ::getJSONObject)
    private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.requiredText(key: String): String = optionalText(key)
        ?: throw PlaylistSourceReadException("La source n’a pas fourni toutes les informations du titre.")

    companion object {
        private val allowedCover = Regex("https://(?:i[.]scdn[.]co|cdn-images[.]dzcdn[.]net|e-cdns-images[.]dzcdn[.]net)/[A-Za-z0-9_./?=%&@,+-]+")
    }
}

private fun readSourceJson(uri: URI, token: String?): JSONObject {
    val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 10_000
        readTimeout = 15_000
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    try {
        when (connection.responseCode) {
            401 -> throw PlaylistSourceReadException("Reconnecte le fournisseur pour actualiser cette playlist.")
            403, 404 -> throw PlaylistSourceReadException("La playlist source est inaccessible avec ce compte.")
            429 -> throw PlaylistSourceReadException("Le fournisseur limite les requêtes. Réessaie plus tard.")
            in 200..299 -> Unit
            else -> throw PlaylistSourceReadException("Le fournisseur est temporairement indisponible.")
        }
        val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        if (data.has("error")) throw PlaylistSourceReadException("La playlist source est inaccessible.")
        return data
    } finally {
        connection.disconnect()
    }
}
