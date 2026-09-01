package com.aura.music.data.playlist

import com.aura.music.data.local.TrackListRow
import com.aura.music.domain.search.SearchNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExportedPlaylistJson(
    val version: Int = 1,
    val name: String,
    val tracks: List<ExportedTrackJson>
)

@Serializable
data class ExportedTrackJson(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null
)

data class ParsedPlaylistItem(
    val rawTitle: String,
    val rawArtist: String?,
    val durationSeconds: Int?
)

data class ImportAnalysisResult(
    val playlistName: String,
    val totalEntries: Int,
    val matchedTracks: List<TrackListRow>,
    val unmatchedEntries: List<ParsedPlaylistItem>
)

/**
 * Moteur pur Kotlin Multiplatform pour le parsing, l'export et la réconciliation
 * de fichiers de playlists (.m3u, .m3u8, .json).
 */
object PlaylistImportExportEngine {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // =======================================================================
    // PARSING M3U / M3U8
    // =======================================================================

    fun parseM3u(content: String, defaultName: String = "Playlist importée"): List<ParsedPlaylistItem> {
        val cleanContent = content.removePrefix("\uFEFF") // Supprime UTF-8 BOM
        val lines = cleanContent.lines().map { it.trim() }.filter { it.isNotBlank() }

        val items = mutableListOf<ParsedPlaylistItem>()
        var currentDuration: Int? = null
        var currentTitle: String? = null
        var currentArtist: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val info = line.substring(8).trim()
                val commaIndex = info.indexOf(',')
                if (commaIndex != -1) {
                    val durationStr = info.substring(0, commaIndex).trim()
                    currentDuration = durationStr.toIntOrNull()
                    val titleArtistPart = info.substring(commaIndex + 1).trim()
                    if (titleArtistPart.contains(" - ")) {
                        val parts = titleArtistPart.split(" - ", limit = 2)
                        currentArtist = parts[0].trim()
                        currentTitle = parts[1].trim()
                    } else {
                        currentTitle = titleArtistPart
                        currentArtist = null
                    }
                }
            } else if (!line.startsWith("#")) {
                // Ligne de chemin ou fichier
                val title = currentTitle ?: extractTitleFromPath(line)
                items.add(
                    ParsedPlaylistItem(
                        rawTitle = title,
                        rawArtist = currentArtist,
                        durationSeconds = currentDuration
                    )
                )
                currentDuration = null
                currentTitle = null
                currentArtist = null
            }
        }
        return items
    }

    private fun extractTitleFromPath(path: String): String {
        val clean = path.replace('\\', '/')
        val filename = clean.substringAfterLast('/')
        return filename.substringBeforeLast('.')
    }

    // =======================================================================
    // PARSING JSON
    // =======================================================================

    fun parseJson(content: String): ExportedPlaylistJson? {
        return try {
            json.decodeFromString<ExportedPlaylistJson>(content)
        } catch (e: Exception) {
            null
        }
    }

    // =======================================================================
    // RÉCONCILIATION AVEC LA BASE DE DONNÉES LOCALE (FUZZY MATCHING)
    // =======================================================================

    fun reconcile(
        playlistName: String,
        parsedItems: List<ParsedPlaylistItem>,
        allLocalTracks: List<TrackListRow>
    ): ImportAnalysisResult {
        val matchedTracks = mutableListOf<TrackListRow>()
        val unmatched = mutableListOf<ParsedPlaylistItem>()

        for (item in parsedItems) {
            val normalizedItemTitle = SearchNormalizer.normalize(item.rawTitle)
            val normalizedItemArtist = item.rawArtist?.let { SearchNormalizer.normalize(it) }

            val match = allLocalTracks.firstOrNull { local ->
                val localTitleNorm = SearchNormalizer.normalize(local.title)
                val localArtistNorm = SearchNormalizer.normalize(local.artistName)

                val titleMatches = localTitleNorm == normalizedItemTitle ||
                        localTitleNorm.contains(normalizedItemTitle) ||
                        normalizedItemTitle.contains(localTitleNorm)

                if (normalizedItemArtist != null) {
                    val artistMatches = localArtistNorm == normalizedItemArtist ||
                            localArtistNorm.contains(normalizedItemArtist) ||
                            normalizedItemArtist.contains(localArtistNorm)
                    titleMatches && artistMatches
                } else {
                    titleMatches
                }
            }

            if (match != null) {
                matchedTracks.add(match)
            } else {
                unmatched.add(item)
            }
        }

        return ImportAnalysisResult(
            playlistName = playlistName,
            totalEntries = parsedItems.size,
            matchedTracks = matchedTracks,
            unmatchedEntries = unmatched
        )
    }

    // =======================================================================
    // GÉNÉRATION D'EXPORT (.m3u8 & .json)
    // =======================================================================

    fun exportToM3u8(playlistName: String, tracks: List<TrackListRow>): String {
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#PLAYLIST:$playlistName")
        sb.appendLine()

        for (track in tracks) {
            val durationSec = (track.durationMs ?: 0L) / 1000L
            sb.appendLine("#EXTINF:$durationSec,${track.artistName} - ${track.title}")
            sb.appendLine(track.contentUri ?: "${track.title}.mp3")
        }

        return sb.toString()
    }

    fun exportToJson(playlistName: String, tracks: List<TrackListRow>): String {
        val exportData = ExportedPlaylistJson(
            version = 1,
            name = playlistName,
            tracks = tracks.map {
                ExportedTrackJson(
                    title = it.title,
                    artist = it.artistName,
                    album = it.albumTitle,
                    durationMs = it.durationMs
                )
            }
        )
        return json.encodeToString(ExportedPlaylistJson.serializer(), exportData)
    }
}
