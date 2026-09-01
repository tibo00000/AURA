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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Hydrateur automatique en arrière-plan pour les pistes synchronisées depuis le Cloud / Supabase.
 * Résout les stubs temporaires (Piste xxx, Artiste inconnu, 0:00) auprès de l'API de catalogue.
 */
object DesktopTrackHydrator {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val inFlightResolutions = ConcurrentHashMap.newKeySet<String>()

    suspend fun hydrateTrackStubs(database: AuraDatabase) = withContext(Dispatchers.IO) {
        val allTracks = database.trackDao().getAllTracks()
        val stubsToHydrate = allTracks.filter { track ->
            (track.title.startsWith(Piste , ignoreCase = true) ||
             track.title.equals(Titre inconnu, ignoreCase = true) ||
             track.artistName.equals(Artiste inconnu, ignoreCase = true) ||
             track.durationMs == null ||
             track.durationMs == 0L ||
             track.coverUri.isNullOrBlank()) &&
            !inFlightResolutions.contains(track.id)
        }

        if (stubsToHydrate.isEmpty()) return@withContext

        System.out.println(DesktopTrackHydrator: Hydrating \ track stubs in background...)

        for (stub in stubsToHydrate) {
            val deezerId = DesktopTrackMatcher.extractDeezerId(stub.id) ?: continue
            inFlightResolutions.add(stub.id)
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(https://api.deezer.com/track/"))
 .timeout(Duration.ofSeconds(6))
 .header(User-Agent, AuraMusicDesktop/1.0)
 .GET()
 .build()

 val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
 if (response.statusCode() != 200) continue

 val root = json.parseToJsonElement(response.body()).jsonObject
 if (root.containsKey(error)) continue

 val title = root[title]?.jsonPrimitive?.content ?: continue
 val durationSec = root[duration]?.jsonPrimitive?.longOrNull ?: 0L
 val durationMs = durationSec * 1000L

 val artistObj = root[artist]?.jsonObject
 val artistName = artistObj?.get(name)?.jsonPrimitive?.content ?: Artiste inconnu
 val artistPicture = artistObj?.get(picture_medium)?.jsonPrimitive?.content
 ?: artistObj?.get(picture_big)?.jsonPrimitive?.content

 val albumObj = root[album]?.jsonObject
 val albumTitle = albumObj?.get(title)?.jsonPrimitive?.content
 val coverUri = albumObj?.get(cover_medium)?.jsonPrimitive?.content
 ?: albumObj?.get(cover_big)?.jsonPrimitive?.content

 val now = System.currentTimeMillis()
 val artistId = artist:"
                val albumId = if (albumTitle != null) album:\:" else null

 // Mettre à jour / insérer l'artiste
 database.artistDao().insertArtistIgnore(
 ArtistEntity(
 id = artistId,
 name = artistName,
 normalizedName = SearchNormalizer.normalize(artistName),
 pictureUri = artistPicture,
 createdAt = now,
 updatedAt = now
 )
 )

 // Mettre à jour / insérer l'album
 if (albumId != null && albumTitle != null) {
 database.albumDao().insertAlbumIgnore(
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
 canonicalAudioSourceType = cloud,
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
 System.out.println(Hydrated track \ -> '\' by '\' (\ ms, cover=\))
 } catch (e: Exception) {
 // Ignore transient network errors per track
 } finally {
 inFlightResolutions.remove(stub.id)
 }
 }
 }
}
