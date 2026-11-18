package com.aura.music.ui.screens

import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.network.PlaylistAcquisitionStatus

internal fun playlistTrackDownloadStatus(
    hasLocalAudio: Boolean, cloudAvailable: Boolean, acquisition: PlaylistAcquisitionStatus?, job: DownloadJobEntity?,
): TrackDownloadStatus = when {
    hasLocalAudio -> TrackDownloadStatus.Downloaded
    cloudAvailable -> TrackDownloadStatus.NotDownloaded
    job?.status == "running" -> TrackDownloadStatus.Downloading(((job.progressPercent ?: 0f) / 100f).coerceIn(0f, 1f))
    job?.status == "queued" -> TrackDownloadStatus.Queued
    job?.status == "requires_resolution" -> TrackDownloadStatus.RequiresResolution(job.id)
    acquisition?.jobStatus == "requires_resolution" && acquisition.jobId != null -> TrackDownloadStatus.RequiresResolution(requireNotNull(acquisition.jobId))
    acquisition?.jobStatus == "running" && acquisition.jobId != null -> TrackDownloadStatus.Downloading()
    acquisition?.jobStatus == "queued" && acquisition.jobId != null -> TrackDownloadStatus.Queued
    job?.status == "failed" || acquisition?.status == "failed" -> TrackDownloadStatus.Failed(message = job?.errorMessage ?: "Téléchargement indisponible")
    else -> TrackDownloadStatus.NotDownloaded
}

internal fun playlistCloudPending(hasLocalAudio: Boolean, cloudAvailable: Boolean, acquisition: PlaylistAcquisitionStatus?): Boolean =
    !hasLocalAudio && !cloudAvailable && acquisition?.isAcquisitionInProgress() == true &&
        (acquisition.jobId == null || acquisition.jobStatus !in setOf("queued", "running"))
