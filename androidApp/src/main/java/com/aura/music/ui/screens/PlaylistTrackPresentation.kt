package com.aura.music.ui.screens

import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.network.PlaylistAcquisitionStatus

internal fun playlistTrackDownloadStatus(
    hasLocalAudio: Boolean, cloudAvailable: Boolean, acquisition: PlaylistAcquisitionStatus?, job: DownloadJobEntity?,
): TrackDownloadStatus = when {
    hasLocalAudio -> TrackDownloadStatus.Downloaded
    cloudAvailable -> TrackDownloadStatus.NotDownloaded
    job?.status == "running" -> TrackDownloadStatus.Downloading((job.progressPercent ?: 0f).coerceIn(0f, 1f))
    job?.status == "queued" -> TrackDownloadStatus.Queued
    job?.status == "requires_resolution" -> TrackDownloadStatus.RequiresResolution(job.id)
    acquisition?.jobStatus == "requires_resolution" && acquisition.jobId != null -> TrackDownloadStatus.RequiresResolution(requireNotNull(acquisition.jobId))
    acquisition?.jobStatus == "running" -> TrackDownloadStatus.Downloading()
    acquisition?.isAcquisitionInProgress() == true -> TrackDownloadStatus.Queued
    job?.status == "failed" || acquisition?.status == "failed" -> TrackDownloadStatus.Failed(message = job?.errorMessage ?: "Téléchargement indisponible")
    else -> TrackDownloadStatus.NotDownloaded
}

internal fun showPlaylistCloudSummary(rows: List<PlaylistAcquisitionStatus>): Boolean =
    rows.any { it.availability != "available" && it.status != "not_requested" }

internal fun playlistCloudSummaryLabel(rows: List<PlaylistAcquisitionStatus>): String {
    val available = rows.count { it.availability == "available" }
    val running = rows.count { it.availability != "available" && it.jobStatus == "running" }
    val waiting = rows.count { it.isAcquisitionInProgress() && it.jobStatus != "running" }
    val attention = rows.count { it.availability != "available" && !it.isAcquisitionInProgress() && it.status != "not_requested" }
    return listOfNotNull(
        "$available disponible(s)".takeIf { available > 0 },
        "$running téléchargement(s)".takeIf { running > 0 },
        "$waiting en attente".takeIf { waiting > 0 },
        "$attention à vérifier".takeIf { attention > 0 },
    ).joinToString(" · ", prefix = "Cloud · ")
}
