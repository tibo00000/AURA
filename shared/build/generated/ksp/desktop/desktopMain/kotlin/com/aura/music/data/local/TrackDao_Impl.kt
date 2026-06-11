package com.aura.music.`data`.local

import androidx.room3.EntityDeleteOrUpdateAdapter
import androidx.room3.EntityInsertAdapter
import androidx.room3.EntityUpsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.appendPlaceholders
import androidx.room3.util.getColumnIndexOrThrow
import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.prepare
import androidx.sqlite.step
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlin.text.StringBuilder

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class TrackDao_Impl(
  __db: RoomDatabase,
) : TrackDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfTrackEntity: EntityUpsertAdapter<TrackEntity>

  private val __upsertAdapterOfTrackMediaLinkEntity: EntityUpsertAdapter<TrackMediaLinkEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfTrackEntity = EntityUpsertAdapter<TrackEntity>(object : EntityInsertAdapter<TrackEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `tracks` (`id`,`primary_artist_id`,`album_id`,`title`,`normalized_title`,`display_artist_name`,`display_album_title`,`duration_ms`,`cover_uri`,`canonical_audio_source_type`,`is_liked`,`is_downloaded_by_aura`,`is_explicit`,`popularity`,`genres_json`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: TrackEntity) {
        statement.bindText(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpPrimaryArtistId)
        }
        val _tmpAlbumId: String? = entity.albumId
        if (_tmpAlbumId == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpAlbumId)
        }
        statement.bindText(4, entity.title)
        statement.bindText(5, entity.normalizedTitle)
        statement.bindText(6, entity.displayArtistName)
        val _tmpDisplayAlbumTitle: String? = entity.displayAlbumTitle
        if (_tmpDisplayAlbumTitle == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpDisplayAlbumTitle)
        }
        val _tmpDurationMs: Long? = entity.durationMs
        if (_tmpDurationMs == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmpDurationMs)
        }
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpCoverUri)
        }
        statement.bindText(10, entity.canonicalAudioSourceType)
        val _tmp: Int = if (entity.isLiked) 1 else 0
        statement.bindLong(11, _tmp.toLong())
        val _tmp_1: Int = if (entity.isDownloadedByAura) 1 else 0
        statement.bindLong(12, _tmp_1.toLong())
        val _tmpIsExplicit: Boolean? = entity.isExplicit
        val _tmp_2: Int? = _tmpIsExplicit?.let { if (it) 1 else 0 }
        if (_tmp_2 == null) {
          statement.bindNull(13)
        } else {
          statement.bindLong(13, _tmp_2.toLong())
        }
        val _tmpPopularity: Int? = entity.popularity
        if (_tmpPopularity == null) {
          statement.bindNull(14)
        } else {
          statement.bindLong(14, _tmpPopularity.toLong())
        }
        val _tmpGenresJson: String? = entity.genresJson
        if (_tmpGenresJson == null) {
          statement.bindNull(15)
        } else {
          statement.bindText(15, _tmpGenresJson)
        }
        statement.bindLong(16, entity.createdAt)
        statement.bindLong(17, entity.updatedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<TrackEntity>() {
      protected override fun createQuery(): String = "UPDATE `tracks` SET `id` = ?,`primary_artist_id` = ?,`album_id` = ?,`title` = ?,`normalized_title` = ?,`display_artist_name` = ?,`display_album_title` = ?,`duration_ms` = ?,`cover_uri` = ?,`canonical_audio_source_type` = ?,`is_liked` = ?,`is_downloaded_by_aura` = ?,`is_explicit` = ?,`popularity` = ?,`genres_json` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: TrackEntity) {
        statement.bindText(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpPrimaryArtistId)
        }
        val _tmpAlbumId: String? = entity.albumId
        if (_tmpAlbumId == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpAlbumId)
        }
        statement.bindText(4, entity.title)
        statement.bindText(5, entity.normalizedTitle)
        statement.bindText(6, entity.displayArtistName)
        val _tmpDisplayAlbumTitle: String? = entity.displayAlbumTitle
        if (_tmpDisplayAlbumTitle == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpDisplayAlbumTitle)
        }
        val _tmpDurationMs: Long? = entity.durationMs
        if (_tmpDurationMs == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmpDurationMs)
        }
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpCoverUri)
        }
        statement.bindText(10, entity.canonicalAudioSourceType)
        val _tmp: Int = if (entity.isLiked) 1 else 0
        statement.bindLong(11, _tmp.toLong())
        val _tmp_1: Int = if (entity.isDownloadedByAura) 1 else 0
        statement.bindLong(12, _tmp_1.toLong())
        val _tmpIsExplicit: Boolean? = entity.isExplicit
        val _tmp_2: Int? = _tmpIsExplicit?.let { if (it) 1 else 0 }
        if (_tmp_2 == null) {
          statement.bindNull(13)
        } else {
          statement.bindLong(13, _tmp_2.toLong())
        }
        val _tmpPopularity: Int? = entity.popularity
        if (_tmpPopularity == null) {
          statement.bindNull(14)
        } else {
          statement.bindLong(14, _tmpPopularity.toLong())
        }
        val _tmpGenresJson: String? = entity.genresJson
        if (_tmpGenresJson == null) {
          statement.bindNull(15)
        } else {
          statement.bindText(15, _tmpGenresJson)
        }
        statement.bindLong(16, entity.createdAt)
        statement.bindLong(17, entity.updatedAt)
        statement.bindText(18, entity.id)
      }
    })
    this.__upsertAdapterOfTrackMediaLinkEntity = EntityUpsertAdapter<TrackMediaLinkEntity>(object : EntityInsertAdapter<TrackMediaLinkEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `track_media_links` (`id`,`track_id`,`media_store_id`,`content_uri`,`file_size_bytes`,`mime_type`,`date_modified_epoch_ms`,`availability_status`,`last_scanned_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: TrackMediaLinkEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.trackId)
        statement.bindLong(3, entity.mediaStoreId)
        statement.bindText(4, entity.contentUri)
        val _tmpFileSizeBytes: Long? = entity.fileSizeBytes
        if (_tmpFileSizeBytes == null) {
          statement.bindNull(5)
        } else {
          statement.bindLong(5, _tmpFileSizeBytes)
        }
        val _tmpMimeType: String? = entity.mimeType
        if (_tmpMimeType == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpMimeType)
        }
        val _tmpDateModifiedEpochMs: Long? = entity.dateModifiedEpochMs
        if (_tmpDateModifiedEpochMs == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpDateModifiedEpochMs)
        }
        statement.bindText(8, entity.availabilityStatus)
        statement.bindLong(9, entity.lastScannedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<TrackMediaLinkEntity>() {
      protected override fun createQuery(): String = "UPDATE `track_media_links` SET `id` = ?,`track_id` = ?,`media_store_id` = ?,`content_uri` = ?,`file_size_bytes` = ?,`mime_type` = ?,`date_modified_epoch_ms` = ?,`availability_status` = ?,`last_scanned_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: TrackMediaLinkEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.trackId)
        statement.bindLong(3, entity.mediaStoreId)
        statement.bindText(4, entity.contentUri)
        val _tmpFileSizeBytes: Long? = entity.fileSizeBytes
        if (_tmpFileSizeBytes == null) {
          statement.bindNull(5)
        } else {
          statement.bindLong(5, _tmpFileSizeBytes)
        }
        val _tmpMimeType: String? = entity.mimeType
        if (_tmpMimeType == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpMimeType)
        }
        val _tmpDateModifiedEpochMs: Long? = entity.dateModifiedEpochMs
        if (_tmpDateModifiedEpochMs == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpDateModifiedEpochMs)
        }
        statement.bindText(8, entity.availabilityStatus)
        statement.bindLong(9, entity.lastScannedAt)
        statement.bindText(10, entity.id)
      }
    })
  }

  public override suspend fun upsertTracks(items: List<TrackEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfTrackEntity.upsert(_connection, items)
  }

  public override suspend fun upsertTrack(item: TrackEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfTrackEntity.upsert(_connection, item)
  }

  public override suspend fun upsertTrackMediaLinks(items: List<TrackMediaLinkEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfTrackMediaLinkEntity.upsert(_connection, items)
  }

  public override suspend fun getRawTrackById(trackId: String): TrackEntity? {
    val _sql: String = "SELECT * FROM tracks WHERE id = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, trackId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfPrimaryArtistId: Int = getColumnIndexOrThrow(_stmt, "primary_artist_id")
        val _columnIndexOfAlbumId: Int = getColumnIndexOrThrow(_stmt, "album_id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfNormalizedTitle: Int = getColumnIndexOrThrow(_stmt, "normalized_title")
        val _columnIndexOfDisplayArtistName: Int = getColumnIndexOrThrow(_stmt, "display_artist_name")
        val _columnIndexOfDisplayAlbumTitle: Int = getColumnIndexOrThrow(_stmt, "display_album_title")
        val _columnIndexOfDurationMs: Int = getColumnIndexOrThrow(_stmt, "duration_ms")
        val _columnIndexOfCoverUri: Int = getColumnIndexOrThrow(_stmt, "cover_uri")
        val _columnIndexOfCanonicalAudioSourceType: Int = getColumnIndexOrThrow(_stmt, "canonical_audio_source_type")
        val _columnIndexOfIsLiked: Int = getColumnIndexOrThrow(_stmt, "is_liked")
        val _columnIndexOfIsDownloadedByAura: Int = getColumnIndexOrThrow(_stmt, "is_downloaded_by_aura")
        val _columnIndexOfIsExplicit: Int = getColumnIndexOrThrow(_stmt, "is_explicit")
        val _columnIndexOfPopularity: Int = getColumnIndexOrThrow(_stmt, "popularity")
        val _columnIndexOfGenresJson: Int = getColumnIndexOrThrow(_stmt, "genres_json")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: TrackEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpPrimaryArtistId: String?
          if (_stmt.isNull(_columnIndexOfPrimaryArtistId)) {
            _tmpPrimaryArtistId = null
          } else {
            _tmpPrimaryArtistId = _stmt.getText(_columnIndexOfPrimaryArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpNormalizedTitle: String
          _tmpNormalizedTitle = _stmt.getText(_columnIndexOfNormalizedTitle)
          val _tmpDisplayArtistName: String
          _tmpDisplayArtistName = _stmt.getText(_columnIndexOfDisplayArtistName)
          val _tmpDisplayAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfDisplayAlbumTitle)) {
            _tmpDisplayAlbumTitle = null
          } else {
            _tmpDisplayAlbumTitle = _stmt.getText(_columnIndexOfDisplayAlbumTitle)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpCanonicalAudioSourceType: String
          _tmpCanonicalAudioSourceType = _stmt.getText(_columnIndexOfCanonicalAudioSourceType)
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpIsDownloadedByAura: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsDownloadedByAura).toInt()
          _tmpIsDownloadedByAura = _tmp_1 != 0
          val _tmpIsExplicit: Boolean?
          val _tmp_2: Int?
          if (_stmt.isNull(_columnIndexOfIsExplicit)) {
            _tmp_2 = null
          } else {
            _tmp_2 = _stmt.getLong(_columnIndexOfIsExplicit).toInt()
          }
          _tmpIsExplicit = _tmp_2?.let { it != 0 }
          val _tmpPopularity: Int?
          if (_stmt.isNull(_columnIndexOfPopularity)) {
            _tmpPopularity = null
          } else {
            _tmpPopularity = _stmt.getLong(_columnIndexOfPopularity).toInt()
          }
          val _tmpGenresJson: String?
          if (_stmt.isNull(_columnIndexOfGenresJson)) {
            _tmpGenresJson = null
          } else {
            _tmpGenresJson = _stmt.getText(_columnIndexOfGenresJson)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = TrackEntity(_tmpId,_tmpPrimaryArtistId,_tmpAlbumId,_tmpTitle,_tmpNormalizedTitle,_tmpDisplayArtistName,_tmpDisplayAlbumTitle,_tmpDurationMs,_tmpCoverUri,_tmpCanonicalAudioSourceType,_tmpIsLiked,_tmpIsDownloadedByAura,_tmpIsExplicit,_tmpPopularity,_tmpGenresJson,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTrackCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM tracks"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getLocalTrackIds(): List<String> {
    val _sql: String = "SELECT id FROM tracks WHERE canonical_audio_source_type = 'local'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getDownloadedTrackIds(): List<String> {
    val _sql: String = "SELECT id FROM tracks WHERE canonical_audio_source_type = 'downloaded'"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRecentTracks(limit: Int): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        ORDER BY tracks.created_at DESC
        |        LIMIT ?
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTrackById(trackId: String): TrackListRow? {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        WHERE tracks.id = ?
        |        LIMIT 1
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, trackId)
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: TrackListRow?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun searchTracks(query: String, limit: Int): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        WHERE lower(tracks.title) LIKE '%' || lower(?) || '%'
        |           OR lower(tracks.display_artist_name) LIKE '%' || lower(?) || '%'
        |           OR lower(COALESCE(tracks.display_album_title, '')) LIKE '%' || lower(?) || '%'
        |        ORDER BY tracks.created_at DESC
        |        LIMIT ?
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, query)
        _argIndex = 2
        _stmt.bindText(_argIndex, query)
        _argIndex = 3
        _stmt.bindText(_argIndex, query)
        _argIndex = 4
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllTracks(): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        ORDER BY lower(tracks.display_artist_name) ASC, lower(tracks.title) ASC
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTracksForArtist(artistId: String, limit: Int): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        WHERE tracks.primary_artist_id = ?
        |        ORDER BY tracks.created_at DESC, tracks.title ASC
        |        LIMIT ?
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, artistId)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTracksForAlbum(albumId: String): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        WHERE tracks.album_id = ?
        |        ORDER BY tracks.title ASC, tracks.created_at DESC
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, albumId)
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTracksForAlbumByText(albumTitle: String, artistName: String): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        WHERE lower(tracks.display_album_title) = lower(?)
        |          AND lower(tracks.display_artist_name) = lower(?)
        |        ORDER BY tracks.title ASC, tracks.created_at DESC
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, albumTitle)
        _argIndex = 2
        _stmt.bindText(_argIndex, artistName)
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getLikedTracks(): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        INNER JOIN track_likes ON track_likes.track_id = tracks.id
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        ORDER BY track_likes.liked_at DESC
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getDownloadedTracks(): List<TrackListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            tracks.id AS id,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.is_liked AS is_liked,
        |            tracks.created_at AS created_at,
        |            tracks.updated_at AS updated_at
        |        FROM tracks
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        WHERE tracks.canonical_audio_source_type = 'downloaded'
        |        ORDER BY tracks.created_at DESC
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = 0
        val _columnIndexOfArtistId: Int = 1
        val _columnIndexOfAlbumId: Int = 2
        val _columnIndexOfTitle: Int = 3
        val _columnIndexOfArtistName: Int = 4
        val _columnIndexOfAlbumTitle: Int = 5
        val _columnIndexOfContentUri: Int = 6
        val _columnIndexOfDurationMs: Int = 7
        val _columnIndexOfCoverUri: Int = 8
        val _columnIndexOfIsLiked: Int = 9
        val _columnIndexOfCreatedAt: Int = 10
        val _columnIndexOfUpdatedAt: Int = 11
        val _result: MutableList<TrackListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: TrackListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpAlbumId: String?
          if (_stmt.isNull(_columnIndexOfAlbumId)) {
            _tmpAlbumId = null
          } else {
            _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          }
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpAlbumTitle: String?
          if (_stmt.isNull(_columnIndexOfAlbumTitle)) {
            _tmpAlbumTitle = null
          } else {
            _tmpAlbumTitle = _stmt.getText(_columnIndexOfAlbumTitle)
          }
          val _tmpContentUri: String?
          if (_stmt.isNull(_columnIndexOfContentUri)) {
            _tmpContentUri = null
          } else {
            _tmpContentUri = _stmt.getText(_columnIndexOfContentUri)
          }
          val _tmpDurationMs: Long?
          if (_stmt.isNull(_columnIndexOfDurationMs)) {
            _tmpDurationMs = null
          } else {
            _tmpDurationMs = _stmt.getLong(_columnIndexOfDurationMs)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteTracksByIds(ids: List<String>) {
    val _stringBuilder: StringBuilder = StringBuilder()
    _stringBuilder.append("DELETE FROM tracks WHERE id IN (")
    val _inputSize: Int = ids.size
    appendPlaceholders(_stringBuilder, _inputSize)
    _stringBuilder.append(")")
    val _sql: String = _stringBuilder.toString()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        for (_item: String in ids) {
          _stmt.bindText(_argIndex, _item)
          _argIndex++
        }
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
