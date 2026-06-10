package com.aura.music.`data`.local

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.room.RoomOpenHelper
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.lang.Class
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import javax.`annotation`.processing.Generated
import kotlin.Any
import kotlin.Boolean
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.Set

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class AuraDatabase_Impl : AuraDatabase() {
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


  protected override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper {
    val _openCallback: SupportSQLiteOpenHelper.Callback = RoomOpenHelper(config, object :
        RoomOpenHelper.Delegate(4) {
      public override fun createAllTables(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `albums` (`id` TEXT NOT NULL, `primary_artist_id` TEXT, `title` TEXT NOT NULL, `normalized_title` TEXT NOT NULL, `cover_uri` TEXT, `artwork_origin` TEXT, `artwork_last_resolved_at` INTEGER, `release_date` TEXT, `track_count` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`primary_artist_id`) REFERENCES `artists`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_albums_primary_artist_id` ON `albums` (`primary_artist_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_albums_normalized_title` ON `albums` (`normalized_title`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `album_source_links` (`id` TEXT NOT NULL, `album_id` TEXT NOT NULL, `usage_type` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `provider_album_id` TEXT NOT NULL, `provider_artist_id` TEXT, `match_score` REAL, `is_active_for_usage` INTEGER NOT NULL, `metadata_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`album_id`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_album_id` ON `album_source_links` (`album_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_usage_type` ON `album_source_links` (`usage_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_provider_name` ON `album_source_links` (`provider_name`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `artists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `normalized_name` TEXT NOT NULL, `picture_uri` TEXT, `artwork_origin` TEXT, `artwork_last_resolved_at` INTEGER, `summary` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_artists_name` ON `artists` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_artists_normalized_name` ON `artists` (`normalized_name`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `artist_source_links` (`id` TEXT NOT NULL, `artist_id` TEXT NOT NULL, `usage_type` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `provider_artist_id` TEXT NOT NULL, `match_score` REAL, `is_active_for_usage` INTEGER NOT NULL, `metadata_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`artist_id`) REFERENCES `artists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_artist_id` ON `artist_source_links` (`artist_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_usage_type` ON `artist_source_links` (`usage_type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_provider_name` ON `artist_source_links` (`provider_name`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `download_jobs` (`id` TEXT NOT NULL, `track_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `status` TEXT NOT NULL, `progress_percent` REAL, `error_code` TEXT, `error_message` TEXT, `attempt_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `archived_in_cloud_at` INTEGER, `purge_after_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_jobs_track_id` ON `download_jobs` (`track_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_download_jobs_status` ON `download_jobs` (`status`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playback_snapshots` (`id` TEXT NOT NULL, `current_track_id` TEXT, `playback_context_type` TEXT, `playback_context_id` TEXT, `playback_context_index` INTEGER, `position_ms` INTEGER NOT NULL, `shuffle_enabled` INTEGER NOT NULL, `repeat_mode` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `cover_uri` TEXT, `is_pinned` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_name` ON `playlists` (`name`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_items` (`id` TEXT NOT NULL, `playlist_id` TEXT NOT NULL, `track_id` TEXT NOT NULL, `position` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, `added_from_context_type` TEXT, `added_from_context_id` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_items_playlist_id_position` ON `playlist_items` (`playlist_id`, `position`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_items_track_id` ON `playlist_items` (`track_id`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `recent_searches` (`id` TEXT NOT NULL, `query` TEXT NOT NULL, `searched_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recent_searches_query` ON `recent_searches` (`query`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recent_searches_searched_at` ON `recent_searches` (`searched_at`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`id` TEXT NOT NULL, `entity_type` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `operation_type` TEXT NOT NULL, `payload_json` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_status` ON `sync_outbox` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_created_at` ON `sync_outbox` (`created_at`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tracks` (`id` TEXT NOT NULL, `primary_artist_id` TEXT, `album_id` TEXT, `title` TEXT NOT NULL, `normalized_title` TEXT NOT NULL, `display_artist_name` TEXT NOT NULL, `display_album_title` TEXT, `duration_ms` INTEGER, `cover_uri` TEXT, `canonical_audio_source_type` TEXT NOT NULL, `is_liked` INTEGER NOT NULL, `is_downloaded_by_aura` INTEGER NOT NULL, `is_explicit` INTEGER, `popularity` INTEGER, `genres_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`primary_artist_id`) REFERENCES `artists`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`album_id`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_primary_artist_id` ON `tracks` (`primary_artist_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_album_id` ON `tracks` (`album_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_normalized_title` ON `tracks` (`normalized_title`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `track_likes` (`track_id` TEXT NOT NULL, `liked_at` INTEGER NOT NULL, `source_context_type` TEXT, `source_context_id` TEXT, PRIMARY KEY(`track_id`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE TABLE IF NOT EXISTS `track_media_links` (`id` TEXT NOT NULL, `track_id` TEXT NOT NULL, `media_store_id` INTEGER NOT NULL, `content_uri` TEXT NOT NULL, `file_size_bytes` INTEGER, `mime_type` TEXT, `date_modified_epoch_ms` INTEGER, `availability_status` TEXT NOT NULL, `last_scanned_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_track_media_links_track_id` ON `track_media_links` (`track_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_track_media_links_media_store_id` ON `track_media_links` (`media_store_id`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `user_settings` (`id` TEXT NOT NULL, `sync_enabled` INTEGER NOT NULL, `online_search_enabled` INTEGER NOT NULL, `online_search_network_policy` TEXT NOT NULL, `stats_sync_network_policy` TEXT NOT NULL, `last_sync_at` INTEGER, `sync_token` TEXT, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '4d1b83f1655de3314f9a83a8e92fd2f3')")
      }

      public override fun dropAllTables(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `albums`")
        db.execSQL("DROP TABLE IF EXISTS `album_source_links`")
        db.execSQL("DROP TABLE IF EXISTS `artists`")
        db.execSQL("DROP TABLE IF EXISTS `artist_source_links`")
        db.execSQL("DROP TABLE IF EXISTS `download_jobs`")
        db.execSQL("DROP TABLE IF EXISTS `playback_snapshots`")
        db.execSQL("DROP TABLE IF EXISTS `playlists`")
        db.execSQL("DROP TABLE IF EXISTS `playlist_items`")
        db.execSQL("DROP TABLE IF EXISTS `recent_searches`")
        db.execSQL("DROP TABLE IF EXISTS `sync_outbox`")
        db.execSQL("DROP TABLE IF EXISTS `tracks`")
        db.execSQL("DROP TABLE IF EXISTS `track_likes`")
        db.execSQL("DROP TABLE IF EXISTS `track_media_links`")
        db.execSQL("DROP TABLE IF EXISTS `user_settings`")
        val _callbacks: List<RoomDatabase.Callback>? = mCallbacks
        if (_callbacks != null) {
          for (_callback: RoomDatabase.Callback in _callbacks) {
            _callback.onDestructiveMigration(db)
          }
        }
      }

      public override fun onCreate(db: SupportSQLiteDatabase) {
        val _callbacks: List<RoomDatabase.Callback>? = mCallbacks
        if (_callbacks != null) {
          for (_callback: RoomDatabase.Callback in _callbacks) {
            _callback.onCreate(db)
          }
        }
      }

      public override fun onOpen(db: SupportSQLiteDatabase) {
        mDatabase = db
        db.execSQL("PRAGMA foreign_keys = ON")
        internalInitInvalidationTracker(db)
        val _callbacks: List<RoomDatabase.Callback>? = mCallbacks
        if (_callbacks != null) {
          for (_callback: RoomDatabase.Callback in _callbacks) {
            _callback.onOpen(db)
          }
        }
      }

      public override fun onPreMigrate(db: SupportSQLiteDatabase) {
        dropFtsSyncTriggers(db)
      }

      public override fun onPostMigrate(db: SupportSQLiteDatabase) {
      }

      public override fun onValidateSchema(db: SupportSQLiteDatabase):
          RoomOpenHelper.ValidationResult {
        val _columnsAlbums: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(11)
        _columnsAlbums.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("primary_artist_id", TableInfo.Column("primary_artist_id", "TEXT", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("title", TableInfo.Column("title", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("normalized_title", TableInfo.Column("normalized_title", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("cover_uri", TableInfo.Column("cover_uri", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("artwork_origin", TableInfo.Column("artwork_origin", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("artwork_last_resolved_at", TableInfo.Column("artwork_last_resolved_at",
            "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("release_date", TableInfo.Column("release_date", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("track_count", TableInfo.Column("track_count", "INTEGER", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbums.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAlbums: HashSet<TableInfo.ForeignKey> = HashSet<TableInfo.ForeignKey>(1)
        _foreignKeysAlbums.add(TableInfo.ForeignKey("artists", "SET NULL", "NO ACTION",
            listOf("primary_artist_id"), listOf("id")))
        val _indicesAlbums: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(2)
        _indicesAlbums.add(TableInfo.Index("index_albums_primary_artist_id", false,
            listOf("primary_artist_id"), listOf("ASC")))
        _indicesAlbums.add(TableInfo.Index("index_albums_normalized_title", false,
            listOf("normalized_title"), listOf("ASC")))
        val _infoAlbums: TableInfo = TableInfo("albums", _columnsAlbums, _foreignKeysAlbums,
            _indicesAlbums)
        val _existingAlbums: TableInfo = read(db, "albums")
        if (!_infoAlbums.equals(_existingAlbums)) {
          return RoomOpenHelper.ValidationResult(false, """
              |albums(com.aura.music.data.local.AlbumEntity).
              | Expected:
              |""".trimMargin() + _infoAlbums + """
              |
              | Found:
              |""".trimMargin() + _existingAlbums)
        }
        val _columnsAlbumSourceLinks: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(11)
        _columnsAlbumSourceLinks.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("album_id", TableInfo.Column("album_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("usage_type", TableInfo.Column("usage_type", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("provider_name", TableInfo.Column("provider_name", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("provider_album_id", TableInfo.Column("provider_album_id",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("provider_artist_id", TableInfo.Column("provider_artist_id",
            "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("match_score", TableInfo.Column("match_score", "REAL", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("is_active_for_usage", TableInfo.Column("is_active_for_usage",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("metadata_json", TableInfo.Column("metadata_json", "TEXT",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("created_at", TableInfo.Column("created_at", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAlbumSourceLinks.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAlbumSourceLinks: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(1)
        _foreignKeysAlbumSourceLinks.add(TableInfo.ForeignKey("albums", "CASCADE", "NO ACTION",
            listOf("album_id"), listOf("id")))
        val _indicesAlbumSourceLinks: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(3)
        _indicesAlbumSourceLinks.add(TableInfo.Index("index_album_source_links_album_id", false,
            listOf("album_id"), listOf("ASC")))
        _indicesAlbumSourceLinks.add(TableInfo.Index("index_album_source_links_usage_type", false,
            listOf("usage_type"), listOf("ASC")))
        _indicesAlbumSourceLinks.add(TableInfo.Index("index_album_source_links_provider_name",
            false, listOf("provider_name"), listOf("ASC")))
        val _infoAlbumSourceLinks: TableInfo = TableInfo("album_source_links",
            _columnsAlbumSourceLinks, _foreignKeysAlbumSourceLinks, _indicesAlbumSourceLinks)
        val _existingAlbumSourceLinks: TableInfo = read(db, "album_source_links")
        if (!_infoAlbumSourceLinks.equals(_existingAlbumSourceLinks)) {
          return RoomOpenHelper.ValidationResult(false, """
              |album_source_links(com.aura.music.data.local.AlbumSourceLinkEntity).
              | Expected:
              |""".trimMargin() + _infoAlbumSourceLinks + """
              |
              | Found:
              |""".trimMargin() + _existingAlbumSourceLinks)
        }
        val _columnsArtists: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(9)
        _columnsArtists.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("normalized_name", TableInfo.Column("normalized_name", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("picture_uri", TableInfo.Column("picture_uri", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("artwork_origin", TableInfo.Column("artwork_origin", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("artwork_last_resolved_at", TableInfo.Column("artwork_last_resolved_at",
            "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("summary", TableInfo.Column("summary", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsArtists.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysArtists: HashSet<TableInfo.ForeignKey> = HashSet<TableInfo.ForeignKey>(0)
        val _indicesArtists: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(2)
        _indicesArtists.add(TableInfo.Index("index_artists_name", false, listOf("name"),
            listOf("ASC")))
        _indicesArtists.add(TableInfo.Index("index_artists_normalized_name", false,
            listOf("normalized_name"), listOf("ASC")))
        val _infoArtists: TableInfo = TableInfo("artists", _columnsArtists, _foreignKeysArtists,
            _indicesArtists)
        val _existingArtists: TableInfo = read(db, "artists")
        if (!_infoArtists.equals(_existingArtists)) {
          return RoomOpenHelper.ValidationResult(false, """
              |artists(com.aura.music.data.local.ArtistEntity).
              | Expected:
              |""".trimMargin() + _infoArtists + """
              |
              | Found:
              |""".trimMargin() + _existingArtists)
        }
        val _columnsArtistSourceLinks: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(10)
        _columnsArtistSourceLinks.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("artist_id", TableInfo.Column("artist_id", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("usage_type", TableInfo.Column("usage_type", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("provider_name", TableInfo.Column("provider_name", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("provider_artist_id", TableInfo.Column("provider_artist_id",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("match_score", TableInfo.Column("match_score", "REAL", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("is_active_for_usage", TableInfo.Column("is_active_for_usage",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("metadata_json", TableInfo.Column("metadata_json", "TEXT",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("created_at", TableInfo.Column("created_at", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsArtistSourceLinks.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysArtistSourceLinks: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(1)
        _foreignKeysArtistSourceLinks.add(TableInfo.ForeignKey("artists", "CASCADE", "NO ACTION",
            listOf("artist_id"), listOf("id")))
        val _indicesArtistSourceLinks: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(3)
        _indicesArtistSourceLinks.add(TableInfo.Index("index_artist_source_links_artist_id", false,
            listOf("artist_id"), listOf("ASC")))
        _indicesArtistSourceLinks.add(TableInfo.Index("index_artist_source_links_usage_type", false,
            listOf("usage_type"), listOf("ASC")))
        _indicesArtistSourceLinks.add(TableInfo.Index("index_artist_source_links_provider_name",
            false, listOf("provider_name"), listOf("ASC")))
        val _infoArtistSourceLinks: TableInfo = TableInfo("artist_source_links",
            _columnsArtistSourceLinks, _foreignKeysArtistSourceLinks, _indicesArtistSourceLinks)
        val _existingArtistSourceLinks: TableInfo = read(db, "artist_source_links")
        if (!_infoArtistSourceLinks.equals(_existingArtistSourceLinks)) {
          return RoomOpenHelper.ValidationResult(false, """
              |artist_source_links(com.aura.music.data.local.ArtistSourceLinkEntity).
              | Expected:
              |""".trimMargin() + _infoArtistSourceLinks + """
              |
              | Found:
              |""".trimMargin() + _existingArtistSourceLinks)
        }
        val _columnsDownloadJobs: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(12)
        _columnsDownloadJobs.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("track_id", TableInfo.Column("track_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("provider_name", TableInfo.Column("provider_name", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("status", TableInfo.Column("status", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("progress_percent", TableInfo.Column("progress_percent", "REAL",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("error_code", TableInfo.Column("error_code", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("error_message", TableInfo.Column("error_message", "TEXT", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("attempt_count", TableInfo.Column("attempt_count", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("archived_in_cloud_at", TableInfo.Column("archived_in_cloud_at",
            "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsDownloadJobs.put("purge_after_at", TableInfo.Column("purge_after_at", "INTEGER",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysDownloadJobs: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(1)
        _foreignKeysDownloadJobs.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION",
            listOf("track_id"), listOf("id")))
        val _indicesDownloadJobs: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(2)
        _indicesDownloadJobs.add(TableInfo.Index("index_download_jobs_track_id", false,
            listOf("track_id"), listOf("ASC")))
        _indicesDownloadJobs.add(TableInfo.Index("index_download_jobs_status", false,
            listOf("status"), listOf("ASC")))
        val _infoDownloadJobs: TableInfo = TableInfo("download_jobs", _columnsDownloadJobs,
            _foreignKeysDownloadJobs, _indicesDownloadJobs)
        val _existingDownloadJobs: TableInfo = read(db, "download_jobs")
        if (!_infoDownloadJobs.equals(_existingDownloadJobs)) {
          return RoomOpenHelper.ValidationResult(false, """
              |download_jobs(com.aura.music.data.local.DownloadJobEntity).
              | Expected:
              |""".trimMargin() + _infoDownloadJobs + """
              |
              | Found:
              |""".trimMargin() + _existingDownloadJobs)
        }
        val _columnsPlaybackSnapshots: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(9)
        _columnsPlaybackSnapshots.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("current_track_id", TableInfo.Column("current_track_id",
            "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("playback_context_type",
            TableInfo.Column("playback_context_type", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("playback_context_id", TableInfo.Column("playback_context_id",
            "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("playback_context_index",
            TableInfo.Column("playback_context_index", "INTEGER", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("position_ms", TableInfo.Column("position_ms", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("shuffle_enabled", TableInfo.Column("shuffle_enabled",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("repeat_mode", TableInfo.Column("repeat_mode", "TEXT", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaybackSnapshots.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPlaybackSnapshots: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(0)
        val _indicesPlaybackSnapshots: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(0)
        val _infoPlaybackSnapshots: TableInfo = TableInfo("playback_snapshots",
            _columnsPlaybackSnapshots, _foreignKeysPlaybackSnapshots, _indicesPlaybackSnapshots)
        val _existingPlaybackSnapshots: TableInfo = read(db, "playback_snapshots")
        if (!_infoPlaybackSnapshots.equals(_existingPlaybackSnapshots)) {
          return RoomOpenHelper.ValidationResult(false, """
              |playback_snapshots(com.aura.music.data.local.PlaybackSnapshotEntity).
              | Expected:
              |""".trimMargin() + _infoPlaybackSnapshots + """
              |
              | Found:
              |""".trimMargin() + _existingPlaybackSnapshots)
        }
        val _columnsPlaylists: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(6)
        _columnsPlaylists.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("cover_uri", TableInfo.Column("cover_uri", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("is_pinned", TableInfo.Column("is_pinned", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylists.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPlaylists: HashSet<TableInfo.ForeignKey> = HashSet<TableInfo.ForeignKey>(0)
        val _indicesPlaylists: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(1)
        _indicesPlaylists.add(TableInfo.Index("index_playlists_name", false, listOf("name"),
            listOf("ASC")))
        val _infoPlaylists: TableInfo = TableInfo("playlists", _columnsPlaylists,
            _foreignKeysPlaylists, _indicesPlaylists)
        val _existingPlaylists: TableInfo = read(db, "playlists")
        if (!_infoPlaylists.equals(_existingPlaylists)) {
          return RoomOpenHelper.ValidationResult(false, """
              |playlists(com.aura.music.data.local.PlaylistEntity).
              | Expected:
              |""".trimMargin() + _infoPlaylists + """
              |
              | Found:
              |""".trimMargin() + _existingPlaylists)
        }
        val _columnsPlaylistItems: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(7)
        _columnsPlaylistItems.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("playlist_id", TableInfo.Column("playlist_id", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("track_id", TableInfo.Column("track_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("position", TableInfo.Column("position", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("added_at", TableInfo.Column("added_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("added_from_context_type",
            TableInfo.Column("added_from_context_type", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPlaylistItems.put("added_from_context_id", TableInfo.Column("added_from_context_id",
            "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPlaylistItems: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(2)
        _foreignKeysPlaylistItems.add(TableInfo.ForeignKey("playlists", "CASCADE", "NO ACTION",
            listOf("playlist_id"), listOf("id")))
        _foreignKeysPlaylistItems.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION",
            listOf("track_id"), listOf("id")))
        val _indicesPlaylistItems: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(2)
        _indicesPlaylistItems.add(TableInfo.Index("index_playlist_items_playlist_id_position", true,
            listOf("playlist_id", "position"), listOf("ASC", "ASC")))
        _indicesPlaylistItems.add(TableInfo.Index("index_playlist_items_track_id", false,
            listOf("track_id"), listOf("ASC")))
        val _infoPlaylistItems: TableInfo = TableInfo("playlist_items", _columnsPlaylistItems,
            _foreignKeysPlaylistItems, _indicesPlaylistItems)
        val _existingPlaylistItems: TableInfo = read(db, "playlist_items")
        if (!_infoPlaylistItems.equals(_existingPlaylistItems)) {
          return RoomOpenHelper.ValidationResult(false, """
              |playlist_items(com.aura.music.data.local.PlaylistItemEntity).
              | Expected:
              |""".trimMargin() + _infoPlaylistItems + """
              |
              | Found:
              |""".trimMargin() + _existingPlaylistItems)
        }
        val _columnsRecentSearches: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(3)
        _columnsRecentSearches.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsRecentSearches.put("query", TableInfo.Column("query", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsRecentSearches.put("searched_at", TableInfo.Column("searched_at", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysRecentSearches: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(0)
        val _indicesRecentSearches: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(2)
        _indicesRecentSearches.add(TableInfo.Index("index_recent_searches_query", true,
            listOf("query"), listOf("ASC")))
        _indicesRecentSearches.add(TableInfo.Index("index_recent_searches_searched_at", false,
            listOf("searched_at"), listOf("ASC")))
        val _infoRecentSearches: TableInfo = TableInfo("recent_searches", _columnsRecentSearches,
            _foreignKeysRecentSearches, _indicesRecentSearches)
        val _existingRecentSearches: TableInfo = read(db, "recent_searches")
        if (!_infoRecentSearches.equals(_existingRecentSearches)) {
          return RoomOpenHelper.ValidationResult(false, """
              |recent_searches(com.aura.music.data.local.RecentSearchEntity).
              | Expected:
              |""".trimMargin() + _infoRecentSearches + """
              |
              | Found:
              |""".trimMargin() + _existingRecentSearches)
        }
        val _columnsSyncOutbox: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(9)
        _columnsSyncOutbox.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("entity_type", TableInfo.Column("entity_type", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("entity_id", TableInfo.Column("entity_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("operation_type", TableInfo.Column("operation_type", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("payload_json", TableInfo.Column("payload_json", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("status", TableInfo.Column("status", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("attempt_count", TableInfo.Column("attempt_count", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsSyncOutbox.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysSyncOutbox: HashSet<TableInfo.ForeignKey> = HashSet<TableInfo.ForeignKey>(0)
        val _indicesSyncOutbox: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(2)
        _indicesSyncOutbox.add(TableInfo.Index("index_sync_outbox_status", false, listOf("status"),
            listOf("ASC")))
        _indicesSyncOutbox.add(TableInfo.Index("index_sync_outbox_created_at", false,
            listOf("created_at"), listOf("ASC")))
        val _infoSyncOutbox: TableInfo = TableInfo("sync_outbox", _columnsSyncOutbox,
            _foreignKeysSyncOutbox, _indicesSyncOutbox)
        val _existingSyncOutbox: TableInfo = read(db, "sync_outbox")
        if (!_infoSyncOutbox.equals(_existingSyncOutbox)) {
          return RoomOpenHelper.ValidationResult(false, """
              |sync_outbox(com.aura.music.data.local.SyncOutboxEntity).
              | Expected:
              |""".trimMargin() + _infoSyncOutbox + """
              |
              | Found:
              |""".trimMargin() + _existingSyncOutbox)
        }
        val _columnsTracks: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(17)
        _columnsTracks.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("primary_artist_id", TableInfo.Column("primary_artist_id", "TEXT", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("album_id", TableInfo.Column("album_id", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("title", TableInfo.Column("title", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("normalized_title", TableInfo.Column("normalized_title", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("display_artist_name", TableInfo.Column("display_artist_name", "TEXT",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("display_album_title", TableInfo.Column("display_album_title", "TEXT",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("duration_ms", TableInfo.Column("duration_ms", "INTEGER", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("cover_uri", TableInfo.Column("cover_uri", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("canonical_audio_source_type",
            TableInfo.Column("canonical_audio_source_type", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("is_liked", TableInfo.Column("is_liked", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("is_downloaded_by_aura", TableInfo.Column("is_downloaded_by_aura",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("is_explicit", TableInfo.Column("is_explicit", "INTEGER", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("popularity", TableInfo.Column("popularity", "INTEGER", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("genres_json", TableInfo.Column("genres_json", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTracks.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTracks: HashSet<TableInfo.ForeignKey> = HashSet<TableInfo.ForeignKey>(2)
        _foreignKeysTracks.add(TableInfo.ForeignKey("artists", "SET NULL", "NO ACTION",
            listOf("primary_artist_id"), listOf("id")))
        _foreignKeysTracks.add(TableInfo.ForeignKey("albums", "SET NULL", "NO ACTION",
            listOf("album_id"), listOf("id")))
        val _indicesTracks: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(3)
        _indicesTracks.add(TableInfo.Index("index_tracks_primary_artist_id", false,
            listOf("primary_artist_id"), listOf("ASC")))
        _indicesTracks.add(TableInfo.Index("index_tracks_album_id", false, listOf("album_id"),
            listOf("ASC")))
        _indicesTracks.add(TableInfo.Index("index_tracks_normalized_title", false,
            listOf("normalized_title"), listOf("ASC")))
        val _infoTracks: TableInfo = TableInfo("tracks", _columnsTracks, _foreignKeysTracks,
            _indicesTracks)
        val _existingTracks: TableInfo = read(db, "tracks")
        if (!_infoTracks.equals(_existingTracks)) {
          return RoomOpenHelper.ValidationResult(false, """
              |tracks(com.aura.music.data.local.TrackEntity).
              | Expected:
              |""".trimMargin() + _infoTracks + """
              |
              | Found:
              |""".trimMargin() + _existingTracks)
        }
        val _columnsTrackLikes: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(4)
        _columnsTrackLikes.put("track_id", TableInfo.Column("track_id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackLikes.put("liked_at", TableInfo.Column("liked_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackLikes.put("source_context_type", TableInfo.Column("source_context_type",
            "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackLikes.put("source_context_id", TableInfo.Column("source_context_id", "TEXT",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTrackLikes: HashSet<TableInfo.ForeignKey> = HashSet<TableInfo.ForeignKey>(1)
        _foreignKeysTrackLikes.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION",
            listOf("track_id"), listOf("id")))
        val _indicesTrackLikes: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(0)
        val _infoTrackLikes: TableInfo = TableInfo("track_likes", _columnsTrackLikes,
            _foreignKeysTrackLikes, _indicesTrackLikes)
        val _existingTrackLikes: TableInfo = read(db, "track_likes")
        if (!_infoTrackLikes.equals(_existingTrackLikes)) {
          return RoomOpenHelper.ValidationResult(false, """
              |track_likes(com.aura.music.data.local.TrackLikeEntity).
              | Expected:
              |""".trimMargin() + _infoTrackLikes + """
              |
              | Found:
              |""".trimMargin() + _existingTrackLikes)
        }
        val _columnsTrackMediaLinks: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(9)
        _columnsTrackMediaLinks.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("track_id", TableInfo.Column("track_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("media_store_id", TableInfo.Column("media_store_id", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("content_uri", TableInfo.Column("content_uri", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("file_size_bytes", TableInfo.Column("file_size_bytes",
            "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("mime_type", TableInfo.Column("mime_type", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("date_modified_epoch_ms",
            TableInfo.Column("date_modified_epoch_ms", "INTEGER", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("availability_status", TableInfo.Column("availability_status",
            "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTrackMediaLinks.put("last_scanned_at", TableInfo.Column("last_scanned_at",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTrackMediaLinks: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(1)
        _foreignKeysTrackMediaLinks.add(TableInfo.ForeignKey("tracks", "CASCADE", "NO ACTION",
            listOf("track_id"), listOf("id")))
        val _indicesTrackMediaLinks: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(2)
        _indicesTrackMediaLinks.add(TableInfo.Index("index_track_media_links_track_id", true,
            listOf("track_id"), listOf("ASC")))
        _indicesTrackMediaLinks.add(TableInfo.Index("index_track_media_links_media_store_id", true,
            listOf("media_store_id"), listOf("ASC")))
        val _infoTrackMediaLinks: TableInfo = TableInfo("track_media_links",
            _columnsTrackMediaLinks, _foreignKeysTrackMediaLinks, _indicesTrackMediaLinks)
        val _existingTrackMediaLinks: TableInfo = read(db, "track_media_links")
        if (!_infoTrackMediaLinks.equals(_existingTrackMediaLinks)) {
          return RoomOpenHelper.ValidationResult(false, """
              |track_media_links(com.aura.music.data.local.TrackMediaLinkEntity).
              | Expected:
              |""".trimMargin() + _infoTrackMediaLinks + """
              |
              | Found:
              |""".trimMargin() + _existingTrackMediaLinks)
        }
        val _columnsUserSettings: HashMap<String, TableInfo.Column> =
            HashMap<String, TableInfo.Column>(7)
        _columnsUserSettings.put("id", TableInfo.Column("id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("sync_enabled", TableInfo.Column("sync_enabled", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("online_search_enabled", TableInfo.Column("online_search_enabled",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("online_search_network_policy",
            TableInfo.Column("online_search_network_policy", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("stats_sync_network_policy",
            TableInfo.Column("stats_sync_network_policy", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("last_sync_at", TableInfo.Column("last_sync_at", "INTEGER", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsUserSettings.put("sync_token", TableInfo.Column("sync_token", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysUserSettings: HashSet<TableInfo.ForeignKey> =
            HashSet<TableInfo.ForeignKey>(0)
        val _indicesUserSettings: HashSet<TableInfo.Index> = HashSet<TableInfo.Index>(0)
        val _infoUserSettings: TableInfo = TableInfo("user_settings", _columnsUserSettings,
            _foreignKeysUserSettings, _indicesUserSettings)
        val _existingUserSettings: TableInfo = read(db, "user_settings")
        if (!_infoUserSettings.equals(_existingUserSettings)) {
          return RoomOpenHelper.ValidationResult(false, """
              |user_settings(com.aura.music.data.local.UserSettingsEntity).
              | Expected:
              |""".trimMargin() + _infoUserSettings + """
              |
              | Found:
              |""".trimMargin() + _existingUserSettings)
        }
        return RoomOpenHelper.ValidationResult(true, null)
      }
    }, "4d1b83f1655de3314f9a83a8e92fd2f3", "b917a564ac42ec64327f4fc281005ba0")
    val _sqliteConfig: SupportSQLiteOpenHelper.Configuration =
        SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build()
    val _helper: SupportSQLiteOpenHelper = config.sqliteOpenHelperFactory.create(_sqliteConfig)
    return _helper
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: HashMap<String, String> = HashMap<String, String>(0)
    val _viewTables: HashMap<String, Set<String>> = HashMap<String, Set<String>>(0)
    return InvalidationTracker(this, _shadowTablesMap, _viewTables,
        "albums","album_source_links","artists","artist_source_links","download_jobs","playback_snapshots","playlists","playlist_items","recent_searches","sync_outbox","tracks","track_likes","track_media_links","user_settings")
  }

  public override fun clearAllTables() {
    super.assertNotMainThread()
    val _db: SupportSQLiteDatabase = super.openHelper.writableDatabase
    val _supportsDeferForeignKeys: Boolean = android.os.Build.VERSION.SDK_INT >=
        android.os.Build.VERSION_CODES.LOLLIPOP
    try {
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = FALSE")
      }
      super.beginTransaction()
      if (_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA defer_foreign_keys = TRUE")
      }
      _db.execSQL("DELETE FROM `albums`")
      _db.execSQL("DELETE FROM `album_source_links`")
      _db.execSQL("DELETE FROM `artists`")
      _db.execSQL("DELETE FROM `artist_source_links`")
      _db.execSQL("DELETE FROM `download_jobs`")
      _db.execSQL("DELETE FROM `playback_snapshots`")
      _db.execSQL("DELETE FROM `playlists`")
      _db.execSQL("DELETE FROM `playlist_items`")
      _db.execSQL("DELETE FROM `recent_searches`")
      _db.execSQL("DELETE FROM `sync_outbox`")
      _db.execSQL("DELETE FROM `tracks`")
      _db.execSQL("DELETE FROM `track_likes`")
      _db.execSQL("DELETE FROM `track_media_links`")
      _db.execSQL("DELETE FROM `user_settings`")
      super.setTransactionSuccessful()
    } finally {
      super.endTransaction()
      if (!_supportsDeferForeignKeys) {
        _db.execSQL("PRAGMA foreign_keys = TRUE")
      }
      _db.query("PRAGMA wal_checkpoint(FULL)").close()
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM")
      }
    }
  }

  protected override fun getRequiredTypeConverters(): Map<Class<out Any>, List<Class<out Any>>> {
    val _typeConvertersMap: HashMap<Class<out Any>, List<Class<out Any>>> =
        HashMap<Class<out Any>, List<Class<out Any>>>()
    _typeConvertersMap.put(ArtistDao::class.java, ArtistDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AlbumDao::class.java, AlbumDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(TrackDao::class.java, TrackDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(TrackLikeDao::class.java, TrackLikeDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PlaylistDao::class.java, PlaylistDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(PlaybackSnapshotDao::class.java,
        PlaybackSnapshotDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(RecentSearchDao::class.java,
        RecentSearchDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(UserSettingsDao::class.java,
        UserSettingsDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ArtistSourceLinkDao::class.java,
        ArtistSourceLinkDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AlbumSourceLinkDao::class.java,
        AlbumSourceLinkDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(DownloadJobDao::class.java, DownloadJobDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(SyncOutboxDao::class.java, SyncOutboxDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecs(): Set<Class<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: HashSet<Class<out AutoMigrationSpec>> =
        HashSet<Class<out AutoMigrationSpec>>()
    return _autoMigrationSpecsSet
  }

  public override
      fun getAutoMigrations(autoMigrationSpecs: Map<Class<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = ArrayList<Migration>()
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
