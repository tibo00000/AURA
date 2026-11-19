package com.aura.music.data.repository

import com.aura.music.data.local.TrackEntity
import com.aura.music.data.network.DownloadJobResponseData

/** Match our generated labels exactly; a user's title starting with "Piste" is not a placeholder. */
internal fun TrackEntity.hasDownloadPlaceholder(): Boolean =
    title == "Piste ${id.takeLast(6)}" || title == "Piste Cloud $id" ||
        (title == "Titre inconnu" && displayArtistName in setOf("Téléchargement", "Artiste Inconnu"))

internal fun reconcileDownloadTrack(
    existing: TrackEntity?, item: DownloadJobResponseData, now: Long,
): TrackEntity {
    val provisional = existing == null || existing.hasDownloadPlaceholder()
    val metadata = item.metadata
    val title = metadata?.title?.takeIf(String::isNotBlank)
    val base = existing ?: TrackEntity(
        id = item.trackId, primaryArtistId = null, albumId = null,
        title = title ?: "Piste ${item.trackId.takeLast(6)}", normalizedTitle = "piste",
        displayArtistName = "Téléchargement", displayAlbumTitle = null,
        durationMs = null, coverUri = null, canonicalAudioSourceType = "cloud_only",
        isLiked = false, isDownloadedByAura = false, isExplicit = null,
        popularity = null, genresJson = null, createdAt = now, updatedAt = now,
    )
    val repaired = if (provisional && title != null) base.copy(
        title = title, normalizedTitle = title.lowercase().trim(),
        displayArtistName = if (base.displayArtistName in setOf("Téléchargement", "Artiste Inconnu", "Artiste", ""))
            metadata?.artist?.takeIf(String::isNotBlank) ?: base.displayArtistName else base.displayArtistName,
        displayAlbumTitle = base.displayAlbumTitle?.takeUnless { it == "Album Inconnu" || it.isBlank() }
            ?: metadata?.album?.takeIf(String::isNotBlank),
        durationMs = base.durationMs?.takeIf { it > 0 } ?: metadata?.durationMs?.takeIf { it > 0 },
        coverUri = base.coverUri ?: metadata?.coverUri?.takeIf(String::isNotBlank),
    ) else base
    // A completed job can survive a removed private cloud copy. Do not turn
    // the locally reconstructed placeholder into a playable phantom.
    val checked = if (provisional && item.audioAvailable == false && !repaired.isDownloadedByAura &&
        repaired.canonicalAudioSourceType == "cloud") repaired.copy(canonicalAudioSourceType = "cloud_only") else repaired
    return if (existing != null && checked != existing) checked.copy(updatedAt = now) else checked
}
