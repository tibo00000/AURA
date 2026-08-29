package com.aura.music.data.playlist

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.spotify.SpotifyAuthManager
import com.aura.music.domain.search.SearchNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class RawImportTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationS: Int = 0,
    val isrc: String? = null,
    val coverUrl: String? = null,
    val pathOrUri: String? = null
)

data class MatchedTrack(
    val raw: RawImportTrack,
    val localTrackId: String? = null,
    val matchedTitle: String? = null,
    val matchedArtist: String? = null,
    val isMatchedLocally: Boolean = false
)

data class ImportProgress(
    val processedCount: Int,
    val totalCount: Int,
    val currentTrackName: String,
    val fraction: Float
)

data class ImportReport(
    val playlistName: String,
    val sourceDescription: String,
    val totalTracks: Int,
    val matchedLocalCount: Int,
    val missingCount: Int,
    val tracks: List<MatchedTrack>,
    val isPartial: Boolean = false
)

data class ExportReport(
    val playlistName: String,
    val totalTracks: Int,
    val fullPathCount: Int,
    val metadataOnlyCount: Int,
    val shareableUri: Uri,
    val outputFile: File
)

sealed class ImportStage {
    object FetchingMetadata : ImportStage()
    data class Reconciling(val progress: ImportProgress) : ImportStage()
    data class ReadyToCreate(val report: ImportReport) : ImportStage()
    data class Error(val message: String, val requiresSpotifyAuth: Boolean = false, val sessionExpired: Boolean = false) : ImportStage()
}

class SpotifyAuthRequiredException(val sessionExpired: Boolean) : Exception("Authentification Spotify requise")

/**
 * Gestionnaire d'Import et d'Export de Playlists pour AURA.
 * 
 * - Parsing universel tolérant (M3U, M3U8, CSV dynamique, TXT).
 * - Résolution Deezer sans clé et Spotify via API officielle sécurisée.
 * - Réconciliation Zero-Jank par lots sur Dispatchers.Default avec progression continue.
 * - Export standardisé .m3u8 avec résolution des chemins réels.
 */
