package com.aura.music.`data`.local

import androidx.room3.InvalidationTracker
import androidx.room3.RoomOpenDelegate
import androidx.room3.migration.AutoMigrationSpec
import androidx.room3.migration.Migration
import androidx.room3.util.TableInfo
import androidx.room3.util.TableInfo.Companion.read
import androidx.room3.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.executeSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class AuraDatabase_Impl : AuraDatabase() {
  private val _artistDao: Lazy<ArtistDao> = lazy {
    ArtistDao_Impl(this)
  }

  private val _albumDao: Lazy<AlbumDao> = lazy {
    AlbumDao_Impl(this)
  }

  private val _trackDao: Lazy<TrackDao> = lazy {
    TrackDao_Impl(this)
  }

  private val _trackLikeDao: Lazy<TrackLikeDao> = lazy {
    TrackLikeDao_Impl(this)
  }

  private val _playlistDao: Lazy<PlaylistDao> = lazy {
    PlaylistDao_Impl(this)
  }

  private val _playbackSnapshotDao: Lazy<PlaybackSnapshotDao> = lazy {
    PlaybackSnapshotDao_Impl(this)
  }

  private val _recentSearchDao: Lazy<RecentSearchDao> = lazy {
    RecentSearchDao_Impl(this)
  }

  private val _userSettingsDao: Lazy<UserSettingsDao> = lazy {
    UserSettingsDao_Impl(this)
  }

  private val _artistSourceLinkDao: Lazy<ArtistSourceLinkDao> = lazy {
    ArtistSourceLinkDao_Impl(this)
  }

  private val _albumSourceLinkDao: Lazy<AlbumSourceLinkDao> = lazy {
    AlbumSourceLinkDao_Impl(this)
  }

  private val _downloadJobDao: Lazy<DownloadJobDao> = lazy {
    DownloadJobDao_Impl(this)
  }

  private val _syncOutboxDao: Lazy<SyncOutboxDao> = lazy {
    SyncOutboxDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(4, "4d1b83f1655de3314f9a83a8e92fd2f3", "b917a564ac42ec64327f4fc281005ba0") {
      public override suspend fun createAllTables(connection: SQLiteConnection) {
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `albums` (`id` TEXT NOT NULL, `primary_artist_id` TEXT, `title` TEXT NOT NULL, `normalized_title` TEXT NOT NULL, `cover_uri` TEXT, `artwork_origin` TEXT, `artwork_last_resolved_at` INTEGER, `release_date` TEXT, `track_count` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`primary_artist_id`) REFERENCES `artists`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_albums_primary_artist_id` ON `albums` (`primary_artist_id`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_albums_normalized_title` ON `albums` (`normalized_title`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `album_source_links` (`id` TEXT NOT NULL, `album_id` TEXT NOT NULL, `usage_type` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `provider_album_id` TEXT NOT NULL, `provider_artist_id` TEXT, `match_score` REAL, `is_active_for_usage` INTEGER NOT NULL, `metadata_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`album_id`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_album_id` ON `album_source_links` (`album_id`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_usage_type` ON `album_source_links` (`usage_type`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_provider_name` ON `album_source_links` (`provider_name`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `artists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `normalized_name` TEXT NOT NULL, `picture_uri` TEXT, `artwork_origin` TEXT, `artwork_last_resolved_at` INTEGER, `summary` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_artists_name` ON `artists` (`name`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_artists_normalized_name` ON `artists` (`normalized_name`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `artist_source_links` (`id` TEXT NOT NULL, `artist_id` TEXT NOT NULL, `usage_type` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `provider_artist_id` TEXT NOT NULL, `match_score` REAL, `is_active_for_usage` INTEGER NOT NULL, `metadata_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`artist_id`) REFERENCES `artists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_artist_id` ON `artist_source_links` (`artist_id`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_usage_type` ON `artist_source_links` (`usage_type`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_provider_name` ON `artist_source_links` (`provider_name`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `download_jobs` (`id` TEXT NOT NULL, `track_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `status` TEXT NOT NULL, `progress_percent` REAL, `error_code` TEXT, `error_message` TEXT, `attempt_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `archived_in_cloud_at` INTEGER, `purge_after_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_download_jobs_track_id` ON `download_jobs` (`track_id`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_download_jobs_status` ON `download_jobs` (`status`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `playback_snapshots` (`id` TEXT NOT NULL, `current_track_id` TEXT, `playback_context_type` TEXT, `playback_context_id` TEXT, `playback_context_index` INTEGER, `position_ms` INTEGER NOT NULL, `shuffle_enabled` INTEGER NOT NULL, `repeat_mode` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `cover_uri` TEXT, `is_pinned` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_playlists_name` ON `playlists` (`name`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `playlist_items` (`id` TEXT NOT NULL, `playlist_id` TEXT NOT NULL, `track_id` TEXT NOT NULL, `position` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, `added_from_context_type` TEXT, `added_from_context_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.executeSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_items_playlist_id_position` ON `playlist_items` (`playlist_id`, `position`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_playlist_items_track_id` ON `playlist_items` (`track_id`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `recent_searches` (`id` TEXT NOT NULL, `query` TEXT NOT NULL, `searched_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.executeSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_searches_query` ON `recent_searches` (`query`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_recent_searches_searched_at` ON `recent_searches` (`searched_at`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`id` TEXT NOT NULL, `entity_type` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `operation_type` TEXT NOT NULL, `payload_json` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_status` ON `sync_outbox` (`status`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_created_at` ON `sync_outbox` (`created_at`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `tracks` (`id` TEXT NOT NULL, `primary_artist_id` TEXT, `album_id` TEXT, `title` TEXT NOT NULL, `normalized_title` TEXT NOT NULL, `display_artist_name` TEXT NOT NULL, `display_album_title` TEXT, `duration_ms` INTEGER, `cover_uri` TEXT, `canonical_audio_source_type` TEXT NOT NULL, `is_liked` INTEGER NOT NULL, `is_downloaded_by_aura` INTEGER NOT NULL, `is_explicit` INTEGER, `popularity` INTEGER, `genres_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`primary_artist_id`) REFERENCES `artists`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`album_id`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_tracks_primary_artist_id` ON `tracks` (`primary_artist_id`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_tracks_album_id` ON `tracks` (`album_id`)")
        connection.executeSQL("CREATE INDEX IF NOT EXISTS `index_tracks_normalized_title` ON `tracks` (`normalized_title`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `track_likes` (`track_id` TEXT NOT NULL, `liked_at` INTEGER NOT NULL, `source_context_type` TEXT, `source_context_id` TEXT, PRIMARY KEY(`track_id`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `track_media_links` (`id` TEXT NOT NULL, `track_id` TEXT NOT NULL, `media_store_id` INTEGER NOT NULL, `content_uri` TEXT NOT NULL, `file_size_bytes` INTEGER, `mime_type` TEXT, `date_modified_epoch_ms` INTEGER, `availability_status` TEXT NOT NULL, `last_scanned_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.executeSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_track_media_links_track_id` ON `track_media_links` (`track_id`)")
        connection.executeSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_track_media_links_media_store_id` ON `track_media_links` (`media_store_id`)")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS `user_settings` (`id` TEXT NOT NULL, `sync_enabled` INTEGER NOT NULL, `online_search_enabled` INTEGER NOT NULL, `online_search_network_policy` TEXT NOT NULL, `stats_sync_network_policy` TEXT NOT NULL, `last_sync_at` INTEGER, `sync_token` TEXT, PRIMARY KEY(`id`))")
        connection.executeSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.executeSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '4d1b83f1655de3314f9a83a8e92fd2f3')")
      }

      public override suspend fun dropAllTables(connection: SQLiteConnection) {
        connection.executeSQL("DROP TABLE IF EXISTS `albums`")
        connection.executeSQL("DROP TABLE IF EXISTS `album_source_links`")
        connection.executeSQL("DROP TABLE IF EXISTS `artists`")
        connection.executeSQL("DROP TABLE IF EXISTS `artist_source_links`")
        connection.executeSQL("DROP TABLE IF EXISTS `download_jobs`")
        connection.executeSQL("DROP TABLE IF EXISTS `playback_snapshots`")
        connection.executeSQL("DROP TABLE IF EXISTS `playlists`")
        connection.executeSQL("DROP TABLE IF EXISTS `playlist_items`")
        connection.executeSQL("DROP TABLE IF EXISTS `recent_searches`")
        connection.executeSQL("DROP TABLE IF EXISTS `sync_outbox`")
        connection.executeSQL("DROP TABLE IF EXISTS `tracks`")
        connection.executeSQL("DROP TABLE IF EXISTS `track_likes`")
        connection.executeSQL("DROP TABLE IF EXISTS `track_media_links`")
        connection.executeSQL("DROP TABLE IF EXISTS `user_settings`")
      }

      public override suspend fun onCreate(connection: SQLiteConnection) {
      }

      public override suspend fun onOpen(connection: SQLiteConnection) {
        connection.executeSQL("PRAGMA foreign_keys = ON")
        internalInitInvalidationTracker(connection)
      }

      public override suspend fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override suspend fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override suspend fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
        val _columnsAlbums: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAlbums.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("primary_artist_id", TableInfo.Column("primary_artist_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("title", TableInfo.Column("title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("normalized_title", TableInfo.Column("normalized_title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("cover_uri", TableInfo.Column("cover_uri", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("artwork_origin", TableInfo.Column("artwork_origin", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("artwork_last_resolved_at", TableInfo.Column("artwork_last_resolved_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("release_date", TableInfo.Column("release_date", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("track_count", TableInfo.Column("track_count", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAlbums: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysAlbums.add(TableInfo.ForeignKey("artists", "SET NULL", "NO ACTION", listOf("primary_artist_id"), listOf("id")))
        val _indicesAlbums: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesAlbums.add(TableInfo.Index("index_albums_primary_artist_id", false, listOf("primary_artist_id"), listOf("ASC")))
        _indicesAlbums.add(TableInfo.Index("index_albums_normalized_title", false, listOf("normalized_title"), listOf("ASC")))
        val _infoAlbums: TableInfo = TableInfo("albums", _columnsAlbums, _foreignKeysAlbums, _indicesAlbums)
        val _existingAlbums: TableInfo = read(connection, "albums")
        if (!_infoAlbums.equals(_existingAlbums)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |albums(com.aura.music.data.local.AlbumEntity).
              | Expected:
              |""".trimMargin() + _infoAlbums + """
              |
              | Found:
              |""".trimMargin() + _existingAlbums)
        }
        val _columnsAlbumSourceLinks: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAlbumSourceLinks.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("album_id", TableInfo.Column("album_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("usage_type", TableInfo.Column("usage_type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("provider_name", TableInfo.Column("provider_name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("provider_album_id", TableInfo.Column("provider_album_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("provider_artist_id", TableInfo.Column("provider_artist_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("match_score", TableInfo.Column("match_score", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("is_active_for_usage", TableInfo.Column("is_active_for_usage", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("metadata_json", TableInfo.Column("metadata_json", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAlbumSourceLinks: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysAlbumSourceLinks.add(TableInfo.ForeignKey("albums", "CASCADE", "NO ACTION", listOf("album_id"), listOf("id")))
        val _indicesAlbumSourceLinks: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesAlbumSourceLinks.add(TableInfo.Index("index_album_source_links_album_id", false, listOf("album_id"), listOf("ASC")))
        _indicesAlbumSourceLinks.add(TableInfo.Index("index_album_source_links_usage_type", false, listOf("usage_type"), listOf("ASC")))
        _indicesAlbumSourceLinks.add(TableInfo.Index("index_album_source_links_provider_name", false, listOf("provider_name"), listOf("ASC")))
        val _infoAlbumSourceLinks: TableInfo = TableInfo("album_source_links", _columnsAlbumSourceLinks, _foreignKeysAlbumSourceLinks, _indicesAlbumSourceLinks)
        val _existingAlbumSourceLinks: TableInfo = read(connection, "album_source_links")
        if (!_infoAlbumSourceLinks.equals(_existingAlbumSourceLinks)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |album_source_links(com.aura.music.data.local.AlbumSourceLinkEntity).
              | Expected:
              |""".trimMargin() + _infoAlbumSourceLinks + """
              |
              | Found:
              |""".trimMargin() + _existingAlbumSourceLinks)
        }
        val _columnsArtists: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsArtists.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("name", TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("normalized_name", TableInfo.Column("normalized_name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("picture_uri", TableInfo.Column("picture_uri", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("artwork_origin", TableInfo.Column("artwork_origin", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("artwork_last_resolved_at", TableInfo.Column("artwork_last_resolved_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("summary", TableInfo.Column("summary", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysArtists: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesArtists: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesArtists.add(TableInfo.Index("index_artists_name", false, listOf("name"), listOf("ASC")))
        _indicesArtists.add(TableInfo.Index("index_artists_normalized_name", false, listOf("normalized_name"), listOf("ASC")))
        val _infoArtists: TableInfo = TableInfo("artists", _columnsArtists, _foreignKeysArtists, _indicesArtists)
        val _existingArtists: TableInfo = read(connection, "artists")
        if (!_infoArtists.equals(_existingArtists)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |artists(com.aura.music.data.local.ArtistEntity).
              | Expected:
              |""".trimMargin() + _infoArtists + """
              |
              | Found:
              |""".trimMargin() + _existingArtists)
        }
        val _columnsArtistSourceLinks: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsArtistSourceLinks.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("artist_id", TableInfo.Column("artist_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("usage_type", TableInfo.Column("usage_type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("provider_name", TableInfo.Column("provider_name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("provider_artist_id", TableInfo.Column("provider_artist_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("match_score", TableInfo.Column("match_score", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("is_active_for_usage", TableInfo.Column("is_active_for_usage", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("metadata_json", TableInfo.Column("metadata_json", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysArtistSourceLinks: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysArtistSourceLinks.add(TableInfo.ForeignKey("artists", "CASCADE", "NO ACTION", listOf("artist_id"), listOf("id")))
        val _indicesArtistSourceLinks: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesArtistSourceLinks.add(TableInfo.Index("index_artist_source_links_artist_id", false, listOf("artist_id"), listOf("ASC")))
        _indicesArtistSourceLinks.add(TableInfo.Index("index_artist_source_links_usage_type", false, listOf("usage_type"), listOf("ASC")))
        _indicesArtistSourceLinks.add(TableInfo.Index("index_artist_source_links_provider_name", false, listOf("provider_name"), listOf("ASC")))
        val _infoArtistSourceLinks: TableInfo = TableInfo("artist_source_links", _columnsArtistSourceLinks, _foreignKeysArtistSourceLinks, _indicesArtistSourceLinks)
        val _existingArtistSourceLinks: TableInfo = read(connection, "artist_source_links")
        if (!_infoArtistSourceLinks.equals(_existingArtistSourceLinks)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |artist_source_links(com.aura.music.data.local.ArtistSourceLinkEntity).
              | Expected:
              |""".trimMargin() + _infoArtistSourceLinks + """
              |
              | Found:
              |""".trimMargin() + _existingArtistSourceLinks)
        }
        val _columnsDownloadJobs: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsDownloadJobs.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("track_id", TableInfo.Column("track_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("provider_name", TableInfo.Column("provider_name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("status", TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("progress_percent", TableInfo.Column("progress_percent", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("error_code", TableInfo.Column("error_code", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("error_message", TableInfo.Column("error_message", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("attempt_count", TableInfo.Column("attempt_count", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("archived_in_cloud_at", TableInfo.Column("archived_in_cloud_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("purge_after_at", TableInfo.Column("purge_after_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysDownloadJobs: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysDownloadJobs.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION", listOf("track_id"), listOf("id")))
        val _indicesDownloadJobs: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesDownloadJobs.add(TableInfo.Index("index_download_jobs_track_id", false, listOf("track_id"), listOf("ASC")))
        _indicesDownloadJobs.add(TableInfo.Index("index_download_jobs_status", false, listOf("status"), listOf("ASC")))
        val _infoDownloadJobs: TableInfo = TableInfo("download_jobs", _columnsDownloadJobs, _foreignKeysDownloadJobs, _indicesDownloadJobs)
        val _existingDownloadJobs: TableInfo = read(connection, "download_jobs")
        if (!_infoDownloadJobs.equals(_existingDownloadJobs)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |download_jobs(com.aura.music.data.local.DownloadJobEntity).
              | Expected:
              |""".trimMargin() + _infoDownloadJobs + """
              |
              | Found:
              |""".trimMargin() + _existingDownloadJobs)
        }
        val _columnsPlaybackSnapshots: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPlaybackSnapshots.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("current_track_id", TableInfo.Column("current_track_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("playback_context_type", TableInfo.Column("playback_context_type", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("playback_context_id", TableInfo.Column("playback_context_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("playback_context_index", TableInfo.Column("playback_context_index", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("position_ms", TableInfo.Column("position_ms", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("shuffle_enabled", TableInfo.Column("shuffle_enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("repeat_mode", TableInfo.Column("repeat_mode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPlaybackSnapshots: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesPlaybackSnapshots: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoPlaybackSnapshots: TableInfo = TableInfo("playback_snapshots", _columnsPlaybackSnapshots, _foreignKeysPlaybackSnapshots, _indicesPlaybackSnapshots)
        val _existingPlaybackSnapshots: TableInfo = read(connection, "playback_snapshots")
        if (!_infoPlaybackSnapshots.equals(_existingPlaybackSnapshots)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |playback_snapshots(com.aura.music.data.local.PlaybackSnapshotEntity).
              | Expected:
              |""".trimMargin() + _infoPlaybackSnapshots + """
              |
              | Found:
              |""".trimMargin() + _existingPlaybackSnapshots)
        }
        val _columnsPlaylists: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPlaylists.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("name", TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("cover_uri", TableInfo.Column("cover_uri", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("is_pinned", TableInfo.Column("is_pinned", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPlaylists: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesPlaylists: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesPlaylists.add(TableInfo.Index("index_playlists_name", false, listOf("name"), listOf("ASC")))
        val _infoPlaylists: TableInfo = TableInfo("playlists", _columnsPlaylists, _foreignKeysPlaylists, _indicesPlaylists)
        val _existingPlaylists: TableInfo = read(connection, "playlists")
        if (!_infoPlaylists.equals(_existingPlaylists)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |playlists(com.aura.music.data.local.PlaylistEntity).
              | Expected:
              |""".trimMargin() + _infoPlaylists + """
              |
              | Found:
              |""".trimMargin() + _existingPlaylists)
        }
        val _columnsPlaylistItems: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPlaylistItems.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("playlist_id", TableInfo.Column("playlist_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("track_id", TableInfo.Column("track_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("position", TableInfo.Column("position", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("added_at", TableInfo.Column("added_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("added_from_context_type", TableInfo.Column("added_from_context_type", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("added_from_context_id", TableInfo.Column("added_from_context_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPlaylistItems: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysPlaylistItems.add(TableInfo.ForeignKey("playlists", "CASCADE", "NO ACTION", listOf("playlist_id"), listOf("id")))
        _foreignKeysPlaylistItems.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION", listOf("track_id"), listOf("id")))
        val _indicesPlaylistItems: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesPlaylistItems.add(TableInfo.Index("index_playlist_items_playlist_id_position", true, listOf("playlist_id", "position"), listOf("ASC", "ASC")))
        _indicesPlaylistItems.add(TableInfo.Index("index_playlist_items_track_id", false, listOf("track_id"), listOf("ASC")))
        val _infoPlaylistItems: TableInfo = TableInfo("playlist_items", _columnsPlaylistItems, _foreignKeysPlaylistItems, _indicesPlaylistItems)
        val _existingPlaylistItems: TableInfo = read(connection, "playlist_items")
        if (!_infoPlaylistItems.equals(_existingPlaylistItems)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |playlist_items(com.aura.music.data.local.PlaylistItemEntity).
              | Expected:
              |""".trimMargin() + _infoPlaylistItems + """
              |
              | Found:
              |""".trimMargin() + _existingPlaylistItems)
        }
        val _columnsRecentSearches: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsRecentSearches.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRecentSearches.put("query", TableInfo.Column("query", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsRecentSearches.put("searched_at", TableInfo.Column("searched_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysRecentSearches: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesRecentSearches: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesRecentSearches.add(TableInfo.Index("index_recent_searches_query", true, listOf("query"), listOf("ASC")))
        _indicesRecentSearches.add(TableInfo.Index("index_recent_searches_searched_at", false, listOf("searched_at"), listOf("ASC")))
        val _infoRecentSearches: TableInfo = TableInfo("recent_searches", _columnsRecentSearches, _foreignKeysRecentSearches, _indicesRecentSearches)
        val _existingRecentSearches: TableInfo = read(connection, "recent_searches")
        if (!_infoRecentSearches.equals(_existingRecentSearches)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |recent_searches(com.aura.music.data.local.RecentSearchEntity).
              | Expected:
              |""".trimMargin() + _infoRecentSearches + """
              |
              | Found:
              |""".trimMargin() + _existingRecentSearches)
        }
        val _columnsSyncOutbox: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsSyncOutbox.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("entity_type", TableInfo.Column("entity_type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("entity_id", TableInfo.Column("entity_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("operation_type", TableInfo.Column("operation_type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("payload_json", TableInfo.Column("payload_json", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("status", TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("attempt_count", TableInfo.Column("attempt_count", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSyncOutbox: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesSyncOutbox: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesSyncOutbox.add(TableInfo.Index("index_sync_outbox_status", false, listOf("status"), listOf("ASC")))
        _indicesSyncOutbox.add(TableInfo.Index("index_sync_outbox_created_at", false, listOf("created_at"), listOf("ASC")))
        val _infoSyncOutbox: TableInfo = TableInfo("sync_outbox", _columnsSyncOutbox, _foreignKeysSyncOutbox, _indicesSyncOutbox)
        val _existingSyncOutbox: TableInfo = read(connection, "sync_outbox")
        if (!_infoSyncOutbox.equals(_existingSyncOutbox)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |sync_outbox(com.aura.music.data.local.SyncOutboxEntity).
              | Expected:
              |""".trimMargin() + _infoSyncOutbox + """
              |
              | Found:
              |""".trimMargin() + _existingSyncOutbox)
        }
        val _columnsTracks: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsTracks.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("primary_artist_id", TableInfo.Column("primary_artist_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("album_id", TableInfo.Column("album_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("title", TableInfo.Column("title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("normalized_title", TableInfo.Column("normalized_title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("display_artist_name", TableInfo.Column("display_artist_name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("display_album_title", TableInfo.Column("display_album_title", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("duration_ms", TableInfo.Column("duration_ms", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("cover_uri", TableInfo.Column("cover_uri", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("canonical_audio_source_type", TableInfo.Column("canonical_audio_source_type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("is_liked", TableInfo.Column("is_liked", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("is_downloaded_by_aura", TableInfo.Column("is_downloaded_by_aura", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("is_explicit", TableInfo.Column("is_explicit", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("popularity", TableInfo.Column("popularity", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("genres_json", TableInfo.Column("genres_json", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTracks: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysTracks.add(TableInfo.ForeignKey("artists", "SET NULL", "NO ACTION", listOf("primary_artist_id"), listOf("id")))
        _foreignKeysTracks.add(TableInfo.ForeignKey("albums", "SET NULL", "NO ACTION", listOf("album_id"), listOf("id")))
        val _indicesTracks: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesTracks.add(TableInfo.Index("index_tracks_primary_artist_id", false, listOf("primary_artist_id"), listOf("ASC")))
        _indicesTracks.add(TableInfo.Index("index_tracks_album_id", false, listOf("album_id"), listOf("ASC")))
        _indicesTracks.add(TableInfo.Index("index_tracks_normalized_title", false, listOf("normalized_title"), listOf("ASC")))
        val _infoTracks: TableInfo = TableInfo("tracks", _columnsTracks, _foreignKeysTracks, _indicesTracks)
        val _existingTracks: TableInfo = read(connection, "tracks")
        if (!_infoTracks.equals(_existingTracks)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |tracks(com.aura.music.data.local.TrackEntity).
              | Expected:
              |""".trimMargin() + _infoTracks + """
              |
              | Found:
              |""".trimMargin() + _existingTracks)
        }
        val _columnsTrackLikes: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsTrackLikes.put("track_id", TableInfo.Column("track_id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackLikes.put("liked_at", TableInfo.Column("liked_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackLikes.put("source_context_type", TableInfo.Column("source_context_type", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackLikes.put("source_context_id", TableInfo.Column("source_context_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTrackLikes: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysTrackLikes.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION", listOf("track_id"), listOf("id")))
        val _indicesTrackLikes: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoTrackLikes: TableInfo = TableInfo("track_likes", _columnsTrackLikes, _foreignKeysTrackLikes, _indicesTrackLikes)
        val _existingTrackLikes: TableInfo = read(connection, "track_likes")
        if (!_infoTrackLikes.equals(_existingTrackLikes)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |track_likes(com.aura.music.data.local.TrackLikeEntity).
              | Expected:
              |""".trimMargin() + _infoTrackLikes + """
              |
              | Found:
              |""".trimMargin() + _existingTrackLikes)
        }
        val _columnsTrackMediaLinks: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsTrackMediaLinks.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("track_id", TableInfo.Column("track_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("media_store_id", TableInfo.Column("media_store_id", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("content_uri", TableInfo.Column("content_uri", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("file_size_bytes", TableInfo.Column("file_size_bytes", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("mime_type", TableInfo.Column("mime_type", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("date_modified_epoch_ms", TableInfo.Column("date_modified_epoch_ms", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("availability_status", TableInfo.Column("availability_status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("last_scanned_at", TableInfo.Column("last_scanned_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTrackMediaLinks: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysTrackMediaLinks.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION", listOf("track_id"), listOf("id")))
        val _indicesTrackMediaLinks: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesTrackMediaLinks.add(TableInfo.Index("index_track_media_links_track_id", true, listOf("track_id"), listOf("ASC")))
        _indicesTrackMediaLinks.add(TableInfo.Index("index_track_media_links_media_store_id", true, listOf("media_store_id"), listOf("ASC")))
        val _infoTrackMediaLinks: TableInfo = TableInfo("track_media_links", _columnsTrackMediaLinks, _foreignKeysTrackMediaLinks, _indicesTrackMediaLinks)
        val _existingTrackMediaLinks: TableInfo = read(connection, "track_media_links")
        if (!_infoTrackMediaLinks.equals(_existingTrackMediaLinks)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |track_media_links(com.aura.music.data.local.TrackMediaLinkEntity).
              | Expected:
              |""".trimMargin() + _infoTrackMediaLinks + """
              |
              | Found:
              |""".trimMargin() + _existingTrackMediaLinks)
        }
        val _columnsUserSettings: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsUserSettings.put("id", TableInfo.Column("id", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("sync_enabled", TableInfo.Column("sync_enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("online_search_enabled", TableInfo.Column("online_search_enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("online_search_network_policy", TableInfo.Column("online_search_network_policy", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("stats_sync_network_policy", TableInfo.Column("stats_sync_network_policy", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("last_sync_at", TableInfo.Column("last_sync_at", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("sync_token", TableInfo.Column("sync_token", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysUserSettings: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesUserSettings: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoUserSettings: TableInfo = TableInfo("user_settings", _columnsUserSettings, _foreignKeysUserSettings, _indicesUserSettings)
        val _existingUserSettings: TableInfo = read(connection, "user_settings")
        if (!_infoUserSettings.equals(_existingUserSettings)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |user_settings(com.aura.music.data.local.UserSettingsEntity).
              | Expected:
              |""".trimMargin() + _infoUserSettings + """
              |
              | Found:
              |""".trimMargin() + _existingUserSettings)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "albums", "album_source_links", "artists", "artist_source_links", "download_jobs", "playback_snapshots", "playlists", "playlist_items", "recent_searches", "sync_outbox", "tracks", "track_likes", "track_media_links", "user_settings")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(ArtistDao::class, ArtistDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AlbumDao::class, AlbumDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(TrackDao::class, TrackDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(TrackLikeDao::class, TrackLikeDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PlaylistDao::class, PlaylistDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PlaybackSnapshotDao::class, PlaybackSnapshotDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(RecentSearchDao::class, RecentSearchDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(UserSettingsDao::class, UserSettingsDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ArtistSourceLinkDao::class, ArtistSourceLinkDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AlbumSourceLinkDao::class, AlbumSourceLinkDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(DownloadJobDao::class, DownloadJobDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(SyncOutboxDao::class, SyncOutboxDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun artistDao(): ArtistDao = _artistDao.value

  public override fun albumDao(): AlbumDao = _albumDao.value

  public override fun trackDao(): TrackDao = _trackDao.value

  public override fun trackLikeDao(): TrackLikeDao = _trackLikeDao.value

  public override fun playlistDao(): PlaylistDao = _playlistDao.value

  public override fun playbackSnapshotDao(): PlaybackSnapshotDao = _playbackSnapshotDao.value

  public override fun recentSearchDao(): RecentSearchDao = _recentSearchDao.value

  public override fun userSettingsDao(): UserSettingsDao = _userSettingsDao.value

  public override fun artistSourceLinkDao(): ArtistSourceLinkDao = _artistSourceLinkDao.value

  public override fun albumSourceLinkDao(): AlbumSourceLinkDao = _albumSourceLinkDao.value

  public override fun downloadJobDao(): DownloadJobDao = _downloadJobDao.value

  public override fun syncOutboxDao(): SyncOutboxDao = _syncOutboxDao.value
}
