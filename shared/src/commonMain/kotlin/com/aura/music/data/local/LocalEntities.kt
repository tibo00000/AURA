package com.aura.music.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"]), Index(value = ["normalized_name"])],
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "picture_uri") val pictureUri: String? = null,
    @ColumnInfo(name = "artwork_origin") val artworkOrigin: String? = null,
    @ColumnInfo(name = "artwork_last_resolved_at") val artworkLastResolvedAt: Long? = null,
    val summary: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["primary_artist_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["primary_artist_id"]), Index(value = ["normalized_title"])],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "primary_artist_id") val primaryArtistId: String?,
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String? = null,
    @ColumnInfo(name = "artwork_origin") val artworkOrigin: String? = null,
    @ColumnInfo(name = "artwork_last_resolved_at") val artworkLastResolvedAt: Long? = null,
    @ColumnInfo(name = "release_date") val releaseDate: String? = null,
    @ColumnInfo(name = "track_count") val trackCount: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["primary_artist_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["primary_artist_id"]),
        Index(value = ["album_id"]),
        Index(value = ["normalized_title"]),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "primary_artist_id") val primaryArtistId: String?,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val title: String,
    @ColumnInfo(name = "normalized_title") val normalizedTitle: String,
    @ColumnInfo(name = "display_artist_name") val displayArtistName: String,
    @ColumnInfo(name = "display_album_title") val displayAlbumTitle: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "cover_uri") val coverUri: String? = null,
    @ColumnInfo(name = "canonical_audio_source_type") val canonicalAudioSourceType: String,
    @ColumnInfo(name = "is_liked") val isLiked: Boolean,
    @ColumnInfo(name = "is_downloaded_by_aura") val isDownloadedByAura: Boolean,
    @ColumnInfo(name = "is_explicit") val isExplicit: Boolean? = null,
    val popularity: Int? = null,
    @ColumnInfo(name = "genres_json") val genresJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "track_media_links",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["track_id"], unique = true), Index(value = ["media_store_id"], unique = true)],
)
data class TrackMediaLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "media_store_id") val mediaStoreId: Long,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "date_modified_epoch_ms") val dateModifiedEpochMs: Long? = null,
    @ColumnInfo(name = "availability_status") val availabilityStatus: String,
    @ColumnInfo(name = "last_scanned_at") val lastScannedAt: Long,
)

@Entity(
    tableName = "track_likes",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackLikeEntity(
    @PrimaryKey
    @ColumnInfo(name = "track_id")
    val trackId: String,
    @ColumnInfo(name = "liked_at") val likedAt: Long,
    @ColumnInfo(name = "source_context_type") val sourceContextType: String? = null,
    @ColumnInfo(name = "source_context_id") val sourceContextId: String? = null,
)

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["name"])],
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String? = null,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlist_id", "position"], unique = true),
        Index(value = ["track_id"]),
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "added_from_context_type") val addedFromContextType: String? = null,
    @ColumnInfo(name = "added_from_context_id") val addedFromContextId: String? = null,
)

@Entity(tableName = "playback_snapshots")
data class PlaybackSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "current_track_id") val currentTrackId: String? = null,
    @ColumnInfo(name = "playback_context_type") val playbackContextType: String? = null,
    @ColumnInfo(name = "playback_context_id") val playbackContextId: String? = null,
    @ColumnInfo(name = "playback_context_index") val playbackContextIndex: Int? = null,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "shuffle_enabled") val shuffleEnabled: Boolean,
    @ColumnInfo(name = "repeat_mode") val repeatMode: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "recent_searches",
    indices = [Index(value = ["query"], unique = true), Index(value = ["searched_at"])],
)
data class RecentSearchEntity(
    @PrimaryKey val id: String,
    val query: String,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "sync_enabled") val syncEnabled: Boolean,
    @ColumnInfo(name = "online_search_enabled") val onlineSearchEnabled: Boolean,
    @ColumnInfo(name = "online_search_network_policy") val onlineSearchNetworkPolicy: String,
    @ColumnInfo(name = "stats_sync_network_policy") val statsSyncNetworkPolicy: String,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "sync_token") val syncToken: String? = null,
)

data class TrackListRow(
    val id: String,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    @ColumnInfo(name = "album_id") val albumId: String?,
    val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_title") val albumTitle: String?,
    @ColumnInfo(name = "content_uri") val contentUri: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "is_liked") val isLiked: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

data class PlaylistListRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class PlaylistDetailRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

data class PlaylistTrackRow(
    @ColumnInfo(name = "playlist_item_id") val playlistItemId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_title") val albumTitle: String?,
    @ColumnInfo(name = "content_uri") val contentUri: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "artist_id") val artistId: String? = null,
    @ColumnInfo(name = "album_id") val albumId: String? = null,
    @ColumnInfo(name = "is_liked") val isLiked: Boolean = false,
)

