package com.aura.music.data.repository

import android.content.Context
import android.util.Log
import com.aura.music.data.local.AlbumSourceLinkEntity
import com.aura.music.data.local.ArtistSourceLinkEntity
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.NetworkPolicyChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * EnrichmentRepository — gouvernance réseau + persistance des enrichissements d'image/metadata.
 *
 * Règles (AND-009):
 * - Chaque appel est précédé d'une vérification NetworkPolicyChecker.isAllowed().
 * - Si le réseau est bloqué par les réglages, retourne null sans erreur bloquante.
 * - Si le résultat de résolution est positif, il est persisté dans Room
 *   (artist_source_links / album_source_links + artwork sur l'entité locale).
 * - La déduplication est assurée par artwork_last_resolved_at :
 *   si un enrichissement récent (< ENRICHMENT_TTL_MS) existe déjà, on ne rappelle pas.
 * - Les suggestions locales pendant la saisie ne déclenchent jamais ce repo.
 *
 * Governs: docs/android/local-persistence.md, docs/android/room-schema.md,
 *           docs/android/screens/settings.md, docs/server/api-contract.md
 */
class EnrichmentRepository(
    private val database: AuraDatabase,
    private val apiService: AuraApiService,
    private val context: Context,
) {
    companion object {
        private const val TAG = "EnrichmentRepository"

        /** TTL de déduplication : 7 jours en ms */
        private const val ENRICHMENT_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Score minimum de confiance pour persister une résolution */
        private const val MIN_CONFIDENCE = 0.60
    }

    // ------------------------------------------------------------------
    // Artist enrichment
    // ------------------------------------------------------------------

    /**
     * Tente d'enrichir l'image d'un artiste local depuis le backend.
     *
     * Retourne l'URI d'image résolue si succès, null sinon (réseau bloqué, non trouvé, etc.).
     * Persistance automatique dans artist_source_links et dans artists.picture_uri.
     *
     * @param artistId    ID AURA local de l'artiste (ex: "artist:daft-punk")
     * @param artistName  Nom de l'artiste utilisé pour la résolution Deezer
     */
    suspend fun enrichArtistArtwork(
        artistId: String,
        artistName: String,
        trackHint: String? = null,
        albumHint: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        // 1. Vérification politique réseau
        val settings = database.userSettingsDao().getSettings() ?: return@withContext null
        if (!NetworkPolicyChecker.isAllowed(
                onlineSearchEnabled = settings.onlineSearchEnabled,
                policy = settings.onlineSearchNetworkPolicy,
                context = context,
            )
        ) {
            Log.d(TAG, "enrichArtistArtwork: blocked by network policy for $artistId")
            return@withContext null
        }

        // 2. Déduplication par TTL
        val lastResolved = database.artistDao().getArtworkLastResolvedAt(artistId)
        if (lastResolved != null && System.currentTimeMillis() - lastResolved < ENRICHMENT_TTL_MS) {
            Log.d(TAG, "enrichArtistArtwork: skipping (fresh enrichment exists) for $artistId")
            return@withContext database.artistDao().getArtistDetail(artistId)?.pictureUri
        }

        // 3. Extraction d'échantillons de discographie si non fournis pour désambiguïser les homonymes
        val resolvedTrackHint = trackHint ?: database.trackDao().getSampleTrackTitleForArtist(artistId)
        val resolvedAlbumHint = albumHint ?: database.albumDao().getSampleAlbumTitleForArtist(artistId)

        // 4. Appel backend
        val response = runCatching {
            apiService.resolveArtist(
                name = artistName,
                trackTitle = resolvedTrackHint,
                albumTitle = resolvedAlbumHint
            )
        }.getOrElse { e ->
            Log.w(TAG, "enrichArtistArtwork: network error for $artistId", e)
            return@withContext null
        }

        val resolveData = response.data ?: return@withContext null
        if (!resolveData.resolved || resolveData.matchConfidence < MIN_CONFIDENCE) {
            Log.d(TAG, "enrichArtistArtwork: low confidence (${resolveData.matchConfidence}) for $artistId")
            return@withContext null
        }

        val artistData = resolveData.artist ?: return@withContext null
        val pictureUri = artistData.pictureUri ?: return@withContext null

        // 5. Persistance
        val now = System.currentTimeMillis()
        database.artistDao().updateArtwork(
            artistId = artistId,
            pictureUri = pictureUri,
            artworkOrigin = "backend_remote",
            resolvedAt = now,
            updatedAt = now,
        )
        database.artistSourceLinkDao().upsert(
            ArtistSourceLinkEntity(
                id = "asl:${artistId}:metadata:aura_backend:${artistData.id}",
                artistId = artistId,
                usageType = "metadata",
                providerName = "aura_backend",
                providerArtistId = artistData.id,
                matchScore = resolveData.matchConfidence,
                isActiveForUsage = true,
                metadataJson = null,
                createdAt = now,
                updatedAt = now,
            )
        )
        Log.i(TAG, "enrichArtistArtwork: persisted pictureUri for $artistId")
        pictureUri
    }

    // ------------------------------------------------------------------
    // Album enrichment
    // ------------------------------------------------------------------

    /**
     * Tente d'enrichir la cover d'un album local depuis le backend.
     *
     * Retourne l'URI de cover résolue si succès, null sinon.
     * Persistance automatique dans album_source_links et dans albums.cover_uri.
     *
     * @param albumId    ID AURA local de l'album (ex: "album:daft-punk:discovery")
     * @param albumTitle Titre de l'album utilisé pour la résolution
     * @param artistName Nom de l'artiste (hint, fortement recommandé)
     * @param trackHint  Titre d'un morceau de l'album pour désambiguïser
     */
    suspend fun enrichAlbumArtwork(
        albumId: String,
        albumTitle: String,
        artistName: String?,
        trackHint: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        // 1. Vérification politique réseau
        val settings = database.userSettingsDao().getSettings() ?: return@withContext null
        if (!NetworkPolicyChecker.isAllowed(
                onlineSearchEnabled = settings.onlineSearchEnabled,
                policy = settings.onlineSearchNetworkPolicy,
                context = context,
            )
        ) {
            Log.d(TAG, "enrichAlbumArtwork: blocked by network policy for $albumId")
            return@withContext null
        }

        // 2. Déduplication par TTL
        val lastResolved = database.albumDao().getArtworkLastResolvedAt(albumId)
        if (lastResolved != null && System.currentTimeMillis() - lastResolved < ENRICHMENT_TTL_MS) {
            Log.d(TAG, "enrichAlbumArtwork: skipping (fresh enrichment exists) for $albumId")
            return@withContext database.albumDao().getAlbumDetail(albumId)?.coverUri
        }

        // 3. Extraction d'échantillon de morceau si non fourni
        val resolvedTrackHint = trackHint ?: database.trackDao().getSampleTrackTitleForAlbum(albumId)

        // 4. Appel backend
        val response = runCatching {
            apiService.resolveAlbum(
                title = albumTitle,
                artistName = artistName,
                trackTitle = resolvedTrackHint
            )
        }.getOrElse { e ->
            Log.w(TAG, "enrichAlbumArtwork: network error for $albumId", e)
            return@withContext null
        }

        val resolveData = response.data ?: return@withContext null
        if (!resolveData.resolved || resolveData.matchConfidence < MIN_CONFIDENCE) {
            Log.d(TAG, "enrichAlbumArtwork: low confidence (${resolveData.matchConfidence}) for $albumId")
            return@withContext null
        }

        val albumData = resolveData.album ?: return@withContext null
        val coverUri = albumData.coverUri ?: return@withContext null

        // 5. Persistance
        val now = System.currentTimeMillis()
        database.albumDao().updateArtwork(
            albumId = albumId,
            coverUri = coverUri,
            artworkOrigin = "backend_remote",
            resolvedAt = now,
            updatedAt = now,
        )
        database.albumSourceLinkDao().upsert(
            AlbumSourceLinkEntity(
                id = "asl:${albumId}:metadata:aura_backend:${albumData.id}",
                albumId = albumId,
                usageType = "metadata",
                providerName = "aura_backend",
                providerAlbumId = albumData.id,
                providerArtistId = null,
                matchScore = resolveData.matchConfidence,
                isActiveForUsage = true,
                metadataJson = null,
                createdAt = now,
                updatedAt = now,
            )
        )
        Log.i(TAG, "enrichAlbumArtwork: persisted coverUri for $albumId")
        coverUri
    }

    // ------------------------------------------------------------------
    // Backend ID lookup (AND-010)
    // ------------------------------------------------------------------

    /**
     * Retourne l'ID backend opaque mémorisé pour un artiste local, ou null si absent.
     * Utilisé par ArtistScreen pour chaîner vers /artists/{id} si disponible.
     */
    suspend fun getBackendArtistId(artistId: String): String? = withContext(Dispatchers.IO) {
        database.artistSourceLinkDao()
            .getActiveLink(artistId, "metadata")
            ?.providerArtistId
    }

    /**
     * Retourne l'ID backend opaque mémorisé pour un album local, ou null si absent.
     * Utilisé par AlbumScreen pour chaîner vers /albums/{id} si disponible.
     */
    suspend fun getBackendAlbumId(albumId: String): String? = withContext(Dispatchers.IO) {
        database.albumSourceLinkDao()
            .getActiveLink(albumId, "metadata")
            ?.providerAlbumId
    }
}
