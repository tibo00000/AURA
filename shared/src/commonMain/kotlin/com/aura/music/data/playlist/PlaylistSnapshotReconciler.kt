package com.aura.music.data.playlist

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.metadata.repairMissingTrackParents
import com.aura.music.data.metadata.prepareArtistCredits
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.PlaylistSnapshotStateEntity
import com.aura.music.data.local.PlaylistSyncStateEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.network.PlaylistSnapshotResponse
import com.aura.music.domain.search.SearchNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.aura.music.data.network.PlaylistRefreshResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Applies a complete inventory. A playlist with unresolved local edits stays intact
 * until those edits have been acknowledged or their conflict has been resolved.
 */
class PlaylistSnapshotReconciler(
    private val database: AuraDatabase,
    private val parseTimestamp: (String) -> Long,
) {
    suspend fun apply(
        snapshot: PlaylistSnapshotResponse,
        userId: String,
        isCurrentSession: () -> Boolean,
        acknowledgedOperations: Map<String, Long> = emptyMap(),
    ) {
        require(snapshot.contractVersion == 1 && snapshot.revision >= 0)
        require(snapshot.userId == userId && userId.isNotBlank())
        require(snapshot.playlistCount == snapshot.playlists.size)
        require(snapshot.itemCount == snapshot.playlists.sumOf { it.items.size })
        val playlistIds = snapshot.playlists.map { it.id }.toSet()
        require(playlistIds.size == snapshot.playlists.size)
        val itemIds = mutableSetOf<String>()
        for (playlist in snapshot.playlists) {
            require(playlist.userId == userId && playlist.id.isNotBlank() && playlist.orderToken.isNotBlank())
            require(playlist.itemCount == playlist.items.size)
            require(playlist.sourceRevision >= 0)
            require((playlist.sourceProvider == null) == (playlist.sourceExternalId == null))
            require(playlist.lastRefresh == null || playlist.lastRefresh.sourceRevision == playlist.sourceRevision)
            var previousPosition = -1
            for (item in playlist.items) {
                require(item.playlistId == playlist.id && item.id.isNotBlank() && item.trackId.isNotBlank())
                require(item.position > previousPosition && itemIds.add(item.id))
                require(item.sourceMetadata == null ||
                    (item.sourceMetadata.title.isNotBlank() && item.sourceMetadata.artist.isNotBlank()))
                previousPosition = item.position
                parseTimestamp(item.addedAt)
            }
            parseTimestamp(playlist.createdAt)
            parseTimestamp(playlist.updatedAt)
        }

        database.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (!isCurrentSession()) throw CancellationException("Playlist session changed")
                val playlistDao = database.playlistDao()
                val observedRevision = playlistDao.getSnapshotRevision(userId)
                if (observedRevision != null && snapshot.revision < observedRevision) return@immediateTransaction
                repairMissingTrackParents(database, snapshot.playlists.maxOfOrNull { parseTimestamp(it.updatedAt) } ?: 0L, isCurrentSession)
                val creditedTracks = linkedMapOf<String, TrackEntity>()
                val incomingCredits = linkedMapOf<String, List<com.aura.music.data.network.ArtistCreditDto>>()
                val protectedPlaylists = mutableSetOf<String>()
                val outbox = database.syncOutboxDao()
                for (op in outbox.getUnresolvedPlaylistOperations()) {
                    if (op.userId != null && op.userId != userId) continue
                    val acknowledgedRevision = acknowledgedOperations[op.id]
                    if (acknowledgedRevision != null && acknowledgedRevision in 0..snapshot.revision) {
                        outbox.deleteOperation(op.id)
                        continue
                    }
                    val playlistId = if (op.entityType == "playlist_item" && op.payloadJson.trimStart().startsWith("{")) {
                        val payload = Json.decodeFromString<JsonObject>(op.payloadJson)
                        requireNotNull(payload["playlist_id"]?.jsonPrimitive?.contentOrNull)
                    } else op.entityId // Legacy Desktop used the parent ID with a raw track ID payload.
                    protectedPlaylists.add(playlistId)
                    if (op.userId == null && playlistId in playlistIds) {
                        outbox.assignLegacyPlaylistOperation(op.id, userId)
                    }
                }

                for (remote in snapshot.playlists) {
                    val synced = playlistDao.getPlaylistSyncState(remote.id)
                    require(synced == null || synced.userId == userId) { "Conflicting playlist owner" }
                    val newerSource = synced?.takeIf { it.sourceRevision > remote.sourceRevision }
                    val localRefresh = synced?.lastRefreshJson
                        ?.takeIf { synced.sourceRevision == remote.sourceRevision }
                        ?.let { Json.decodeFromString<PlaylistRefreshResult>(it) }
                    val remoteRefresh = remote.lastRefresh
                    val retainLocalRefresh = localRefresh != null &&
                        (remoteRefresh == null || parseTimestamp(localRefresh.completedAt) > parseTimestamp(remoteRefresh.completedAt))
                    val state = PlaylistSyncStateEntity(
                        remote.id, userId, remote.updatedAt, remote.orderToken, snapshot.revision,
                        if (newerSource != null) newerSource.sourceProvider else remote.sourceProvider,
                        if (newerSource != null) newerSource.sourceExternalId else remote.sourceExternalId,
                        newerSource?.sourceRevision ?: remote.sourceRevision,
                        if (newerSource != null || retainLocalRefresh) synced?.lastRefreshJson
                        else remoteRefresh?.let { Json.encodeToString(it) },
                        synced?.pendingRefreshJson?.takeIf { synced.sourceRevision >= remote.sourceRevision },
                    )
                    if (remote.id in protectedPlaylists) {
                        // A matching server parent proves ownership even while a legacy edit
                        // keeps its local contents protected from this snapshot.
                        if (synced == null && playlistDao.getPlaylistEntity(remote.id) != null) {
                            playlistDao.upsertPlaylistSyncState(state.copy(appliedRevision = null))
                        } else if (synced != null && remote.sourceRevision >= synced.sourceRevision) {
                            val updatedSource = synced.copy(
                                sourceProvider = remote.sourceProvider,
                                sourceExternalId = remote.sourceExternalId,
                                sourceRevision = remote.sourceRevision,
                                lastRefreshJson = state.lastRefreshJson,
                                pendingRefreshJson = state.pendingRefreshJson,
                            )
                            if (synced != updatedSource) playlistDao.upsertPlaylistSyncState(updatedSource)
                        }
                        continue
                    }
                    val updatedAt = parseTimestamp(remote.updatedAt)

                    val parent = PlaylistEntity(
                        id = remote.id,
                        name = remote.name,
                        coverUri = remote.coverUri,
                        isPinned = remote.isPinned,
                        createdAt = parseTimestamp(remote.createdAt),
                        updatedAt = updatedAt,
                    )
                    if (playlistDao.getPlaylistEntity(remote.id) != parent) playlistDao.upsertPlaylist(parent)
                    val desired = remote.items.map { item ->
                        PlaylistItemEntity(
                            id = item.id,
                            playlistId = item.playlistId,
                            trackId = item.trackId,
                            position = item.position,
                            addedAt = parseTimestamp(item.addedAt),
                            addedFromContextType = item.addedFromContextType,
                            addedFromContextId = item.addedFromContextId,
                        )
                    }
                    val previous = playlistDao.getPlaylistItems(remote.id)
                    val desiredById = desired.associateBy { it.id }
                    val previousById = previous.associateBy { it.id }
                    val moved = previous.filter { item ->
                        desiredById[item.id]?.let { it.position != item.position } == true
                    }
                    var temporaryPosition = -1
                    val occupied = previous.mapTo(mutableSetOf()) { it.position }
                    for (item in previous) {
                        if (item.id !in desiredById) playlistDao.deletePlaylistItem(item.id)
                    }
                    for (item in moved) {
                        while (temporaryPosition in occupied) temporaryPosition--
                        playlistDao.updatePlaylistItemPosition(item.id, temporaryPosition--)
                    }
                    for ((index, item) in desired.withIndex()) {
                        val metadata = remote.items[index].sourceMetadata
                        if (item == previousById[item.id] && metadata == null) continue
                        val existing = playlistDao.getPlaylistItem(item.id)
                        require(existing == null || existing.playlistId == remote.id) { "Conflicting playlist item ID" }
                        val trackDao = database.trackDao()
                        val track = trackDao.getRawTrackById(item.trackId)
                        val updatedTrack = if (track == null) {
                            val title = metadata?.title ?: "Morceau synchronisé"
                            TrackEntity(
                                    id = item.trackId,
                                    primaryArtistId = null,
                                    albumId = null,
                                    title = title,
                                    normalizedTitle = SearchNormalizer.normalize(title),
                                    displayArtistName = metadata?.artist ?: "Artiste inconnu",
                                    displayAlbumTitle = metadata?.album,
                                    durationMs = metadata?.durationMs,
                                    coverUri = metadata?.coverUri,
                                    canonicalAudioSourceType = "online",
                                    isLiked = false,
                                    isDownloadedByAura = false,
                                    createdAt = item.addedAt,
                                    updatedAt = item.addedAt,
                            )
                        } else if (metadata != null) {
                            val title = if (track.title in setOf("Morceau synchronisé", "Unknown Title")) metadata.title else track.title
                            track.copy(
                                title = title,
                                normalizedTitle = if (title == track.title) track.normalizedTitle else SearchNormalizer.normalize(title),
                                displayArtistName = if (track.displayArtistName in setOf("Artiste inconnu", "Unknown artist", ""))
                                    metadata.artist else track.displayArtistName,
                                displayAlbumTitle = track.displayAlbumTitle ?: metadata.album,
                                durationMs = track.durationMs?.takeIf { it > 0 } ?: metadata.durationMs,
                                coverUri = track.coverUri ?: metadata.coverUri,
                            )
                        } else track
                        if (updatedTrack != track) trackDao.upsertTrack(updatedTrack)
                        if (!metadata?.artists.isNullOrEmpty()) {
                            creditedTracks[item.trackId] = updatedTrack
                            incomingCredits[item.trackId] = metadata!!.artists
                        }
                        if (item != previousById[item.id]) playlistDao.upsertPlaylistItem(item)
                    }
                    if (synced != state) playlistDao.upsertPlaylistSyncState(state)
                }
                if (incomingCredits.isNotEmpty()) prepareArtistCredits(database, creditedTracks, incomingCredits,
                    snapshot.playlists.maxOfOrNull { parseTimestamp(it.updatedAt) } ?: 0L).apply(database, isCurrentSession)
                for (synced in playlistDao.getPlaylistSyncStates(userId)) {
                    val appliedRevision = synced.appliedRevision ?: continue
                    if (snapshot.revision > appliedRevision &&
                        synced.playlistId !in playlistIds && synced.playlistId !in protectedPlaylists
                    ) {
                        playlistDao.deletePlaylist(synced.playlistId)
                    }
                }
                if (observedRevision != snapshot.revision) {
                    playlistDao.upsertSnapshotState(PlaylistSnapshotStateEntity(userId, snapshot.revision))
                }
                if (!isCurrentSession()) throw CancellationException("Playlist session changed")
            }
        }
    }
}