data class ArtistBrowseRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "picture_uri") val pictureUri: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

data class ArtistDetailRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "picture_uri") val pictureUri: String?,
    val summary: String?,
)

data class AlbumBrowseRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    @ColumnInfo(name = "artist_name") val artistName: String?,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int?,
)

data class AlbumDetailRow(
    val id: String,
    val title: String,
    @ColumnInfo(name = "artist_id") val artistId: String?,
    @ColumnInfo(name = "artist_name") val artistName: String?,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int?,
)

// ---------------------------------------------------------------------------
// Source link tables (SRV-008 / AND-009)
// ---------------------------------------------------------------------------

/**
 * Lien entre un artiste local Room et une résolution backend ou provider.
 * Permet à Android de mémoriser l'ID opaque AURA backend et les métadonnées
 * enrichies (picture_uri) sans supprimer ni écraser l'entité locale.
 *
 * Governé par : docs/android/room-schema.md — table artist_source_links
 */
@Entity(
    tableName = "artist_source_links",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["artist_id"]),
        Index(value = ["usage_type"]),
        Index(value = ["provider_name"]),
    ],
)
data class ArtistSourceLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "artist_id") val artistId: String,
    /** 'search', 'metadata', 'detail' */
    @ColumnInfo(name = "usage_type") val usageType: String,
    /** 'aura_backend', 'deezer', etc. */
    @ColumnInfo(name = "provider_name") val providerName: String,
    /** Opaque AURA backend artist ID (e.g. art_XXXX) */
    @ColumnInfo(name = "provider_artist_id") val providerArtistId: String,
    @ColumnInfo(name = "match_score") val matchScore: Double? = null,
    @ColumnInfo(name = "is_active_for_usage") val isActiveForUsage: Boolean = true,
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Lien entre un album local Room et une résolution backend ou provider.
 * Permet à Android de mémoriser l'ID opaque AURA backend et les métadonnées
 * enrichies (cover_uri) sans supprimer ni écraser l'entité locale.
 *
 * Governé par : docs/android/room-schema.md — table album_source_links
 */
@Entity(
    tableName = "album_source_links",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["album_id"]),
        Index(value = ["usage_type"]),
        Index(value = ["provider_name"]),
    ],
)
data class AlbumSourceLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "album_id") val albumId: String,
    /** 'search', 'metadata', 'detail' */
    @ColumnInfo(name = "usage_type") val usageType: String,
    /** 'aura_backend', 'deezer', etc. */
    @ColumnInfo(name = "provider_name") val providerName: String,
    /** Opaque AURA backend album ID (e.g. alb_XXXX) */
    @ColumnInfo(name = "provider_album_id") val providerAlbumId: String,
    @ColumnInfo(name = "provider_artist_id") val providerArtistId: String? = null,
    @ColumnInfo(name = "match_score") val matchScore: Double? = null,
    @ColumnInfo(name = "is_active_for_usage") val isActiveForUsage: Boolean = true,
    @ColumnInfo(name = "metadata_json") val metadataJson: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)


/**
 * Représente un job de téléchargement asynchrone dans la base Room locale.
 * Permet de suivre l'état de téléchargement d'un titre et de mettre à jour la vue.
 *
 * Governé par : docs/android/room-schema.md — table download_jobs
 */
@Entity(
    tableName = "download_jobs",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["status"]),
    ],
)
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "provider_name") val providerName: String,
    val status: String, // 'queued', 'running', 'succeeded', 'failed', 'cancelled'
    @ColumnInfo(name = "progress_percent") val progressPercent: Float? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "archived_in_cloud_at") val archivedInCloudAt: Long? = null,
    @ColumnInfo(name = "purge_after_at") val purgeAfterAt: Long? = null,
)

data class DownloadJobRowModel(
    val jobId: String,
    val trackId: String,
    val title: String,
    val artistName: String,
    val coverUri: String?,
    val status: String,
    val progressPercent: Float?,
    val errorCode: String?,
    val errorMessage: String?,
    val createdAt: Long
)

/**
 * Opérations locales en attente de synchronisation cloud (outbox sync).
 *
 * Governé par : docs/android/local-persistence.md — table sync_outbox
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["status"]),
        Index(value = ["created_at"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "entity_type") val entityType: String, // 'user_settings', 'playlist', 'playlist_item', 'track_like', 'playback_snapshot'
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "operation_type") val operationType: String, // 'patch', 'create', 'update', 'delete', 'set'
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val status: String, // 'pending', 'syncing', 'failed'
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)


