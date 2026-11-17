package com.aura.music.domain.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aura.music.core.AuthSessionManager
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.media.UriPathParser
import com.aura.music.data.network.PlaylistAcquisitionStatus
import com.aura.music.data.playlist.PlaylistSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val PLAYLIST_OCCURRENCE_KEY = "aura.playlist.occurrence"

internal fun MediaItem.occurrenceId(): String? = mediaMetadata.extras?.getString(PLAYLIST_OCCURRENCE_KEY)

internal fun MediaItem.matchesQueueTrack(track: QueuedTrack): Boolean {
    if (mediaId != track.trackId) return false
    val occurrence = occurrenceId()
    return if (occurrence == null) track.playlistCloudAvailable == null else occurrence == track.internalId
}

internal fun MediaMetadata.Builder.withOccurrence(id: String?): MediaMetadata.Builder = apply {
    if (id != null) setExtras(Bundle().apply { putString(PLAYLIST_OCCURRENCE_KEY, id) })
}

internal fun buildPlaylistQueue(
    rows: List<PlaylistTrackRow>,
    cloudTrackIds: Set<String>,
    cloudTrackAliases: Map<String, String> = emptyMap(),
): List<QueuedTrack> = rows.mapNotNull { row ->
    val uri = row.contentUri?.takeIf { it.startsWith("content://") || it.startsWith("file:") || it.startsWith('/') }
    val cloudAlias = cloudTrackAliases[row.trackId]
    val cloudAvailable = row.trackId in cloudTrackIds &&
        (cloudAlias == null || com.aura.music.core.PrivateStreamPolicy.validTrackId(cloudAlias))
    if (!com.aura.music.core.PrivateStreamPolicy.validTrackId(row.trackId) ||
        (uri == null && !cloudAvailable)) return@mapNotNull null
    QueuedTrack(
        trackId = row.trackId,
        title = row.title,
        artistName = row.artistName.orEmpty(),
        albumTitle = row.albumTitle,
        contentUri = uri,
        durationMs = row.durationMs,
        coverUri = row.coverUri,
        internalId = row.playlistItemId,
        playlistCloudAvailable = cloudAvailable,
        playlistCloudTrackId = cloudAlias.takeIf { cloudAvailable },
    )
}

internal fun findPlaylistResumeIndex(tracks: List<QueuedTrack>, trackId: String, occurrenceId: String?): Int {
    if (occurrenceId != null) return tracks.indexOfFirst { it.internalId == occurrenceId && it.trackId == trackId }
    var found = -1
    for ((index, track) in tracks.withIndex()) {
        if (track.trackId != trackId) continue
        if (found >= 0) return -1
        found = index
    }
    return found
}

internal fun resolvePlaylistLocalSources(
    tracks: List<QueuedTrack>,
    missingMediaStoreUris: Set<String>,
    resolve: (QueuedTrack) -> PlayableResolution,
): List<QueuedTrack> {
    val checked = mutableMapOf<Pair<String, String>, PlayableResolution>()
    return tracks.mapNotNull { track ->
        val uri = track.contentUri ?: return@mapNotNull track
        val missing = uri in missingMediaStoreUris
        if (!missing && UriPathParser.isContentUri(uri)) return@mapNotNull track
        val result = checked.getOrPut(track.trackId to uri) {
            resolve(if (missing) track.copy(contentUri = null) else track)
        }
        when (result) {
            is PlayableResolution.LocalFile -> track.copy(contentUri = result.uri)
            is PlayableResolution.ScopedContent -> track.copy(contentUri = result.contentUri)
            is PlayableResolution.CloudStream -> track.copy(contentUri = null)
            is PlayableResolution.NotAvailable -> null
        }
    }
}

