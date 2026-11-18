package com.aura.music.data.playlist

import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.PlaylistTrackMetadata
import com.aura.music.domain.search.SearchNormalizer
import java.util.Base64

/** Catalogue proof only. Metadata similarity is reserved for explicit suggestions. */
internal data class PlaylistRecordingIdentity(val provider: String, val id: String) {
    val canonicalId: String get() = "trk_" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("v1:track:$provider:$id".toByteArray(Charsets.UTF_8))

    fun aliases(): Set<String> = setOf(canonicalId, "$provider:$id", "track:$provider:$id", "trk_${provider}_$id") +
        if (provider == "deezer") setOf(id) else emptySet()

    companion object {
        private val spotifyId = Regex("[A-Za-z0-9]{22}")
        private val deezerId = Regex("[1-9][0-9]{0,19}")
        private val prefixes = listOf("spotify:" to "spotify", "track:spotify:" to "spotify", "trk_spotify_" to "spotify",
            "deezer:" to "deezer", "track:deezer:" to "deezer", "trk_deezer_" to "deezer")
        fun from(provider: String?, id: String?): PlaylistRecordingIdentity? = when {
            provider == "spotify" && id?.matches(spotifyId) == true -> PlaylistRecordingIdentity(provider, id)
            provider == "deezer" && id?.matches(deezerId) == true -> PlaylistRecordingIdentity(provider, id)
            else -> null
        }

        fun parse(trackId: String): PlaylistRecordingIdentity? {
            for ((prefix, provider) in prefixes) {
                if (trackId.startsWith(prefix)) return from(provider, trackId.removePrefix(prefix))
            }
            val decoded = if (trackId.startsWith("trk_djE6dHJhY2s6")) runCatching {
                String(Base64.getUrlDecoder().decode(trackId.removePrefix("trk_")), Charsets.UTF_8)
            }.getOrNull() else null
            if (decoded?.startsWith("v1:track:") == true) {
                val parts = decoded.removePrefix("v1:track:").split(':')
                return if (parts.size == 2) from(parts[0], parts[1]) else null
            }
            return if (trackId.firstOrNull()?.isDigit() == true) from("deezer", trackId) else null
        }
    }
}

internal fun PlaylistTrackMetadata.recordingIdentity() = PlaylistRecordingIdentity.from(provider, providerId)

internal fun metadataMatchKey(title: String, artist: String): Pair<String, String>? {
    val normalizedTitle = SearchNormalizer.normalize(title)
    val normalizedArtist = SearchNormalizer.normalize(artist)
    return if (normalizedTitle.isBlank() || normalizedArtist.isBlank()) null else normalizedTitle to normalizedArtist
}

/** Batch suggestions require a unique source and a unique destination, never an automatic merge. */
internal fun suggestedPlaylistLinks(
    uncertain: List<PlaylistTrackMetadata>, rows: List<PlaylistTrackRow>,
): Map<String, String> {
    val candidates = rows.groupBy { metadataMatchKey(it.title, it.artistName) }
    val sources = uncertain.groupBy { metadataMatchKey(it.title, it.artist) }
    return uncertain.mapNotNull { track ->
        val key = metadataMatchKey(track.title, track.artist) ?: return@mapNotNull null
        if (sources[key]?.size != 1) return@mapNotNull null
        val row = candidates[key]?.singleOrNull() ?: return@mapNotNull null
        track.trackId?.let { it to row.playlistItemId }
    }.toMap()
}

internal fun suggestedLibraryTracks(
    uncertain: List<PlaylistTrackMetadata>, library: List<TrackListRow>, rows: List<PlaylistTrackRow>,
): Map<String, TrackListRow> {
    val existing = rows.mapTo(mutableSetOf()) { metadataMatchKey(it.title, it.artistName) }
    val candidates = library.distinctBy { it.id }.groupBy { metadataMatchKey(it.title, it.artistName) }
    val sources = uncertain.groupBy { metadataMatchKey(it.title, it.artist) }
    return uncertain.mapNotNull { track ->
        val key = metadataMatchKey(track.title, track.artist) ?: return@mapNotNull null
        if (key in existing || sources[key]?.size != 1) return@mapNotNull null
        val row = candidates[key]?.singleOrNull() ?: return@mapNotNull null
        track.trackId?.let { it to row }
    }.toMap()
}
