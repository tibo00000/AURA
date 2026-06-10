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
import androidx.room.SharedSQLiteStatement
import androidx.room.util.createCancellationSignal
import androidx.room.util.query
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.Class
import java.util.ArrayList
import java.util.concurrent.Callable
import javax.`annotation`.processing.Generated
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
public class AlbumDao_Impl(
  __db: RoomDatabase,
) : AlbumDao {
  private val __db: RoomDatabase

  private val __preparedStmtOfUpdateArtwork: SharedSQLiteStatement

  private val __upsertionAdapterOfAlbumEntity: EntityUpsertionAdapter<AlbumEntity>
  init {
    this.__db = __db
    this.__preparedStmtOfUpdateArtwork = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = """
            |
            |        UPDATE albums
            |        SET cover_uri = ?,
            |            artwork_origin = ?,
            |            artwork_last_resolved_at = ?,
            |            updated_at = ?
            |        WHERE id = ?
            |        
            """.trimMargin()
        return _query
      }
    }
    this.__upsertionAdapterOfAlbumEntity = EntityUpsertionAdapter<AlbumEntity>(object :
        EntityInsertionAdapter<AlbumEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT INTO `albums` (`id`,`primary_artist_id`,`title`,`normalized_title`,`cover_uri`,`artwork_origin`,`artwork_last_resolved_at`,`release_date`,`track_count`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: AlbumEntity) {
        statement.bindString(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindString(2, _tmpPrimaryArtistId)
        }
        statement.bindString(3, entity.title)
        statement.bindString(4, entity.normalizedTitle)
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(5)
        } else {
          statement.bindString(5, _tmpCoverUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(6)
        } else {
          statement.bindString(6, _tmpArtworkOrigin)
        }
        val _tmpArtworkLastResolvedAt: Long? = entity.artworkLastResolvedAt
        if (_tmpArtworkLastResolvedAt == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpArtworkLastResolvedAt)
        }
        val _tmpReleaseDate: String? = entity.releaseDate
        if (_tmpReleaseDate == null) {
          statement.bindNull(8)
        } else {
          statement.bindString(8, _tmpReleaseDate)
        }
        val _tmpTrackCount: Int? = entity.trackCount
        if (_tmpTrackCount == null) {
          statement.bindNull(9)
        } else {
          statement.bindLong(9, _tmpTrackCount.toLong())
        }
        statement.bindLong(10, entity.createdAt)
        statement.bindLong(11, entity.updatedAt)
      }
    }, object : EntityDeletionOrUpdateAdapter<AlbumEntity>(__db) {
      protected override fun createQuery(): String =
          "UPDATE `albums` SET `id` = ?,`primary_artist_id` = ?,`title` = ?,`normalized_title` = ?,`cover_uri` = ?,`artwork_origin` = ?,`artwork_last_resolved_at` = ?,`release_date` = ?,`track_count` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SupportSQLiteStatement, entity: AlbumEntity) {
        statement.bindString(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindString(2, _tmpPrimaryArtistId)
        }
        statement.bindString(3, entity.title)
        statement.bindString(4, entity.normalizedTitle)
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(5)
        } else {
          statement.bindString(5, _tmpCoverUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(6)
        } else {
          statement.bindString(6, _tmpArtworkOrigin)
        }
        val _tmpArtworkLastResolvedAt: Long? = entity.artworkLastResolvedAt
        if (_tmpArtworkLastResolvedAt == null) {
          statement.bindNull(7)
        } else {
          statement.bindLong(7, _tmpArtworkLastResolvedAt)
        }
        val _tmpReleaseDate: String? = entity.releaseDate
        if (_tmpReleaseDate == null) {
          statement.bindNull(8)
        } else {
          statement.bindString(8, _tmpReleaseDate)
        }
        val _tmpTrackCount: Int? = entity.trackCount
        if (_tmpTrackCount == null) {
          statement.bindNull(9)
        } else {
          statement.bindLong(9, _tmpTrackCount.toLong())
        }
        statement.bindLong(10, entity.createdAt)
        statement.bindLong(11, entity.updatedAt)
        statement.bindString(12, entity.id)
      }
    })
  }

  public override suspend fun updateArtwork(
    albumId: String,
    coverUri: String,
    artworkOrigin: String,
    resolvedAt: Long,
    updatedAt: Long,
  ): Unit = CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateArtwork.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, coverUri)
      _argIndex = 2
      _stmt.bindString(_argIndex, artworkOrigin)
      _argIndex = 3
      _stmt.bindLong(_argIndex, resolvedAt)
      _argIndex = 4
      _stmt.bindLong(_argIndex, updatedAt)
      _argIndex = 5
      _stmt.bindString(_argIndex, albumId)
      try {
        __db.beginTransaction()
        try {
          _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfUpdateArtwork.release(_stmt)
      }
    }
  })

  public override suspend fun upsertAlbums(items: List<AlbumEntity>): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfAlbumEntity.upsert(items)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun getBrowseAlbums(limit: Int): List<AlbumBrowseRow> {
    val _sql: String = """
        |
        |        SELECT
        |            albums.id AS id,
        |            albums.title AS title,
        |            albums.primary_artist_id AS artist_id,
        |            artists.name AS artist_name,
        |            albums.cover_uri AS cover_uri,
        |            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        |        FROM albums
        |        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        |        LEFT JOIN tracks ON tracks.album_id = albums.id
        |        GROUP BY albums.id
        |        ORDER BY albums.updated_at DESC, albums.title ASC
        |        LIMIT ?
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<AlbumBrowseRow>> {
      public override fun call(): List<AlbumBrowseRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfTitle: Int = 1
          val _cursorIndexOfArtistId: Int = 2
          val _cursorIndexOfArtistName: Int = 3
          val _cursorIndexOfCoverUri: Int = 4
          val _cursorIndexOfTrackCount: Int = 5
          val _result: MutableList<AlbumBrowseRow> = ArrayList<AlbumBrowseRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: AlbumBrowseRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpArtistName: String?
            if (_cursor.isNull(_cursorIndexOfArtistName)) {
              _tmpArtistName = null
            } else {
              _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpTrackCount: Int?
            if (_cursor.isNull(_cursorIndexOfTrackCount)) {
              _tmpTrackCount = null
            } else {
              _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            }
            _item =
                AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
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

  public override suspend fun getAllBrowseAlbums(): List<AlbumBrowseRow> {
    val _sql: String = """
        |
        |        SELECT
        |            albums.id AS id,
        |            albums.title AS title,
        |            albums.primary_artist_id AS artist_id,
        |            artists.name AS artist_name,
        |            albums.cover_uri AS cover_uri,
        |            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        |        FROM albums
        |        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        |        LEFT JOIN tracks ON tracks.album_id = albums.id
        |        GROUP BY albums.id
        |        ORDER BY albums.title ASC
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<AlbumBrowseRow>> {
      public override fun call(): List<AlbumBrowseRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfTitle: Int = 1
          val _cursorIndexOfArtistId: Int = 2
          val _cursorIndexOfArtistName: Int = 3
          val _cursorIndexOfCoverUri: Int = 4
          val _cursorIndexOfTrackCount: Int = 5
          val _result: MutableList<AlbumBrowseRow> = ArrayList<AlbumBrowseRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: AlbumBrowseRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpArtistName: String?
            if (_cursor.isNull(_cursorIndexOfArtistName)) {
              _tmpArtistName = null
            } else {
              _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpTrackCount: Int?
            if (_cursor.isNull(_cursorIndexOfTrackCount)) {
              _tmpTrackCount = null
            } else {
              _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            }
            _item =
                AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
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

  public override suspend fun searchAlbums(query: String, limit: Int): List<AlbumBrowseRow> {
    val _sql: String = """
        |
        |        SELECT
        |            albums.id AS id,
        |            albums.title AS title,
        |            albums.primary_artist_id AS artist_id,
        |            artists.name AS artist_name,
        |            albums.cover_uri AS cover_uri,
        |            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        |        FROM albums
        |        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        |        LEFT JOIN tracks ON tracks.album_id = albums.id
        |        WHERE lower(albums.title) LIKE '%' || lower(?) || '%'
        |           OR lower(artists.name) LIKE '%' || lower(?) || '%'
        |        GROUP BY albums.id
        |        ORDER BY albums.updated_at DESC, albums.title ASC
        |        LIMIT ?
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 3)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, query)
    _argIndex = 2
    _statement.bindString(_argIndex, query)
    _argIndex = 3
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<AlbumBrowseRow>> {
      public override fun call(): List<AlbumBrowseRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfTitle: Int = 1
          val _cursorIndexOfArtistId: Int = 2
          val _cursorIndexOfArtistName: Int = 3
          val _cursorIndexOfCoverUri: Int = 4
          val _cursorIndexOfTrackCount: Int = 5
          val _result: MutableList<AlbumBrowseRow> = ArrayList<AlbumBrowseRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: AlbumBrowseRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpArtistName: String?
            if (_cursor.isNull(_cursorIndexOfArtistName)) {
              _tmpArtistName = null
            } else {
              _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpTrackCount: Int?
            if (_cursor.isNull(_cursorIndexOfTrackCount)) {
              _tmpTrackCount = null
            } else {
              _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            }
            _item =
                AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
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

  public override suspend fun getAlbumsForArtist(artistId: String, limit: Int):
      List<AlbumBrowseRow> {
    val _sql: String = """
        |
        |        SELECT
        |            albums.id AS id,
        |            albums.title AS title,
        |            albums.primary_artist_id AS artist_id,
        |            artists.name AS artist_name,
        |            albums.cover_uri AS cover_uri,
        |            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        |        FROM albums
        |        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        |        LEFT JOIN tracks ON tracks.album_id = albums.id
        |        WHERE albums.primary_artist_id = ?
        |        GROUP BY albums.id
        |        ORDER BY albums.updated_at DESC, albums.title ASC
        |        LIMIT ?
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 2)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, artistId)
    _argIndex = 2
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<AlbumBrowseRow>> {
      public override fun call(): List<AlbumBrowseRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfTitle: Int = 1
          val _cursorIndexOfArtistId: Int = 2
          val _cursorIndexOfArtistName: Int = 3
          val _cursorIndexOfCoverUri: Int = 4
          val _cursorIndexOfTrackCount: Int = 5
          val _result: MutableList<AlbumBrowseRow> = ArrayList<AlbumBrowseRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: AlbumBrowseRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpArtistName: String?
            if (_cursor.isNull(_cursorIndexOfArtistName)) {
              _tmpArtistName = null
            } else {
              _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpTrackCount: Int?
            if (_cursor.isNull(_cursorIndexOfTrackCount)) {
              _tmpTrackCount = null
            } else {
              _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            }
            _item =
                AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
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

  public override suspend fun getAlbumDetail(albumId: String): AlbumDetailRow? {
    val _sql: String = """
        |
        |        SELECT
        |            albums.id AS id,
        |            albums.title AS title,
        |            albums.primary_artist_id AS artist_id,
        |            artists.name AS artist_name,
        |            albums.cover_uri AS cover_uri,
        |            albums.release_date AS release_date,
        |            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        |        FROM albums
        |        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        |        LEFT JOIN tracks ON tracks.album_id = albums.id
        |        WHERE albums.id = ?
        |        GROUP BY albums.id
        |        LIMIT 1
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, albumId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<AlbumDetailRow?> {
      public override fun call(): AlbumDetailRow? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfTitle: Int = 1
          val _cursorIndexOfArtistId: Int = 2
          val _cursorIndexOfArtistName: Int = 3
          val _cursorIndexOfCoverUri: Int = 4
          val _cursorIndexOfReleaseDate: Int = 5
          val _cursorIndexOfTrackCount: Int = 6
          val _result: AlbumDetailRow?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpArtistName: String?
            if (_cursor.isNull(_cursorIndexOfArtistName)) {
              _tmpArtistName = null
            } else {
              _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpReleaseDate: String?
            if (_cursor.isNull(_cursorIndexOfReleaseDate)) {
              _tmpReleaseDate = null
            } else {
              _tmpReleaseDate = _cursor.getString(_cursorIndexOfReleaseDate)
            }
            val _tmpTrackCount: Int?
            if (_cursor.isNull(_cursorIndexOfTrackCount)) {
              _tmpTrackCount = null
            } else {
              _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            }
            _result =
                AlbumDetailRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpReleaseDate,_tmpTrackCount)
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

  public override suspend fun getAlbumByTitleAndArtist(title: String, artistName: String):
      AlbumBrowseRow? {
    val _sql: String = """
        |
        |        SELECT
        |            albums.id AS id,
        |            albums.title AS title,
        |            albums.primary_artist_id AS artist_id,
        |            artists.name AS artist_name,
        |            albums.cover_uri AS cover_uri,
        |            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        |        FROM albums
        |        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        |        LEFT JOIN tracks ON tracks.album_id = albums.id
        |        WHERE lower(albums.title) = lower(?) AND lower(artists.name) = lower(?)
        |        GROUP BY albums.id
        |        LIMIT 1
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 2)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, title)
    _argIndex = 2
    _statement.bindString(_argIndex, artistName)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<AlbumBrowseRow?> {
      public override fun call(): AlbumBrowseRow? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfTitle: Int = 1
          val _cursorIndexOfArtistId: Int = 2
          val _cursorIndexOfArtistName: Int = 3
          val _cursorIndexOfCoverUri: Int = 4
          val _cursorIndexOfTrackCount: Int = 5
          val _result: AlbumBrowseRow?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistId: String?
            if (_cursor.isNull(_cursorIndexOfArtistId)) {
              _tmpArtistId = null
            } else {
              _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            }
            val _tmpArtistName: String?
            if (_cursor.isNull(_cursorIndexOfArtistName)) {
              _tmpArtistName = null
            } else {
              _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            }
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpTrackCount: Int?
            if (_cursor.isNull(_cursorIndexOfTrackCount)) {
              _tmpTrackCount = null
            } else {
              _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            }
            _result =
                AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
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

  public override suspend fun getArtworkLastResolvedAt(albumId: String): Long? {
    val _sql: String = "SELECT artwork_last_resolved_at FROM albums WHERE id = ? LIMIT 1"
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, albumId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<Long?> {
      public override fun call(): Long? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _result: Long?
          if (_cursor.moveToFirst()) {
            if (_cursor.isNull(0)) {
              _result = null
            } else {
              _result = _cursor.getLong(0)
            }
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

  public companion object {
    @JvmStatic
    public fun getRequiredConverters(): List<Class<*>> = emptyList()
  }
}