class PlaylistPlayback(
    private val auth: AuthSessionManager,
    private val source: PlaylistSourceRepository,
    private val applicationScope: CoroutineScope,
    private val existingCloudIds: () -> Set<String>,
    private val resolver: () -> TrackPlaybackResolver,
    private val mediaStore: MediaStoreAudioDataSource,
) {
    private var cacheScope: AuthSessionManager.Scope? = null
    private val _statuses = MutableStateFlow<Map<String, List<PlaylistAcquisitionStatus>>>(emptyMap())
    val statuses = _statuses.asStateFlow()
    private val _verificationFailures = MutableStateFlow<Set<String>>(emptySet())
    internal val verificationFailures = _verificationFailures.asStateFlow()
    private val verificationJobs = mutableMapOf<String, Job>()
    private val requestVersions = mutableMapOf<String, Long>()
    private val pendingMutationVersions = mutableMapOf<String, Long>()
    private var requestVersion = 0L

    init {
        applicationScope.launch { auth.sessionScope.collect { synchronized(this@PlaylistPlayback) { resetScope() } } }
    }

    @Synchronized
    private fun resetScope() {
        val current = auth.sessionScope.value
        if (cacheScope == current) return
        cacheScope = current
        _statuses.value = emptyMap()
        _verificationFailures.value = emptySet()
        verificationJobs.values.forEach { it.cancel() }
        verificationJobs.clear()
        requestVersions.clear()
        pendingMutationVersions.clear()
    }

    @Synchronized
    fun eligible(playlistId: String, rows: List<PlaylistTrackRow>): List<QueuedTrack> {
        resetScope()
        val status = _statuses.value[playlistId].orEmpty()
        val confirmed = status.asSequence()
            .filter { it.availability == "available" }.map { it.trackId }.toSet()
        val knownMissing = status.asSequence().filter { it.availability == "missing" }.map { it.trackId }.toSet()
        val aliases = status.mapNotNull { row -> row.audioTrackId?.takeIf { row.availability == "available" }
            ?.let { row.trackId to it } }.toMap()
        return buildPlaylistQueue(rows, confirmed + (existingCloudIds() - knownMissing), aliases)
    }

    suspend fun prepare(playlistId: String, rows: List<PlaylistTrackRow>): List<QueuedTrack> {
        val owner = auth.sessionScope.value
        val tracks = eligible(playlistId, rows)
        if (tracks.none { it.contentUri != null }) {
            return if (auth.sessionScope.value == owner) tracks else emptyList()
        }
        val cleaned = withContext(Dispatchers.IO) {
            val missing = mediaStore.missingPlaylistAudioUris(tracks.mapNotNull(QueuedTrack::contentUri))
            resolvePlaylistLocalSources(tracks, missing, resolver()::resolve)
        }
        return if (auth.sessionScope.value == owner) cleaned else emptyList()
    }

    @Synchronized
    fun verifyLater(playlistId: String) {
        resetScope()
        val owner = cacheScope ?: return
        if (verificationJobs[playlistId]?.isActive == true || playlistId in pendingMutationVersions) return
        val version = ++requestVersion
        requestVersions[playlistId] = version
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            var confirmed: List<PlaylistAcquisitionStatus>? = null
            var failed = false
            try {
                confirmed = source.getAcquisitions(playlistId, owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                synchronized(this@PlaylistPlayback) {
                    resetScope()
                    if (cacheScope != owner || requestVersions[playlistId] != version) return@synchronized
                    verificationJobs.remove(playlistId)
                    if (failed) {
                        _verificationFailures.value = (_verificationFailures.value - playlistId + playlistId)
                            .toList().takeLast(32).toSet()
                    }
                    confirmed?.let { publish(playlistId, owner, version, it) }
                }
            }
        }
        verificationJobs[playlistId] = job
        job.start()
    }

    internal suspend fun request(
        playlistId: String, deviceId: String, trackId: String?, owner: AuthSessionManager.Scope,
    ): List<PlaylistAcquisitionStatus> {
        val version = synchronized(this) {
            resetScope()
            if (cacheScope != owner) throw CancellationException("Playlist session changed")
            verificationJobs.remove(playlistId)?.cancel()
            (++requestVersion).also {
                requestVersions[playlistId] = it
                pendingMutationVersions[playlistId] = it
            }
        }
        try {
            val statuses = source.requestAcquisitions(playlistId, deviceId, trackId, owner)
            publish(playlistId, owner, version, statuses)
            return statuses
        } finally {
            synchronized(this) {
                if (pendingMutationVersions[playlistId] == version) pendingMutationVersions.remove(playlistId)
            }
        }
    }

    @Synchronized
    private fun publish(playlistId: String, owner: AuthSessionManager.Scope, version: Long, statuses: List<PlaylistAcquisitionStatus>) {
        resetScope()
        if (cacheScope != owner || requestVersions[playlistId] != version) return
        _statuses.value = (_statuses.value - playlistId + (playlistId to statuses)).entries.toList()
            .takeLast(32).associate { it.key to it.value }
        _verificationFailures.value -= playlistId
    }
}
