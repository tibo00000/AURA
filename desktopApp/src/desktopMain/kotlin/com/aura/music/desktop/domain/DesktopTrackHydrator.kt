package com.aura.music.desktop.domain

import com.aura.music.data.local.AlbumEntity
import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackEntity
import com.aura.music.desktop.utils.DesktopTrackMatcher
import com.aura.music.domain.search.SearchNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Hydrateur automatique en arrière-plan pour les pistes synchronisées depuis le Cloud / Supabase.
 * Résout les stubs temporaires ("Piste xxx", "Artiste inconnu", 0:00) auprès de l'API de catalogue
 * ou via les fichiers déjà en cache local.
 */
object DesktopTrackHydrator {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val inFlightResolutions = ConcurrentHashMap.newKeySet<String>()

    suspend fun hydrateTrackStubs(
        database: AuraDatabase,
        onTrackHydrated: (() -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val allTracks = database.trackDao().getAllTracks()
        val stubsToHydrate = allTracks.filter { track ->
            (track.title.startsWith("Piste ", ignoreCase = true) ||
             track.title.equals("Titre inconnu", ignoreCase = true) ||
             track.artistName.equals("Artiste inconnu", ignoreCase = true) ||
             track.durationMs == null ||
             track.durationMs == 0L ||
             track.coverUri.isNullOrBlank()) &&
            !inFlightResolutions.contains(track.id)
        }.sortedByDescending { it.isLiked }.take(10)

        if (stubsToHydrate.isEmpty()) return@withContext

        System.out.println("DesktopTrackHydrator: Hydrating ${stubsToHydrate.size} track stubs in background...")

        val appDir = File(System.getProperty("user.home"), ".aura")

        for (stub in stubsToHydrate) {
            val sanitizedId = stub.id.replace(':', ';')

            // 0. Vérifier d'abord si un fichier audio existe sur disque pour extraire durée et métadonnées
            val localAudio = listOf(
                File(appDir, "downloads/$sanitizedId.mp3"),
                File(appDir, "cache/stream/$sanitizedId.mp3")
            ).firstOrNull { it.exists() && it.length() > 0L }

            if (localAudio != null) {
                try {
                    val meta = com.aura.music.desktop.media.DesktopMediaMetadataReader.readMetadata(localAudio)
                    if (meta.durationMs > 0L) {
                        val rawTrack = database.trackDao().getRawTrackById(stub.id)
                        if (rawTrack != null && (rawTrack.durationMs == null || rawTrack.durationMs == 0L)) {
                            database.trackDao().upsertTrack(
                                rawTrack.copy(
                                    durationMs = meta.durationMs,
                                    coverUri = rawTrack.coverUri ?: meta.localCoverUri,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                            onTrackHydrated?.invoke()
                        }
                    }
                } catch (_: Exception) {}
            }

            // 1. Résolution via Deezer (par ID direct ou par recherche Titre + Artiste)
            val deezerId = DesktopTrackMatcher.extractDeezerId(stub.id)
            val canSearchByName = deezerId == null &&
                !stub.artistName.equals("Artiste inconnu", ignoreCase = true) &&
                !stub.title.startsWith("Piste ", ignoreCase = true) &&
                !stub.title.equals("Titre inconnu", ignoreCase = true)

            if (deezerId == null && !canSearchByName) {
                continue
            }

            inFlightResolutions.add(stub.id)
            try {
                val apiUrl = if (deezerId != null) {
                    "https://api.deezer.com/track/$deezerId"
                } else {
                    val query = URLEncoder.encode("${stub.artistName} ${stub.title}", "UTF-8")
                    "https://api.deezer.com/search?q=$query"
                }

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "AuraMusicDesktop/1.0")
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) continue

                val root = json.parseToJsonElement(response.body()).jsonObject
                if (root.containsKey("error")) continue

                // Si c'est un résultat de recherche, prendre le premier item
                val trackObj = if (root.containsKey("data")) {
                    root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                } else {
                    root
                }

                val title = trackObj["title"]?.jsonPrimitive?.content ?: stub.title
                val durationSec = trackObj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val durationMs = durationSec * 1000L

                val artistObj = trackObj["artist"]?.jsonObject
                val artistName = artistObj?.get("name")?.jsonPrimitive?.content ?: stub.artistName
                val artistPicture = artistObj?.get("picture_medium")?.jsonPrimitive?.content
                    ?: artistObj?.get("picture_big")?.jsonPrimitive?.content

                val albumObj = trackObj["album"]?.jsonObject
                val albumTitle = albumObj?.get("title")?.jsonPrimitive?.content ?: stub.albumTitle
                val coverUri = albumObj?.get("cover_medium")?.jsonPrimitive?.content
                    ?: albumObj?.get("cover_big")?.jsonPrimitive?.content
                    ?: stub.coverUri

                val now = System.currentTimeMillis()
                val artistId = "artist:${artistName.lowercase().trim().replace(" ", "_")}"
                val albumId = if (albumTitle != null) "album:${artistName.lowercase().trim().replace(" ", "_")}:${albumTitle.lowercase().trim().replace(" ", "_")}" else null

                // Mettre à jour / insérer l'artiste
                database.artistDao().insertArtistsIgnore(
                    listOf(
                        ArtistEntity(
                            id = artistId,
                            name = artistName,
                            normalizedName = SearchNormalizer.normalize(artistName),
                            pictureUri = artistPicture,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                )

                // Mettre à jour / insérer l'album
                if (albumId != null && albumTitle != null) {
                    database.albumDao().insertAlbumsIgnore(
                        listOf(
                            AlbumEntity(
                                id = albumId,
                                primaryArtistId = artistId,
                                title = albumTitle,
                                normalizedTitle = SearchNormalizer.normalize(albumTitle),
                                coverUri = coverUri,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    )
                }

                // Mettre à jour le track dans Room
                val rawTrack = database.trackDao().getRawTrackById(stub.id)
                val updatedTrack = (rawTrack ?: TrackEntity(
                    id = stub.id,
                    primaryArtistId = artistId,
                    albumId = albumId,
                    title = title,
                    normalizedTitle = SearchNormalizer.normalize(title),
                    displayArtistName = artistName,
                    displayAlbumTitle = albumTitle,
                    durationMs = durationMs,
                    coverUri = coverUri,
                    canonicalAudioSourceType = "cloud",
                    isLiked = stub.isLiked,
                    isDownloadedByAura = false,
                    createdAt = now,
                    updatedAt = now
                )).copy(
                    primaryArtistId = artistId,
                    albumId = albumId,
                    title = title,
                    normalizedTitle = SearchNormalizer.normalize(title),
                    displayArtistName = artistName,
                    displayAlbumTitle = albumTitle,
                    durationMs = if (durationMs > 0L) durationMs else rawTrack?.durationMs,
                    coverUri = coverUri ?: rawTrack?.coverUri,
                    updatedAt = now
                )

                database.trackDao().upsertTrack(updatedTrack)
                onTrackHydrated?.invoke()
                System.out.println("Hydrated track ${stub.id} -> '$title' by '$artistName' ($durationMs ms, cover=$coverUri)")
            } catch (e: Exception) {
                // Ignore transient network errors per track
            } finally {
                inFlightResolutions.remove(stub.id)
            }
        }
        onTrackHydrated?.invoke()
    }
}
