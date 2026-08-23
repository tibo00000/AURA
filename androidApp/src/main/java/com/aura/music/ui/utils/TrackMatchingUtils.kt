package com.aura.music.ui.utils

import android.util.Base64
import android.util.LruCache
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.SyncedFileResponseData
import com.aura.music.ui.screens.TrackDownloadStatus

/**
 * Moteur de mémoïsation thread-safe pour le décodage d'identifiants Deezer (Base64 / token URL safe).
 */
object DeezerIdMemoizer {
    private val cache = LruCache<String, String>(1000)

    fun extractDeezerId(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val cached = cache.get(id)
        if (cached != null) return if (cached.isEmpty()) null else cached

        val resolved = computeDeezerId(id)
        cache.put(id, resolved ?: "")
        return resolved
    }

    private fun computeDeezerId(id: String): String? {
        if (id.startsWith("trk_")) {
            return try {
                val token = id.removePrefix("trk_")
                val padding = when (token.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                val paddedToken = token + padding
                val bytes = Base64.decode(paddedToken, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val decoded = String(bytes, Charsets.UTF_8)
                val parts = decoded.split(":")
                if (parts.size >= 4 && parts[2].equals("deezer", ignoreCase = true)) parts[3].trim() else null
            } catch (e: Exception) {
                null
            }
        } else if (id.startsWith("deezer:", ignoreCase = true)) {
            return id.substringAfter(":").trim()
        } else if (id.all { it.isDigit() }) {
            return id.trim()
        }
        return null
    }

    fun isDeezerTrackMatch(trackIdA: String?, trackIdB: String?): Boolean {
        if (trackIdA.isNullOrBlank() || trackIdB.isNullOrBlank()) return false
        if (trackIdA == trackIdB) return true
        val deezerIdA = extractDeezerId(trackIdA)
        val deezerIdB = extractDeezerId(trackIdB)
        return deezerIdA != null && deezerIdB != null && deezerIdA == deezerIdB
    }
}

/**
 * Clé normalisée pour la réconciliation rapide par triplet (Titre + Artiste + Album).
 */
data class NormalizedTrackKey(
    val titleNorm: String,
    val artistNorm: String,
    val albumNorm: String
) {
    companion object {
        fun normalizeText(text: String?): String = text?.trim()?.lowercase().orEmpty()

        fun from(title: String?, artistName: String?, albumTitle: String?): NormalizedTrackKey =
            NormalizedTrackKey(
                titleNorm = normalizeText(title),
                artistNorm = normalizeText(artistName),
                albumNorm = normalizeText(albumTitle)
            )
    }
}

data class NormalizedDoubletKey(
    val titleNorm: String,
    val artistNorm: String
) {
    companion object {
        fun from(title: String?, artistName: String?): NormalizedDoubletKey =
            NormalizedDoubletKey(
                titleNorm = NormalizedTrackKey.normalizeText(title),
                artistNorm = NormalizedTrackKey.normalizeText(artistName)
            )
    }
}

/**
 * Index en mémoire précalculé en O(N) permettant des vérifications en temps constant O(1).
 *
 * Résout les collisions en privilégiant systématiquement les fichiers téléchargés (contentUri != null).
 */
class TrackLookupIndex(
    private val localByExactId: Map<String, List<TrackListRow>>,
    private val localByDeezerId: Map<String, List<TrackListRow>>,
    private val localByTriplet: Map<NormalizedTrackKey, List<TrackListRow>>,
    private val localByDoublet: Map<NormalizedDoubletKey, List<TrackListRow>>,
    private val syncedCloudExactIds: Set<String>,
    private val syncedCloudDeezerIds: Set<String>,
    private val cloudFilesByDeezerId: Map<String, List<SyncedFileResponseData>>,
    private val cloudFilesByTriplet: Map<NormalizedTrackKey, List<SyncedFileResponseData>>,
    private val cloudFilesByDoublet: Map<NormalizedDoubletKey, List<SyncedFileResponseData>>
) {
    /**
     * Recherche une correspondance locale en O(1).
     * Privilégie une piste possédant un contentUri non vide (téléchargée).
     */
    fun findLocalMatch(
        trackId: String?,
        title: String?,
        artistName: String?,
        albumTitle: String?
    ): TrackListRow? {
        if (!trackId.isNullOrBlank()) {
            localByExactId[trackId]?.let { list ->
                val preferred = list.firstOrNull { !it.contentUri.isNullOrBlank() } ?: list.firstOrNull()
                if (preferred != null) return preferred
            }
            val deezerId = DeezerIdMemoizer.extractDeezerId(trackId)
            if (deezerId != null) {
                localByDeezerId[deezerId]?.let { list ->
                    val preferred = list.firstOrNull { !it.contentUri.isNullOrBlank() } ?: list.firstOrNull()
                    if (preferred != null) return preferred
                }
            }
        }

        val normTitle = NormalizedTrackKey.normalizeText(title)
        if (normTitle.isEmpty()) return null

        val normArtist = NormalizedTrackKey.normalizeText(artistName)
        val normAlbum = NormalizedTrackKey.normalizeText(albumTitle)

        if (normAlbum.isNotEmpty() && normArtist.isNotEmpty()) {
            val tripletKey = NormalizedTrackKey(normTitle, normArtist, normAlbum)
            localByTriplet[tripletKey]?.let { list ->
                val preferred = list.firstOrNull { !it.contentUri.isNullOrBlank() } ?: list.firstOrNull()
                if (preferred != null) return preferred
            }
        }

        if (normArtist.isNotEmpty()) {
            val doubletKey = NormalizedDoubletKey(normTitle, normArtist)
            localByDoublet[doubletKey]?.let { list ->
                val match = list.firstOrNull { row ->
                    val rowAlbumNorm = NormalizedTrackKey.normalizeText(row.albumTitle)
                    normAlbum.isEmpty() || rowAlbumNorm.isEmpty() || normAlbum == rowAlbumNorm
                }
                if (match != null) return match
            }
        }

        return null
    }

    /**
     * Vérifie si un morceau est synchronisé / présent sur le Cloud en O(1).
     */
    fun isCloudSynced(
        trackId: String?,
        title: String?,
        artistName: String?,
        albumTitle: String?
    ): Boolean {
        if (!trackId.isNullOrBlank()) {
            if (syncedCloudExactIds.contains(trackId)) return true
            val deezerId = DeezerIdMemoizer.extractDeezerId(trackId)
            if (deezerId != null) {
                if (syncedCloudDeezerIds.contains(deezerId)) return true
                if (cloudFilesByDeezerId.containsKey(deezerId)) return true
            }
        }

        val normTitle = NormalizedTrackKey.normalizeText(title)
        if (normTitle.isEmpty()) return false

        val normArtist = NormalizedTrackKey.normalizeText(artistName)
        val normAlbum = NormalizedTrackKey.normalizeText(albumTitle)

        if (normAlbum.isNotEmpty() && normArtist.isNotEmpty()) {
            val tripletKey = NormalizedTrackKey(normTitle, normArtist, normAlbum)
            if (cloudFilesByTriplet.containsKey(tripletKey)) return true
        }

        if (normArtist.isNotEmpty()) {
            val doubletKey = NormalizedDoubletKey(normTitle, normArtist)
            val candidates = cloudFilesByDoublet[doubletKey]
            if (candidates != null) {
                val match = candidates.any { cloud ->
                    val cloudAlbumNorm = NormalizedTrackKey.normalizeText(cloud.albumTitle)
                    normAlbum.isEmpty() || cloudAlbumNorm.isEmpty() || normAlbum == cloudAlbumNorm
                }
                if (match) return true
            }
        }

        return false
    }

    /**
     * Résout le statut de téléchargement d'un morceau en O(1) avec fallback sur le Deezer ID memoizé.
     */
    fun resolveDownloadStatus(
        trackId: String?,
        downloadStatusMap: Map<String, TrackDownloadStatus>
    ): TrackDownloadStatus {
        if (trackId.isNullOrBlank() || downloadStatusMap.isEmpty()) return TrackDownloadStatus.Idle
        downloadStatusMap[trackId]?.let { return it }

        val deezerId = DeezerIdMemoizer.extractDeezerId(trackId)
        if (deezerId != null) {
            for ((key, status) in downloadStatusMap) {
                if (DeezerIdMemoizer.extractDeezerId(key) == deezerId) {
                    return status
                }
            }
        }
        return TrackDownloadStatus.Idle
    }

    companion object {
        val EMPTY = TrackLookupIndex(
            localByExactId = emptyMap(),
            localByDeezerId = emptyMap(),
            localByTriplet = emptyMap(),
            localByDoublet = emptyMap(),
            syncedCloudExactIds = emptySet(),
            syncedCloudDeezerIds = emptySet(),
            cloudFilesByDeezerId = emptyMap(),
            cloudFilesByTriplet = emptyMap(),
            cloudFilesByDoublet = emptyMap()
        )

        fun build(
            allLibraryTracks: List<TrackListRow>,
            cloudFiles: List<SyncedFileResponseData> = emptyList(),
            syncedCloudTrackIds: Set<String> = emptySet()
        ): TrackLookupIndex {
            val localByExactId = mutableMapOf<String, MutableList<TrackListRow>>()
            val localByDeezerId = mutableMapOf<String, MutableList<TrackListRow>>()
            val localByTriplet = mutableMapOf<NormalizedTrackKey, MutableList<TrackListRow>>()
            val localByDoublet = mutableMapOf<NormalizedDoubletKey, MutableList<TrackListRow>>()

            for (track in allLibraryTracks) {
                localByExactId.getOrPut(track.id) { mutableListOf() }.add(track)
                val deezerId = DeezerIdMemoizer.extractDeezerId(track.id)
                if (deezerId != null) {
                    localByDeezerId.getOrPut(deezerId) { mutableListOf() }.add(track)
                }
                val normTitle = NormalizedTrackKey.normalizeText(track.title)
                if (normTitle.isNotEmpty()) {
                    val normArtist = NormalizedTrackKey.normalizeText(track.artistName)
                    val normAlbum = NormalizedTrackKey.normalizeText(track.albumTitle)
                    if (normArtist.isNotEmpty()) {
                        localByDoublet.getOrPut(NormalizedDoubletKey(normTitle, normArtist)) { mutableListOf() }.add(track)
                        if (normAlbum.isNotEmpty()) {
                            localByTriplet.getOrPut(NormalizedTrackKey(normTitle, normArtist, normAlbum)) { mutableListOf() }.add(track)
                        }
                    }
                }
            }

            val syncedCloudExactIds = HashSet<String>(syncedCloudTrackIds)
            val syncedCloudDeezerIds = HashSet<String>()
            for (id in syncedCloudTrackIds) {
                val deezerId = DeezerIdMemoizer.extractDeezerId(id)
                if (deezerId != null) {
                    syncedCloudDeezerIds.add(deezerId)
                }
            }

            val cloudFilesByDeezerId = mutableMapOf<String, MutableList<SyncedFileResponseData>>()
            val cloudFilesByTriplet = mutableMapOf<NormalizedTrackKey, MutableList<SyncedFileResponseData>>()
            val cloudFilesByDoublet = mutableMapOf<NormalizedDoubletKey, MutableList<SyncedFileResponseData>>()

            for (cloud in cloudFiles) {
                val deezerId = DeezerIdMemoizer.extractDeezerId(cloud.trackId)
                if (deezerId != null) {
                    cloudFilesByDeezerId.getOrPut(deezerId) { mutableListOf() }.add(cloud)
                }
                val normTitle = NormalizedTrackKey.normalizeText(cloud.title)
                if (normTitle.isNotEmpty()) {
                    val normArtist = NormalizedTrackKey.normalizeText(cloud.artistName)
                    val normAlbum = NormalizedTrackKey.normalizeText(cloud.albumTitle)
                    if (normArtist.isNotEmpty()) {
                        cloudFilesByDoublet.getOrPut(NormalizedDoubletKey(normTitle, normArtist)) { mutableListOf() }.add(cloud)
                        if (normAlbum.isNotEmpty()) {
                            cloudFilesByTriplet.getOrPut(NormalizedTrackKey(normTitle, normArtist, normAlbum)) { mutableListOf() }.add(cloud)
                        }
                    }
                }
            }

            return TrackLookupIndex(
                localByExactId = localByExactId,
                localByDeezerId = localByDeezerId,
                localByTriplet = localByTriplet,
                localByDoublet = localByDoublet,
                syncedCloudExactIds = syncedCloudExactIds,
                syncedCloudDeezerIds = syncedCloudDeezerIds,
                cloudFilesByDeezerId = cloudFilesByDeezerId,
                cloudFilesByTriplet = cloudFilesByTriplet,
                cloudFilesByDoublet = cloudFilesByDoublet
            )
        }
    }
}

/**
 * Formateur ultra-léger évitant les instanciations de String.format et le parsing de regex.
 */
object FastTimeFormatter {
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (sec < 10) "$min:0$sec" else "$min:$sec"
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0.0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        val intPart = mb.toLong()
        val decPart = ((mb - intPart) * 10).toLong().coerceIn(0, 9)
        return "$intPart.$decPart MB"
    }
}
