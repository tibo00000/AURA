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

internal fun playlistCloudUnavailableReason(hasLocalAudio: Boolean, cloudAvailable: Boolean, acquisition: PlaylistAcquisitionStatus?, job: DownloadJobEntity? = null): String? {
    if (hasLocalAudio || cloudAvailable) return null
    if (job?.status in listOf("queued", "running", "requires_resolution")) return null
    return when (acquisition?.status) {
        "source_not_authorized" -> when (acquisition.errorCode) {
            "audio_source_ambiguous" -> "Version audio à identifier"
            "audio_source_unresolved" -> "Aucun fichier audio trouvé"
            "source_identity_ambiguous" -> "Correspondances à vérifier"
            else -> "Source audio indisponible"
        }
        "failed" -> "Échec du téléchargement cloud"
        "cancelled" -> "Téléchargement cloud annulé"
        else -> null
    }
}

internal fun playlistCloudRequestFeedback(rows: List<PlaylistAcquisitionStatus>, trackId: String?): String {
    val row = trackId?.let { id -> rows.firstOrNull { it.trackId == id } }
    return when {
        row?.availability == "available" -> "Ce titre est déjà disponible dans le cloud."
        row?.errorCode == "audio_source_ambiguous" -> "La version audio reste incertaine. Aucun téléchargement lancé."
        row?.errorCode == "audio_source_unresolved" -> "Aucun fichier audio trouvé pour ce titre."
        row?.jobStatus == "queued" -> "Titre ajouté à la file des téléchargements cloud."
        row?.jobStatus == "running" -> "Le téléchargement cloud est en cours."
        else -> "Recherche audio demandée. Le téléchargement apparaîtra dans la file après identification."
    }
}
