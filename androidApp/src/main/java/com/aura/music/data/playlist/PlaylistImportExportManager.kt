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
import kotlinx.coroutines.flow.channelFlow
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

data class SpotifyPlaylistSummary(
    val id: String,
    val name: String,
    val trackCount: Int,
    val coverUrl: String?,
    val isLikedSongs: Boolean = false
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
 * - Affichage et sélection directe des playlists du compte Spotify connecté.
 * - Parsing universel tolérant (M3U, M3U8, CSV dynamique, TXT).
 * - Résolution Deezer sans clé et Spotify via API officielle sécurisée.
 * - Réconciliation Zero-Jank par lots sur Dispatchers.Default avec progression continue sans violation de Flow context.
 * - Export standardisé .m3u8 avec résolution des chemins réels.
 */
class PlaylistImportExportManager(
    private val context: Context,
    private val libraryRepository: LocalLibraryRepository,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val downloadRepository: com.aura.music.data.repository.DownloadRepository
) {
    companion object {
        private const val TAG = "PlaylistImportExportMgr"
    }

    /**
     * Récupère la liste des playlists du compte Spotify connecté (avec "Titres Likés" en tête).
     */
    suspend fun getUserSpotifyPlaylists(): List<SpotifyPlaylistSummary> = withContext(Dispatchers.IO) {
        val token = spotifyAuthManager.getValidAccessToken() ?: return@withContext emptyList()
        val result = mutableListOf<SpotifyPlaylistSummary>()

        try {
            // 1. Récupération des Titres Likés (Compteur)
            val likedConn = URL("https://api.spotify.com/v1/me/tracks?limit=1").openConnection() as HttpURLConnection
            likedConn.setRequestProperty("Authorization", "Bearer $token")
            likedConn.connectTimeout = 5_000
            likedConn.readTimeout = 5_000
            if (likedConn.responseCode in 200..299) {
                val likedJson = JSONObject(likedConn.inputStream.bufferedReader().use { it.readText() })
                val totalLiked = likedJson.optInt("total", 0)
                if (totalLiked > 0) {
                    result.add(
                        SpotifyPlaylistSummary(
                            id = "spotify:liked_songs",
                            name = "Titres Likés (Spotify)",
                            trackCount = totalLiked,
                            coverUrl = null,
                            isLikedSongs = true
                        )
                    )
                }
            }

            // 2. Récupération des Playlists de l'utilisateur
            val plConn = URL("https://api.spotify.com/v1/me/playlists?limit=50").openConnection() as HttpURLConnection
            plConn.setRequestProperty("Authorization", "Bearer $token")
            plConn.connectTimeout = 8_000
            plConn.readTimeout = 8_000
            if (plConn.responseCode in 200..299) {
                val plJson = JSONObject(plConn.inputStream.bufferedReader().use { it.readText() })
                val items = plJson.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val name = item.optString("name", "Sans titre")

                        // Compatible anciens schémas ("tracks") et nouveaux schémas Spotify ("items")
                        val totalTracks = item.optJSONObject("items")?.optInt("total", 0)
                            ?: item.optJSONObject("tracks")?.optInt("total", 0)
                            ?: item.optInt("total", 0)

                        val images = item.optJSONArray("images")
                        val coverUrl = if (images != null && images.length() > 0) {
                            images.optJSONObject(0)?.optString("url")
                        } else null

                        if (id.isNotBlank()) {
                            result.add(
                                SpotifyPlaylistSummary(
                                    id = id,
                                    name = name,
                                    trackCount = totalTracks,
                                    coverUrl = coverUrl,
                                    isLikedSongs = false
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur récupération playlists utilisateur Spotify", e)
        }

        result
    }

    /**
     * Importe directement une playlist Spotify par son ID ou depuis les Titres Likés.
     */
    fun importFromSpotifySelection(summary: SpotifyPlaylistSummary): Flow<ImportStage> = channelFlow {
        send(ImportStage.FetchingMetadata)
        try {
            val (playlistName, rawTracks) = if (summary.isLikedSongs) {
                fetchSpotifyLikedSongs()
            } else {
                fetchSpotifyPlaylistById(summary.id)
            }

            if (rawTracks.isEmpty()) {
                send(ImportStage.Error("Aucun morceau trouvé dans la sélection Spotify."))
                return@channelFlow
            }

            // Réconciliation locale
            val matchedTracks = reconcileTracksWithProgress(rawTracks) { progress ->
                send(ImportStage.Reconciling(progress))
            }

            val localMatchedCount = matchedTracks.count { it.isMatchedLocally }
            val report = ImportReport(
                playlistName = playlistName,
                sourceDescription = "Spotify",
                totalTracks = matchedTracks.size,
                matchedLocalCount = localMatchedCount,
                missingCount = matchedTracks.size - localMatchedCount,
                tracks = matchedTracks
            )

            send(ImportStage.ReadyToCreate(report))
        } catch (e: SpotifyAuthRequiredException) {
            send(ImportStage.Error(
                message = if (e.sessionExpired) "Votre session Spotify a expiré. Veuillez reconnecter votre compte." else "Connexion à Spotify requise.",
                requiresSpotifyAuth = true,
                sessionExpired = e.sessionExpired
            ))
        } catch (t: Throwable) {
            Log.e(TAG, "Erreur import Spotify direct", t)
            send(ImportStage.Error(t.message ?: "Erreur lors de la récupération de la playlist Spotify"))
        }
    }

    /**
     * Importe une playlist depuis un lien Web (Deezer ou Spotify).
     */
    fun importFromWeb(url: String): Flow<ImportStage> = channelFlow {
        send(ImportStage.FetchingMetadata)
        try {
            val cleanUrl = url.trim()
            val (playlistName, rawTracks) = when {
                cleanUrl.contains("deezer", ignoreCase = true) -> fetchDeezerPlaylist(cleanUrl)
                cleanUrl.contains("spotify", ignoreCase = true) -> fetchSpotifyPlaylist(cleanUrl)
                else -> throw IllegalArgumentException("Format de lien non reconnu. Utilisez un lien Deezer ou Spotify.")
            }

            if (rawTracks.isEmpty()) {
                send(ImportStage.Error("Aucun morceau trouvé dans la playlist distante."))
                return@channelFlow
            }

            // Réconciliation par lots
            val matchedTracks = reconcileTracksWithProgress(rawTracks) { progress ->
                send(ImportStage.Reconciling(progress))
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

            send(ImportStage.ReadyToCreate(report))
        } catch (e: SpotifyAuthRequiredException) {
            send(ImportStage.Error(
                message = if (e.sessionExpired) "Votre session Spotify a expiré. Veuillez reconnecter votre compte." else "Connexion à Spotify requise pour importer cette playlist.",
                requiresSpotifyAuth = true,
                sessionExpired = e.sessionExpired
            ))
        } catch (t: Throwable) {
            Log.e(TAG, "Erreur import web", t)
            send(ImportStage.Error(t.message ?: "Erreur lors de la récupération de la playlist"))
        }
    }

    /**
     * Importe une playlist depuis un fichier local (M3U, M3U8, CSV, TXT).
     */
    fun importFromFile(uri: Uri, contentResolver: ContentResolver): Flow<ImportStage> = channelFlow {
        send(ImportStage.FetchingMetadata)
        try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Impossible d'ouvrir le fichier sélectionné.")

            val (fileName, rawTracks) = parseFileContent(uri, inputStream)

            if (rawTracks.isEmpty()) {
                send(ImportStage.Error("Le fichier ne contient aucun titre valide."))
                return@channelFlow
            }

            // Réconciliation locale
            val matchedTracks = reconcileTracksWithProgress(rawTracks) { progress ->
                send(ImportStage.Reconciling(progress))
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

            send(ImportStage.ReadyToCreate(report))
        } catch (t: Throwable) {
            Log.e(TAG, "Erreur import fichier", t)
            send(ImportStage.Error(t.message ?: "Erreur lors de la lecture du fichier"))
        }
    }

    /**
     * Crée la playlist dans Room et associe les morceaux trouvés (locaux et Cloud sans téléchargement disque).
     */
    suspend fun commitImport(
        report: ImportReport,
        playlistCustomName: String? = null,
        onProgress: ((current: Int, total: Int, trackName: String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val finalName = playlistCustomName?.takeIf { it.isNotBlank() } ?: report.playlistName
        val playlistId = libraryRepository.createPlaylist(finalName)
        val total = report.tracks.size

        for ((index, item) in report.tracks.withIndex()) {
            try {
                val trackId = if (item.isMatchedLocally && item.localTrackId != null) {
                    item.localTrackId
                } else {
                    // Enregistrement Cloud placeholder (disponible en streaming sans téléchargement physique sur le téléphone)
                    val cloudTrackId = libraryRepository.upsertCloudTrackPlaceholder(
                        title = item.raw.title,
                        artistName = item.raw.artist,
                        albumTitle = item.raw.album,
                        durationMs = item.raw.durationS.toLong() * 1000L,
                        coverUri = item.raw.coverUrl
                    )
                    // Déclenchement automatique du téléchargement serveur Cloud en arrière-plan
                    try {
                        downloadRepository.triggerDownload(
                            trackId = cloudTrackId,
                            title = item.raw.title,
                            artistName = item.raw.artist,
                            albumTitle = item.raw.album,
                            coverUri = item.raw.coverUrl,
                            userToken = com.aura.music.core.AuthSessionManager.getInstance(context).getBearerHeader()
                        ).collect { }
                    } catch (e: Exception) {
                        Log.w(TAG, "Erreur déclenchement téléchargement cloud pour ${item.raw.title}", e)
                    }
                    cloudTrackId
                }
                libraryRepository.addTrackToPlaylist(playlistId, trackId, contextType = "playlist_import")
                onProgress?.invoke(index + 1, total, "${item.raw.artist} - ${item.raw.title}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Erreur ajout titre ${item.raw.title} à la playlist $playlistId", e)
            }
        }

        // Lancement immédiat de la boucle de rafraîchissement d'état des téléchargements
        downloadRepository.ensurePollingStarted(com.aura.music.core.AuthSessionManager.getInstance(context).getBearerHeader())

        Log.i(TAG, "Playlist importée créée avec succès ID=$playlistId (${report.totalTracks} titres ajoutés dont ${report.matchedLocalCount} locaux)")
        playlistId
    }

    /**
     * Exporte une playlist AURA en fichier .m3u8 standard avec résolution des chemins réels.
     */
    suspend fun exportPlaylistToM3U8(playlistId: String): ExportReport = withContext(Dispatchers.IO) {
        val playlist = libraryRepository.getPlaylistDetail(playlistId)
            ?: throw IllegalArgumentException("Playlist introuvable (ID: $playlistId)")

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = playlist.summary.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)
        val outputFile = File(exportDir, "${safeName}_export.m3u8")

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:${playlist.summary.name}\n\n")

        var fullPathCount = 0
        var metadataOnlyCount = 0

        for (t in playlist.tracks) {
            val durationS = ((t.durationMs ?: 0L) / 1000L).coerceAtLeast(0L)
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
            playlistName = playlist.summary.name,
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

    private suspend fun fetchDeezerPlaylist(urlStr: String): Pair<String, List<RawImportTrack>> = withContext(Dispatchers.IO) {
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

        val playlistId = Regex("""playlist/(\d+)""").find(finalUrl)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("ID de playlist Deezer introuvable dans le lien.")

        val apiEndpoint = "https://api.deezer.com/playlist/$playlistId"
        val conn = URL(apiEndpoint).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "AuraMusic/1.0")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(responseText)

        if (root.has("error")) {
            val errObj = root.optJSONObject("error")
            throw RuntimeException("Erreur Deezer : ${errObj?.optString("message", "Erreur inconnue")}")
        }

        val title = root.optString("title", "Playlist Deezer")
        val tracksArray = root.optJSONObject("tracks")?.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: org.json.JSONArray()

        val rawTracks = mutableListOf<RawImportTrack>()
        for (i in 0 until tracksArray.length()) {
            val t = tracksArray.optJSONObject(i) ?: continue
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
        val playlistId = Regex("""(?:playlist[/:]|open\.spotify\.com/(?:intl-[a-z]+/)?playlist/)([a-zA-Z0-9]+)""").find(urlStr)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("ID de playlist Spotify introuvable dans le lien.")

        fetchSpotifyPlaylistById(playlistId)
    }

    private suspend fun fetchSpotifyPlaylistById(playlistId: String): Pair<String, List<RawImportTrack>> = withContext(Dispatchers.IO) {
        val token = spotifyAuthManager.getValidAccessToken()
            ?: throw SpotifyAuthRequiredException(sessionExpired = spotifyAuthManager.isConnected.value)

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
        var currentItemsArray = itemsObj?.optJSONArray("items") ?: root.optJSONArray("items")

        fun parseSpotifyItems(arr: org.json.JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val t = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue

                val artistsArr = t.optJSONArray("artists")
                val artistNames = if (artistsArr != null && artistsArr.length() > 0) {
                    val list = mutableListOf<String>()
                    for (a in 0 until artistsArr.length()) {
                        val aObj = artistsArr.optJSONObject(a)
                        if (aObj != null) {
                            val name = aObj.optString("name", "")
                            if (name.isNotBlank()) list.add(name)
                        }
                    }
                    list.joinToString(", ").ifBlank { "Inconnu" }
                } else "Inconnu"

                val albumObj = t.optJSONObject("album")
                val imagesArr = albumObj?.optJSONArray("images")
                val cover = if (imagesArr != null && imagesArr.length() > 0) {
                    imagesArr.optJSONObject(0)?.optString("url")
                } else null

                val trackTitle = t.optString("name", "").takeIf { it.isNotBlank() } ?: continue

                rawTracks.add(
                    RawImportTrack(
                        title = trackTitle,
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
            try {
                val nextConn = URL(nextUrl).openConnection() as HttpURLConnection
                nextConn.setRequestProperty("Authorization", "Bearer $token")
                nextConn.connectTimeout = 10_000
                nextConn.readTimeout = 10_000

                if (nextConn.responseCode in 200..299) {
                    val nextObj = JSONObject(nextConn.inputStream.bufferedReader().use { it.readText() })
                    parseSpotifyItems(nextObj.optJSONArray("items"))
                    nextUrl = nextObj.optString("next", null)
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Arrêt pagination Spotify suite à erreur", e)
                break
            }
        }

        Pair(title, rawTracks)
    }

    private suspend fun fetchSpotifyLikedSongs(): Pair<String, List<RawImportTrack>> = withContext(Dispatchers.IO) {
        val token = spotifyAuthManager.getValidAccessToken()
            ?: throw SpotifyAuthRequiredException(sessionExpired = spotifyAuthManager.isConnected.value)

        val rawTracks = mutableListOf<RawImportTrack>()
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"

        fun parseSpotifyItems(arr: org.json.JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val t = entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue

                val artistsArr = t.optJSONArray("artists")
                val artistNames = if (artistsArr != null && artistsArr.length() > 0) {
                    val list = mutableListOf<String>()
                    for (a in 0 until artistsArr.length()) {
                        val aObj = artistsArr.optJSONObject(a)
                        if (aObj != null) {
                            val name = aObj.optString("name", "")
                            if (name.isNotBlank()) list.add(name)
                        }
                    }
                    list.joinToString(", ").ifBlank { "Inconnu" }
                } else "Inconnu"

                val albumObj = t.optJSONObject("album")
                val imagesArr = albumObj?.optJSONArray("images")
                val cover = if (imagesArr != null && imagesArr.length() > 0) {
                    imagesArr.optJSONObject(0)?.optString("url")
                } else null

                val trackTitle = t.optString("name", "").takeIf { it.isNotBlank() } ?: continue

                rawTracks.add(
                    RawImportTrack(
                        title = trackTitle,
                        artist = artistNames,
                        album = albumObj?.optString("name"),
                        durationS = t.optInt("duration_ms", 0) / 1000,
                        isrc = t.optJSONObject("external_ids")?.optString("isrc"),
                        coverUrl = cover
                    )
                )
            }
        }

        while (!nextUrl.isNullOrBlank() && nextUrl != "null") {
            try {
                val conn = URL(nextUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                if (conn.responseCode in 200..299) {
                    val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    parseSpotifyItems(root.optJSONArray("items"))
                    nextUrl = root.optString("next", null)
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Arrêt pagination Titres Likés suite à erreur", e)
                break
            }
        }

        Pair("Titres Likés (Spotify)", rawTracks)
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
                        localTrackId = match.first.id,
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
