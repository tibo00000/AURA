package com.aura.music.`data`.local

import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.CoroutinesRoom
import androidx.room.CoroutinesRoom.Companion.execute
import androidx.room.EntityDeletionOrUpdateAdapter
import androidx.room.EntityInsertionAdapter
import androidx.room.EntityUpsertionAdapter
import androidx.room.RoomDatabase
import androidx.room.RoomSQLiteQuery
import androidx.room.RoomSQLiteQuery.Companion.acquire
import androidx.room.util.appendPlaceholders
import androidx.room.util.createCancellationSignal
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.newStringBuilder
import androidx.room.util.query
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.Class
import java.lang.StringBuilder
import java.util.ArrayList
import java.util.concurrent.Callable
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.jvm.JvmStatic

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class TrackDao_Impl(
  __db: RoomDatabase,
) : TrackDao {
  private val __db: RoomDatabase

  private val __upsertionAdapterOfTrackEntity: EntityUpsertionAdapter<TrackEntity>

  private val __upsertionAdapterOfTrackMediaLinkEntity: EntityUpsertionAdapter<TrackMediaLinkEntity>
  init {
    this.__db = __db
    this.__upsertionAdapterOfTrackEntity = EntityUpsertionAdapter<TrackEntity>(object :
        EntityInsertionAdapter<TrackEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT INTO `tracks` (`id`,`primary_artist_id`,`album_id`,`title`,`normalized_title`,`display_artist_name`,`display_album_title`,`duration_ms`,`cover_uri`,`canonical_audio_source_type`,`is_liked`,`is_downloaded_by_aura`,`is_explicit`,`popularity`,`genres_json`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: TrackEntity) {
        statement.bindString(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindString(2, _tmpPrimaryArtistId)
        }
        val _tmpAlbumId: String? = entity.albumId
        if (_tmpAlbumId == null) {
          statement.bindNull(3)
        } else {
          statement.bindString(3, _tmpAlbumId)
        }
        statement.bindString(4, entity.title)
        statement.bindString(5, entity.normalizedTitle)
        statement.bindString(6, entity.displayArtistName)
        val _tmpDisplayAlbumTitle: String? = entity.displayAlbumTitle
        if (_tmpDisplayAlbumTitle == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpDisplayAlbumTitle)
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
          statement.bindString(9, _tmpCoverUri)
        }
        statement.bindString(10, entity.canonicalAudioSourceType)
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
          statement.bindString(15, _tmpGenresJson)
        }
        statement.bindLong(16, entity.createdAt)
        statement.bindLong(17, entity.updatedAt)
      }
    }, object : EntityDeletionOrUpdateAdapter<TrackEntity>(__db) {
      protected override fun createQuery(): String =
          "UPDATE `tracks` SET `id` = ?,`primary_artist_id` = ?,`album_id` = ?,`title` = ?,`normalized_title` = ?,`display_artist_name` = ?,`display_album_title` = ?,`duration_ms` = ?,`cover_uri` = ?,`canonical_audio_source_type` = ?,`is_liked` = ?,`is_downloaded_by_aura` = ?,`is_explicit` = ?,`popularity` = ?,`genres_json` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SupportSQLiteStatement, entity: TrackEntity) {
        statement.bindString(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindString(2, _tmpPrimaryArtistId)
        }
        val _tmpAlbumId: String? = entity.albumId
        if (_tmpAlbumId == null) {
          statement.bindNull(3)
        } else {
          statement.bindString(3, _tmpAlbumId)
        }
        statement.bindString(4, entity.title)
        statement.bindString(5, entity.normalizedTitle)
        statement.bindString(6, entity.displayArtistName)
        val _tmpDisplayAlbumTitle: String? = entity.displayAlbumTitle
        if (_tmpDisplayAlbumTitle == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpDisplayAlbumTitle)
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
          statement.bindString(9, _tmpCoverUri)
        }
        statement.bindString(10, entity.canonicalAudioSourceType)
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
          statement.bindString(15, _tmpGenresJson)
        }
        statement.bindLong(16, entity.createdAt)
        statement.bindLong(17, entity.updatedAt)
        statement.bindString(18, entity.id)
      }
    })
    this.__upsertionAdapterOfTrackMediaLinkEntity =
        EntityUpsertionAdapter<TrackMediaLinkEntity>(object :
        EntityInsertionAdapter<TrackMediaLinkEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT INTO `track_media_links` (`id`,`track_id`,`media_store_id`,`content_uri`,`file_size_bytes`,`mime_type`,`date_modified_epoch_ms`,`availability_status`,`last_scanned_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: TrackMediaLinkEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.trackId)
        statement.bindLong(3, entity.mediaStoreId)
        statement.bindString(4, entity.contentUri)
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
          statement.bindString(6, _tmpMimeType)
        }
        val _tmpDateModifiedEpochMs: Long? = entity.dateModifiedEpochMs
        if (_tmpDateModifiedEpochMs == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpDateModifiedEpochMs)
        }
        statement.bindString(8, entity.availabilityStatus)
        statement.bindLong(9, entity.lastScannedAt)
      }
    }, object : EntityDeletionOrUpdateAdapter<TrackMediaLinkEntity>(__db) {
      protected override fun createQuery(): String =
          "UPDATE `track_media_links` SET `id` = ?,`track_id` = ?,`media_store_id` = ?,`content_uri` = ?,`file_size_bytes` = ?,`mime_type` = ?,`date_modified_epoch_ms` = ?,`availability_status` = ?,`last_scanned_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SupportSQLiteStatement, entity: TrackMediaLinkEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.trackId)
        statement.bindLong(3, entity.mediaStoreId)
        statement.bindString(4, entity.contentUri)
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
          statement.bindString(6, _tmpMimeType)
        }
        val _tmpDateModifiedEpochMs: Long? = entity.dateModifiedEpochMs
        if (_tmpDateModifiedEpochMs == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpDateModifiedEpochMs)
        }
        statement.bindString(8, entity.availabilityStatus)
        statement.bindLong(9, entity.lastScannedAt)
        statement.bindString(10, entity.id)
      }
    })
  }

  public override suspend fun upsertTracks(items: List<TrackEntity>): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfTrackEntity.upsert(items)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun upsertTrack(item: TrackEntity): Unit = CoroutinesRoom.execute(__db,
      true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfTrackEntity.upsert(item)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun upsertTrackMediaLinks(items: List<TrackMediaLinkEntity>): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfTrackMediaLinkEntity.upsert(items)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun getRawTrackById(trackId: String): TrackEntity? {
    val _sql: String = "SELECT * FROM tracks WHERE id = ? LIMIT 1"
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, trackId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<TrackEntity?> {
      public override fun call(): TrackEntity? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfPrimaryArtistId: Int = getColumnIndexOrThrow(_cursor,
              "primary_artist_id")
          val _cursorIndexOfAlbumId: Int = getColumnIndexOrThrow(_cursor, "album_id")
          val _cursorIndexOfTitle: Int = getColumnIndexOrThrow(_cursor, "title")
          val _cursorIndexOfNormalizedTitle: Int = getColumnIndexOrThrow(_cursor,
              "normalized_title")
          val _cursorIndexOfDisplayArtistName: Int = getColumnIndexOrThrow(_cursor,
              "display_artist_name")
          val _cursorIndexOfDisplayAlbumTitle: Int = getColumnIndexOrThrow(_cursor,
              "display_album_title")
          val _cursorIndexOfDurationMs: Int = getColumnIndexOrThrow(_cursor, "duration_ms")
          val _cursorIndexOfCoverUri: Int = getColumnIndexOrThrow(_cursor, "cover_uri")
          val _cursorIndexOfCanonicalAudioSourceType: Int = getColumnIndexOrThrow(_cursor,
              "canonical_audio_source_type")
          val _cursorIndexOfIsLiked: Int = getColumnIndexOrThrow(_cursor, "is_liked")
          val _cursorIndexOfIsDownloadedByAura: Int = getColumnIndexOrThrow(_cursor,
              "is_downloaded_by_aura")
          val _cursorIndexOfIsExplicit: Int = getColumnIndexOrThrow(_cursor, "is_explicit")
          val _cursorIndexOfPopularity: Int = getColumnIndexOrThrow(_cursor, "popularity")
          val _cursorIndexOfGenresJson: Int = getColumnIndexOrThrow(_cursor, "genres_json")
          val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_cursor, "created_at")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _result: TrackEntity?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpPrimaryArtistId: String?
            if (_cursor.isNull(_cursorIndexOfPrimaryArtistId)) {
              _tmpPrimaryArtistId = null
            } else {
              _tmpPrimaryArtistId = _cursor.getString(_cursorIndexOfPrimaryArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpNormalizedTitle: String
            _tmpNormalizedTitle = _cursor.getString(_cursorIndexOfNormalizedTitle)
            val _tmpDisplayArtistName: String
            _tmpDisplayArtistName = _cursor.getString(_cursorIndexOfDisplayArtistName)
            val _tmpDisplayAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfDisplayAlbumTitle)) {
              _tmpDisplayAlbumTitle = null
            } else {
              _tmpDisplayAlbumTitle = _cursor.getString(_cursorIndexOfDisplayAlbumTitle)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpCanonicalAudioSourceType: String
            _tmpCanonicalAudioSourceType = _cursor.getString(_cursorIndexOfCanonicalAudioSourceType)
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpIsDownloadedByAura: Boolean
            val _tmp_1: Int
            _tmp_1 = _cursor.getInt(_cursorIndexOfIsDownloadedByAura)
            _tmpIsDownloadedByAura = _tmp_1 != 0
            val _tmpIsExplicit: Boolean?
            val _tmp_2: Int?
            if (_cursor.isNull(_cursorIndexOfIsExplicit)) {
              _tmp_2 = null
            } else {
              _tmp_2 = _cursor.getInt(_cursorIndexOfIsExplicit)
            }
            _tmpIsExplicit = _tmp_2?.let { it != 0 }
            val _tmpPopularity: Int?
            if (_cursor.isNull(_cursorIndexOfPopularity)) {
              _tmpPopularity = null
            } else {
              _tmpPopularity = _cursor.getInt(_cursorIndexOfPopularity)
            }
            val _tmpGenresJson: String?
            if (_cursor.isNull(_cursorIndexOfGenresJson)) {
              _tmpGenresJson = null
            } else {
              _tmpGenresJson = _cursor.getString(_cursorIndexOfGenresJson)
            }
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _result =
                TrackEntity(_tmpId,_tmpPrimaryArtistId,_tmpAlbumId,_tmpTitle,_tmpNormalizedTitle,_tmpDisplayArtistName,_tmpDisplayAlbumTitle,_tmpDurationMs,_tmpCoverUri,_tmpCanonicalAudioSourceType,_tmpIsLiked,_tmpIsDownloadedByAura,_tmpIsExplicit,_tmpPopularity,_tmpGenresJson,_tmpCreatedAt,_tmpUpdatedAt)
          } else {
            _result = null
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
  }

  public override suspend fun getTrackCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM tracks"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<Int> {
      public override fun call(): Int {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _result: Int
          if (_cursor.moveToFirst()) {
            val _tmp: Int
            _tmp = _cursor.getInt(0)
            _result = _tmp
          } else {
            _result = 0
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
  }

  public override suspend fun getLocalTrackIds(): List<String> {
    val _sql: String = "SELECT id FROM tracks WHERE canonical_audio_source_type = 'local'"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<String>> {
      public override fun call(): List<String> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _result: MutableList<String> = ArrayList<String>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: String
            _item = _cursor.getString(0)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
  }

  public override suspend fun getDownloadedTrackIds(): List<String> {
    val _sql: String = "SELECT id FROM tracks WHERE canonical_audio_source_type = 'downloaded'"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<String>> {
      public override fun call(): List<String> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _result: MutableList<String> = ArrayList<String>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: String
            _item = _cursor.getString(0)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, trackId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<TrackListRow?> {
      public override fun call(): TrackListRow? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: TrackListRow?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _result =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
          } else {
            _result = null
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 4)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, query)
    _argIndex = 2
    _statement.bindString(_argIndex, query)
    _argIndex = 3
    _statement.bindString(_argIndex, query)
    _argIndex = 4
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 2)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, artistId)
    _argIndex = 2
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, albumId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
  }

  public override suspend fun getTracksForAlbumByText(albumTitle: String, artistName: String):
      List<TrackListRow> {
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 2)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, albumTitle)
    _argIndex = 2
    _statement.bindString(_argIndex, artistName)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<TrackListRow>> {
      public override fun call(): List<TrackListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfArtistId: Int = 1
          val _cursorIndexOfAlbumId: Int = 2
          val _cursorIndexOfTitle: Int = 3
          val _cursorIndexOfArtistName: Int = 4
          val _cursorIndexOfAlbumTitle: Int = 5
          val _cursorIndexOfContentUri: Int = 6
          val _cursorIndexOfDurationMs: Int = 7
          val _cursorIndexOfCoverUri: Int = 8
          val _cursorIndexOfIsLiked: Int = 9
          val _cursorIndexOfCreatedAt: Int = 10
          val _cursorIndexOfUpdatedAt: Int = 11
          val _result: MutableList<TrackListRow> = ArrayList<TrackListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: TrackListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpAlbumId: String?
            if (_cursor.isNull(_cursorIndexOfAlbumId)) {
              _tmpAlbumId = null
            } else {
              _tmpAlbumId = _cursor.getString(_cursorIndexOfAlbumId)
            }
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpAlbumTitle: String?
            if (_cursor.isNull(_cursorIndexOfAlbumTitle)) {
              _tmpAlbumTitle = null
            } else {
              _tmpAlbumTitle = _cursor.getString(_cursorIndexOfAlbumTitle)
            }
            val _tmpContentUri: String?
            if (_cursor.isNull(_cursorIndexOfContentUri)) {
              _tmpContentUri = null
            } else {
              _tmpContentUri = _cursor.getString(_cursorIndexOfContentUri)
            }
            val _tmpDurationMs: Long?
            if (_cursor.isNull(_cursorIndexOfDurationMs)) {
              _tmpDurationMs = null
            } else {
              _tmpDurationMs = _cursor.getLong(_cursorIndexOfDurationMs)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                TrackListRow(_tmpId,_tmpArtistId,_tmpAlbumId,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpIsLiked,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
          _statement.release()
        }
      }
    })
  }

  public override suspend fun deleteTracksByIds(ids: List<String>): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      val _stringBuilder: StringBuilder = newStringBuilder()
      _stringBuilder.append("DELETE FROM tracks WHERE id IN (")
      val _inputSize: Int = ids.size
      appendPlaceholders(_stringBuilder, _inputSize)
      _stringBuilder.append(")")
      val _sql: String = _stringBuilder.toString()
      val _stmt: SupportSQLiteStatement = __db.compileStatement(_sql)
      var _argIndex: Int = 1
      for (_item: String in ids) {
        _stmt.bindString(_argIndex, _item)
        _argIndex++
      }
      __db.beginTransaction()
      try {
        _stmt.executeUpdateDelete()
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public companion object {
    @JvmStatic
    public fun getRequiredConverters(): List<Class<*>> = emptyList()
  }
}