class PlaylistImportExportManager(
    private val context: Context,
    private val libraryRepository: LocalLibraryRepository,
    private val spotifyAuthManager: SpotifyAuthManager
) {
    companion object {
        private const val TAG = "PlaylistImportExportMgr"
    }

    /**
     * Importe une playlist depuis un lien Web (Deezer ou Spotify).
     */
    fun importFromWeb(url: String): Flow<ImportStage> = flow {
        emit(ImportStage.FetchingMetadata)
        try {
            val cleanUrl = url.trim()
            val (playlistName, rawTracks) = when {
                cleanUrl.contains("deezer", ignoreCase = true) -> fetchDeezerPlaylist(cleanUrl)
                cleanUrl.contains("spotify", ignoreCase = true) -> fetchSpotifyPlaylist(cleanUrl)
                else -> throw IllegalArgumentException("Format de lien non reconnu. Utilisez un lien Deezer ou Spotify.")
            }

            if (rawTracks.isEmpty()) {
                emit(ImportStage.Error("Aucun morceau trouvé dans la playlist distante."))
                return@flow
            }

            // Réconciliation par lots
            val matchedTracks = reconcileTracksWithProgress(rawTracks) { progress ->
                emit(ImportStage.Reconciling(progress))
            }

            val localMatchedCount = matchedTracks.count { it.isMatchedLocally }
            val report = ImportReport(
                playlistName = playlistName,
                sourceDescription = if (cleanUrl.contains("deezer", ignoreCase = true)) "Deezer" else "Spotify",
                totalTracks = matchedTracks.size,
                matchedLocalCount = localMatchedCount,
                missingCount = matchedTracks.size - localMatchedCount,
                tracks = matchedTracks
            )

            emit(ImportStage.ReadyToCreate(report))
        } catch (e: SpotifyAuthRequiredException) {
            emit(ImportStage.Error(
                message = if (e.sessionExpired) "Votre session Spotify a expiré. Veuillez reconnecter votre compte." else "Connexion à Spotify requise pour importer cette playlist.",
                requiresSpotifyAuth = true,
                sessionExpired = e.sessionExpired
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Erreur import web", e)
            emit(ImportStage.Error(e.message ?: "Erreur lors de la récupération de la playlist"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Importe une playlist depuis un fichier local (M3U, M3U8, CSV, TXT).
     */
    fun importFromFile(uri: Uri, contentResolver: ContentResolver): Flow<ImportStage> = flow {
        emit(ImportStage.FetchingMetadata)
        try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Impossible d'ouvrir le fichier sélectionné.")

            val (fileName, rawTracks) = parseFileContent(uri, inputStream)

            if (rawTracks.isEmpty()) {
                emit(ImportStage.Error("Le fichier ne contient aucun titre valide."))
                return@flow
            }

            // Réconciliation locale
            val matchedTracks = reconcileTracksWithProgress(rawTracks) { progress ->
                emit(ImportStage.Reconciling(progress))
            }

            val localMatchedCount = matchedTracks.count { it.isMatchedLocally }
            val report = ImportReport(
                playlistName = fileName,
                sourceDescription = "Fichier local",
                totalTracks = matchedTracks.size,
                matchedLocalCount = localMatchedCount,
                missingCount = matchedTracks.size - localMatchedCount,
                tracks = matchedTracks
            )

            emit(ImportStage.ReadyToCreate(report))
        } catch (e: Exception) {
            Log.e(TAG, "Erreur import fichier", e)
            emit(ImportStage.Error(e.message ?: "Erreur lors de la lecture du fichier"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Crée la playlist dans Room et associe les morceaux trouvés.
     */
    suspend fun commitImport(report: ImportReport, playlistCustomName: String? = null): String = withContext(Dispatchers.IO) {
        val finalName = playlistCustomName?.takeIf { it.isNotBlank() } ?: report.playlistName
        val playlistId = libraryRepository.createPlaylist(finalName)

        for (item in report.tracks) {
            if (item.isMatchedLocally && item.localTrackId != null) {
                try {
                    libraryRepository.addTrackToPlaylist(playlistId, item.localTrackId, context = "playlist_import")
                } catch (e: Exception) {
                    Log.w(TAG, "Erreur ajout titre ${item.localTrackId} à la playlist $playlistId", e)
                }
            }
        }

        Log.i(TAG, "Playlist importée créée avec succès ID=$playlistId (${report.matchedLocalCount}/${report.totalTracks} liés)")
        playlistId
    }

    /**
     * Exporte une playlist AURA en fichier .m3u8 standard avec résolution des chemins réels.
     */
    suspend fun exportPlaylistToM3U8(playlistId: String): ExportReport = withContext(Dispatchers.IO) {
        val playlist = libraryRepository.getPlaylistDetails(playlistId)
            ?: throw IllegalArgumentException("Playlist introuvable (ID: $playlistId)")

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = playlist.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)
        val outputFile = File(exportDir, "${safeName}_export.m3u8")

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:${playlist.name}\n\n")

        var fullPathCount = 0
        var metadataOnlyCount = 0

        for (t in playlist.tracks) {
            val durationS = (t.durationMs / 1000L).coerceAtLeast(0L)
            sb.append("#EXTINF:$durationS,${t.artistName} - ${t.title}\n")

            // Tentative de résolution du chemin physique absolu
            val resolvedPath = resolvePhysicalAudioPath(t.trackId)
            if (resolvedPath != null && File(resolvedPath).exists()) {
                sb.append("$resolvedPath\n")
                fullPathCount++
            } else {
                // Fallback propre : nom de fichier relatif
                val fallbackRelative = "${t.artistName} - ${t.title}.mp3".replace(Regex("[/\\\\:*?\"<>|]"), "_")
                sb.append("$fallbackRelative\n")
                metadataOnlyCount++
            }
        }

        outputFile.writeText(sb.toString(), StandardCharsets.UTF_8)

        val shareableUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outputFile
        )

        ExportReport(
            playlistName = playlist.name,
            totalTracks = playlist.tracks.size,
            fullPathCount = fullPathCount,
            metadataOnlyCount = metadataOnlyCount,
            shareableUri = shareableUri,
            outputFile = outputFile
        )
    }

    // =========================================================================
    // Résolution Web (Deezer & Spotify API Officielles)
    // =========================================================================

    private suspend fun fetchDeezerPlaylist(urlStr: strToKotlin): Pair<String, List<RawImportTrack>> = withContext(Dispatchers.IO) {
        var finalUrl = urlStr
        // Résolution de lien court Deezer (link.deezer.com / deezer.page.link)
        if (urlStr.contains("link.deezer.com") || urlStr.contains("deezer.page.link")) {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connect()
                finalUrl = conn.url.toString()
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Impossible de suivre la redirection Deezer", e)
            }
        }

        val playlistId = Regex("playlist/(\\d+)").find(finalUrl)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("ID de playlist Deezer introuvable dans le lien.")

        val apiEndpoint = "https://api.deezer.com/playlist/$playlistId"
        val conn = URL(apiEndpoint).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "AuraMusic/1.0")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(responseText)

        if (root.has("error")) {
            val errObj = root.getJSONObject("error")
            throw RuntimeException("Erreur Deezer : ${errObj.optString("message", "Erreur inconnue")}")
        }

        val title = root.optString("title", "Playlist Deezer")
        val tracksArray = root.optJSONObject("tracks")?.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: org.json.JSONArray()

        val rawTracks = mutableListOf<RawImportTrack>()
        for (i in 0 until tracksArray.length()) {
            val t = tracksArray.getJSONObject(i)
            rawTracks.add(
                RawImportTrack(
                    title = t.optString("title", "Sans titre"),
                    artist = t.optJSONObject("artist")?.optString("name", "Inconnu") ?: "Inconnu",
                    album = t.optJSONObject("album")?.optString("title"),
                    durationS = t.optInt("duration", 0),
                    isrc = t.optString("isrc").takeIf { it.isNotBlank() },
                    coverUrl = t.optJSONObject("album")?.optString("cover_big")
                        ?: t.optJSONObject("album")?.optString("cover_medium")
                )
            )
        }

        Pair(title, rawTracks)
    }

    private suspend fun fetchSpotifyPlaylist(urlStr: String): Pair<String, List<RawImportTrack>> = withContext(Dispatchers.IO) {
        val token = spotifyAuthManager.getValidAccessToken()
            ?: throw SpotifyAuthRequiredException(sessionExpired = spotifyAuthManager.isConnected.value)

        val playlistId = Regex("playlist/([a-zA-Z0-9]+)").find(urlStr)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("ID de playlist Spotify introuvable dans le lien.")

        val endpoint = "https://api.spotify.com/v1/playlists/$playlistId"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        if (conn.responseCode == 401 || conn.responseCode == 400) {
            spotifyAuthManager.disconnectSpotify()
            throw SpotifyAuthRequiredException(sessionExpired = true)
        } else if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw RuntimeException("Erreur Spotify (${conn.responseCode}) : $err")
        }

        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val title = root.optString("name", "Playlist Spotify")
        val itemsObj = root.optJSONObject("items") ?: root.optJSONObject("tracks")

        val rawTracks = mutableListOf<RawImportTrack>()
        var currentItemsArray = itemsObj?.optJSONArray("items")

        fun parseSpotifyItems(arr: org.json.JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val t = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue

                val artistsArr = t.optJSONArray("artists")
                val artistNames = if (artistsArr != null && artistsArr.length() > 0) {
                    val list = mutableListOf<String>()
                    for (a in 0 until artistsArr.length()) {
                        list.add(artistsArr.getJSONObject(a).optString("name"))
                    }
                    list.joinToString(", ")
                } else "Inconnu"

                val albumObj = t.optJSONObject("album")
                val imagesArr = albumObj?.optJSONArray("images")
                val cover = if (imagesArr != null && imagesArr.length() > 0) {
                    imagesArr.getJSONObject(0).optString("url")
                } else null

                rawTracks.add(
                    RawImportTrack(
                        title = t.optString("name", "Sans titre"),
                        artist = artistNames,
                        album = albumObj?.optString("name"),
                        durationS = t.optInt("duration_ms", 0) / 1000,
                        isrc = t.optJSONObject("external_ids")?.optString("isrc"),
                        coverUrl = cover
                    )
                )
            }
        }

        parseSpotifyItems(currentItemsArray)

        // Pagination complète à travers `next`
        var nextUrl = itemsObj?.optString("next", null)
        while (!nextUrl.isNullOrBlank() && nextUrl != "null") {
            val nextConn = URL(nextUrl).openConnection() as HttpURLConnection
            nextConn.setRequestProperty("Authorization", "Bearer $token")
            if (nextConn.responseCode in 200..299) {
                val nextObj = JSONObject(nextConn.inputStream.bufferedReader().use { it.readText() })
                parseSpotifyItems(nextObj.optJSONArray("items"))
                nextUrl = nextObj.optString("next", null)
            } else {
                break
            }
        }

        Pair(title, rawTracks)
    }

    // =========================================================================
    // Parsing Local Universel (Encodages BOM, M3U8, CSV, TXT)
    // =========================================================================

    private fun parseFileContent(uri: Uri, inputStream: InputStream): Pair<String, List<RawImportTrack>> {
        val rawBytes = inputStream.use { it.readBytes() }
        val (decodedText, charsetUsed) = decodeBytesWithBOMFallback(rawBytes)
        Log.d(TAG, "Fichier décodé avec encodage $charsetUsed (${decodedText.length} caractères)")

        val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "Playlist Importée"

        val lines = decodedText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return Pair(fileName, emptyList())

        // 1. Détection M3U / M3U8
        if (lines.any { it.startsWith("#EXTM3U") || it.startsWith("#EXTINF:") }) {
            return Pair(extractM3UPlaylistName(lines) ?: fileName, parseM3U(lines))
        }

        // 2. Détection CSV
        val delimiter = detectCsvDelimiter(lines)
        if (delimiter != null) {
            val csvTracks = parseCsv(lines, delimiter)
            if (csvTracks.isNotEmpty()) {
                return Pair(fileName, csvTracks)
            }
        }

        // 3. Fallback TXT (ligne par ligne)
        return Pair(fileName, parsePlainTextLines(lines))
    }

    private fun decodeBytesWithBOMFallback(bytes: ByteArray): Pair<String, Charset> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Pair(String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Pair(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Pair(String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE)
        }

        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
            val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            Pair(text, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Pair(String(bytes, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1)
        }
    }

    private fun extractM3UPlaylistName(lines: List<String>): String? {
        for (l in lines) {
            if (l.startsWith("#PLAYLIST:", ignoreCase = true)) {
                return l.substring(10).trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun parseM3U(lines: List<String>): List<RawImportTrack> {
        val result = mutableListOf<RawImportTrack>()
        var currentDuration = 0
        var currentArtist: String? = null
        var currentTitle: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTM3U") || line.startsWith("#PLAYLIST")) continue

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val payload = line.substring(8).trim()
                val commaIdx = payload.indexOf(',')
                if (commaIdx != -1) {
                    val durStr = payload.substring(0, commaIdx).trim()
                    currentDuration = durStr.toIntOrNull() ?: 0
                    val artistTitle = payload.substring(commaIdx + 1).trim()
                    if (artistTitle.contains(" - ")) {
                        val parts = artistTitle.split(" - ", limit = 2)
                        currentArtist = parts[0].trim()
                        currentTitle = parts[1].trim()
                    } else {
                        currentArtist = "Inconnu"
                        currentTitle = artistTitle
                    }
                }
            } else if (!line.startsWith("#")) {
                // Ligne de chemin de fichier / URL
                val title = currentTitle ?: extractTitleFromPath(line)
                val artist = currentArtist ?: extractArtistFromPath(line)
                result.add(
                    RawImportTrack(
                        title = title,
                        artist = artist,
                        durationS = currentDuration,
                        pathOrUri = line
                    )
                )
                currentDuration = 0
                currentArtist = null
                currentTitle = null
            }
        }
        return result
    }

    private fun extractTitleFromPath(path: String): String {
        val cleanName = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return if (cleanName.contains(" - ")) cleanName.split(" - ", limit = 2)[1].trim() else cleanName.replace('_', ' ')
    }

    private fun extractArtistFromPath(path: String): String {
        val cleanName = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return if (cleanName.contains(" - ")) cleanName.split(" - ", limit = 2)[0].trim() else "Inconnu"
    }

    private fun detectCsvDelimiter(lines: List<String>): Char? {
        val sample = lines.take(5)
        val commaCount = sample.sumOf { it.count { c -> c == ',' } }
        val semiCount = sample.sumOf { it.count { c -> c == ';' } }
        val tabCount = sample.sumOf { it.count { c -> c == '\t' } }

        return when {
            semiCount >= 4 && semiCount >= commaCount -> ';'
            commaCount >= 4 -> ','
            tabCount >= 4 -> '\t'
            else -> null
        }
    }

    private fun parseCsv(lines: List<String>, delimiter: Char): List<RawImportTrack> {
        val result = mutableListOf<RawImportTrack>()
        val firstRow = lines.first().split(delimiter).map { it.trim().lowercase().replace("\"", "") }

        var titleIdx = firstRow.indexOfFirst { it in listOf("title", "titre", "track", "track_name", "song") }
        var artistIdx = firstRow.indexOfFirst { it in listOf("artist", "artiste", "artist_name") }
        var albumIdx = firstRow.indexOfFirst { it in listOf("album", "album_name") }

        val hasHeader = titleIdx != -1 || artistIdx != -1
        val startIndex = if (hasHeader) 1 else 0

        if (!hasHeader) {
            titleIdx = 0
            artistIdx = 1
            albumIdx = 2
        }

        for (i in startIndex until lines.size) {
            val parts = lines[i].split(delimiter).map { it.trim().removeSurrounding("\"") }
            if (parts.isEmpty() || parts.all { it.isBlank() }) continue

            val title = parts.getOrNull(titleIdx)?.takeIf { it.isNotBlank() } ?: continue
            val artist = if (artistIdx != -1) parts.getOrNull(artistIdx)?.takeIf { it.isNotBlank() } ?: "Inconnu" else "Inconnu"
            val album = if (albumIdx != -1) parts.getOrNull(albumIdx)?.takeIf { it.isNotBlank() } else null

            result.add(RawImportTrack(title = title, artist = artist, album = album))
        }
        return result
    }

    private fun parsePlainTextLines(lines: List<String>): List<RawImportTrack> {
        val result = mutableListOf<RawImportTrack>()
        for (l in lines) {
            if (l.startsWith("#") || l.isBlank()) continue
            if (l.contains(" - ")) {
                val parts = l.split(" - ", limit = 2)
                result.add(RawImportTrack(artist = parts[0].trim(), title = parts[1].trim()))
            } else {
                result.add(RawImportTrack(artist = "Inconnu", title = l))
            }
        }
        return result
    }

    // =========================================================================
    // Réconciliation & Matching Zero-Jank par Lots
    // =========================================================================

    private suspend fun reconcileTracksWithProgress(
        rawTracks: List<RawImportTrack>,
        onProgress: suspend (ImportProgress) -> Unit
    ): List<MatchedTrack> = withContext(Dispatchers.Default) {
        val localTracks = libraryRepository.getAllTracks()
        val total = rawTracks.size
        val matchedList = mutableListOf<MatchedTrack>()

        // Pré-normalisation des titres locaux
        val normalizedLocal = localTracks.map { local ->
            val normTitle = SearchNormalizer.normalize(local.title)
            val normArtist = SearchNormalizer.normalize(local.artistName)
            Triple(local, normTitle, normArtist)
        }

        val batchSize = 25
        for (i in rawTracks.indices) {
            val raw = rawTracks[i]
            val normRawTitle = SearchNormalizer.normalize(raw.title)
            val normRawArtist = SearchNormalizer.normalize(raw.artist)

            // Matching par priorité : 1. Exact normalisé (Titre + Artiste), 2. Titre exact si artiste match partiel, 3. Titre contenu
            var match = normalizedLocal.firstOrNull { (_, localT, localA) ->
                normRawTitle == localT && (normRawArtist == localA || normRawArtist.contains(localA) || localA.contains(normRawArtist))
            }

            if (match == null) {
                match = normalizedLocal.firstOrNull { (_, localT, _) ->
                    normRawTitle.length >= 4 && normRawTitle == localT
                }
            }

            if (match != null) {
                matchedList.add(
                    MatchedTrack(
                        raw = raw,
                        localTrackId = match.first.trackId,
                        matchedTitle = match.first.title,
                        matchedArtist = match.first.artistName,
                        isMatchedLocally = true
                    )
                )
            } else {
                matchedList.add(MatchedTrack(raw = raw, isMatchedLocally = false))
            }

            if (i % batchSize == 0 || i == total - 1) {
                val fraction = (i + 1).toFloat() / total
                onProgress(
                    ImportProgress(
                        processedCount = i + 1,
                        totalCount = total,
                        currentTrackName = "${raw.artist} - ${raw.title}",
                        fraction = fraction
                    )
                )
            }
        }

        matchedList
    }

    private fun resolvePhysicalAudioPath(trackId: String): String? {
        if (!trackId.startsWith("track:local:")) return null
        val mediaStoreId = trackId.removePrefix("track:local:").toLongOrNull() ?: return null

        try {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Audio.Media._ID} = ?"
            val selectionArgs = arrayOf(mediaStoreId.toString())

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    if (dataIdx != -1) {
                        val path = cursor.getString(dataIdx)
                        if (!path.isNullOrBlank()) return path
                    }
                    val relIdx = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    if (relIdx != -1 && nameIdx != -1) {
                        val rel = cursor.getString(relIdx)
                        val name = cursor.getString(nameIdx)
                        if (!rel.isNullOrBlank() && !name.isNullOrBlank()) {
                            return File(Environment.getExternalStorageDirectory(), "$rel/$name").absolutePath
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de résoudre le chemin MediaStore pour $trackId", e)
        }
        return null
    }
}

typealias strToKotlin = String
