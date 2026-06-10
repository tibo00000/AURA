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
public class ArtistDao_Impl(
  __db: RoomDatabase,
) : ArtistDao {
  private val __db: RoomDatabase

  private val __preparedStmtOfUpdateArtwork: SharedSQLiteStatement

  private val __upsertionAdapterOfArtistEntity: EntityUpsertionAdapter<ArtistEntity>
  init {
    this.__db = __db
    this.__preparedStmtOfUpdateArtwork = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = """
            |
            |        UPDATE artists
            |        SET picture_uri = ?,
            |            artwork_origin = ?,
            |            artwork_last_resolved_at = ?,
            |            updated_at = ?
            |        WHERE id = ?
            |        
            """.trimMargin()
        return _query
      }
    }
    this.__upsertionAdapterOfArtistEntity = EntityUpsertionAdapter<ArtistEntity>(object :
        EntityInsertionAdapter<ArtistEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT INTO `artists` (`id`,`name`,`normalized_name`,`picture_uri`,`artwork_origin`,`artwork_last_resolved_at`,`summary`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: ArtistEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.name)
        statement.bindString(3, entity.normalizedName)
        val _tmpPictureUri: String? = entity.pictureUri
        if (_tmpPictureUri == null) {
          statement.bindNull(4)
        } else {
          statement.bindString(4, _tmpPictureUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(5)
        } else {
          statement.bindString(5, _tmpArtworkOrigin)
        }
        val _tmpArtworkLastResolvedAt: Long? = entity.artworkLastResolvedAt
        if (_tmpArtworkLastResolvedAt == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpArtworkLastResolvedAt)
        }
        val _tmpSummary: String? = entity.summary
        if (_tmpSummary == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpSummary)
        }
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
      }
    }, object : EntityDeletionOrUpdateAdapter<ArtistEntity>(__db) {
      protected override fun createQuery(): String =
          "UPDATE `artists` SET `id` = ?,`name` = ?,`normalized_name` = ?,`picture_uri` = ?,`artwork_origin` = ?,`artwork_last_resolved_at` = ?,`summary` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SupportSQLiteStatement, entity: ArtistEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.name)
        statement.bindString(3, entity.normalizedName)
        val _tmpPictureUri: String? = entity.pictureUri
        if (_tmpPictureUri == null) {
          statement.bindNull(4)
        } else {
          statement.bindString(4, _tmpPictureUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(5)
        } else {
          statement.bindString(5, _tmpArtworkOrigin)
        }
        val _tmpArtworkLastResolvedAt: Long? = entity.artworkLastResolvedAt
        if (_tmpArtworkLastResolvedAt == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpArtworkLastResolvedAt)
        }
        val _tmpSummary: String? = entity.summary
        if (_tmpSummary == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpSummary)
        }
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
        statement.bindString(10, entity.id)
      }
    })
  }

  public override suspend fun updateArtwork(
    artistId: String,
    pictureUri: String,
    artworkOrigin: String,
    resolvedAt: Long,
    updatedAt: Long,
  ): Unit = CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateArtwork.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, pictureUri)
      _argIndex = 2
      _stmt.bindString(_argIndex, artworkOrigin)
      _argIndex = 3
      _stmt.bindLong(_argIndex, resolvedAt)
      _argIndex = 4
      _stmt.bindLong(_argIndex, updatedAt)
      _argIndex = 5
      _stmt.bindString(_argIndex, artistId)
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

  public override suspend fun upsertArtists(items: List<ArtistEntity>): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfArtistEntity.upsert(items)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun getBrowseArtists(limit: Int): List<ArtistBrowseRow> {
    val _sql: String = """
        |
        |        SELECT
        |            artists.id AS id,
        |            artists.name AS name,
        |            artists.picture_uri AS picture_uri,
        |            COUNT(DISTINCT tracks.id) AS track_count,
        |            COUNT(DISTINCT albums.id) AS album_count,
        |            artists.updated_at AS updated_at
        |        FROM artists
        |        LEFT JOIN tracks ON tracks.primary_artist_id = artists.id
        |        LEFT JOIN albums ON albums.primary_artist_id = artists.id
        |        GROUP BY artists.id
        |        ORDER BY COUNT(DISTINCT tracks.id) DESC, artists.name ASC
        |        LIMIT ?
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<ArtistBrowseRow>> {
      public override fun call(): List<ArtistBrowseRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfName: Int = 1
          val _cursorIndexOfPictureUri: Int = 2
          val _cursorIndexOfTrackCount: Int = 3
          val _cursorIndexOfAlbumCount: Int = 4
          val _cursorIndexOfUpdatedAt: Int = 5
          val _result: MutableList<ArtistBrowseRow> = ArrayList<ArtistBrowseRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: ArtistBrowseRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpName: String
            _tmpName = _cursor.getString(_cursorIndexOfName)
            val _tmpPictureUri: String?
            if (_cursor.isNull(_cursorIndexOfPictureUri)) {
              _tmpPictureUri = null
            } else {
              _tmpPictureUri = _cursor.getString(_cursorIndexOfPictureUri)
            }
            val _tmpTrackCount: Int
            _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            val _tmpAlbumCount: Int
            _tmpAlbumCount = _cursor.getInt(_cursorIndexOfAlbumCount)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                ArtistBrowseRow(_tmpId,_tmpName,_tmpPictureUri,_tmpTrackCount,_tmpAlbumCount,_tmpUpdatedAt)
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

  public override suspend fun getAllBrowseArtists(): List<ArtistBrowseRow> {
    val _sql: String = """
        |
        |        SELECT
        |            artists.id AS id,
        |            artists.name AS name,
        |            artists.picture_uri AS picture_uri,
        |            COUNT(DISTINCT tracks.id) AS track_count,
        |            COUNT(DISTINCT albums.id) AS album_count,
        |            artists.updated_at AS updated_at
        |        FROM artists
        |        LEFT JOIN tracks ON tracks.primary_artist_id = artists.id
        |        LEFT JOIN albums ON albums.primary_artist_id = artists.id
        |        GROUP BY artists.id
        |        ORDER BY lower(artists.name) ASC
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<ArtistBrowseRow>> {
      public override fun call(): List<ArtistBrowseRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfName: Int = 1
          val _cursorIndexOfPictureUri: Int = 2
          val _cursorIndexOfTrackCount: Int = 3
          val _cursorIndexOfAlbumCount: Int = 4
          val _cursorIndexOfUpdatedAt: Int = 5
          val _result: MutableList<ArtistBrowseRow> = ArrayList<ArtistBrowseRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: ArtistBrowseRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpName: String
            _tmpName = _cursor.getString(_cursorIndexOfName)
            val _tmpPictureUri: String?
            if (_cursor.isNull(_cursorIndexOfPictureUri)) {
              _tmpPictureUri = null
            } else {
              _tmpPictureUri = _cursor.getString(_cursorIndexOfPictureUri)
            }
            val _tmpTrackCount: Int
            _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            val _tmpAlbumCount: Int
            _tmpAlbumCount = _cursor.getInt(_cursorIndexOfAlbumCount)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                ArtistBrowseRow(_tmpId,_tmpName,_tmpPictureUri,_tmpTrackCount,_tmpAlbumCount,_tmpUpdatedAt)
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

  public override suspend fun searchArtists(query: String, limit: Int): List<ArtistBrowseRow> {
    val _sql: String = """
        |
        |        SELECT
        |            artists.id AS id,
        |            artists.name AS name,
        |            artists.picture_uri AS picture_uri,
        |            COUNT(DISTINCT tracks.id) AS track_count,
        |            COUNT(DISTINCT albums.id) AS album_count,
        |            artists.updated_at AS updated_at
        |        FROM artists
        |        LEFT JOIN tracks ON tracks.primary_artist_id = artists.id
        |        LEFT JOIN albums ON albums.primary_artist_id = artists.id
        |        WHERE lower(artists.name) LIKE '%' || lower(?) || '%'
        |        GROUP BY artists.id
        |        ORDER BY COUNT(DISTINCT tracks.id) DESC, artists.name ASC
        |        LIMIT ?
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 2)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, query)
    _argIndex = 2
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<ArtistBrowseRow>> {
      public override fun call(): List<ArtistBrowseRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfName: Int = 1
          val _cursorIndexOfPictureUri: Int = 2
          val _cursorIndexOfTrackCount: Int = 3
          val _cursorIndexOfAlbumCount: Int = 4
          val _cursorIndexOfUpdatedAt: Int = 5
          val _result: MutableList<ArtistBrowseRow> = ArrayList<ArtistBrowseRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: ArtistBrowseRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpName: String
            _tmpName = _cursor.getString(_cursorIndexOfName)
            val _tmpPictureUri: String?
            if (_cursor.isNull(_cursorIndexOfPictureUri)) {
              _tmpPictureUri = null
            } else {
              _tmpPictureUri = _cursor.getString(_cursorIndexOfPictureUri)
            }
            val _tmpTrackCount: Int
            _tmpTrackCount = _cursor.getInt(_cursorIndexOfTrackCount)
            val _tmpAlbumCount: Int
            _tmpAlbumCount = _cursor.getInt(_cursorIndexOfAlbumCount)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                ArtistBrowseRow(_tmpId,_tmpName,_tmpPictureUri,_tmpTrackCount,_tmpAlbumCount,_tmpUpdatedAt)
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

  public override suspend fun getArtistDetail(artistId: String): ArtistDetailRow? {
    val _sql: String = """
        |
        |        SELECT
        |            artists.id AS id,
        |            artists.name AS name,
        |            artists.picture_uri AS picture_uri,
        |            artists.summary AS summary
        |        FROM artists
        |        WHERE artists.id = ?
        |        LIMIT 1
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, artistId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<ArtistDetailRow?> {
      public override fun call(): ArtistDetailRow? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfName: Int = 1
          val _cursorIndexOfPictureUri: Int = 2
          val _cursorIndexOfSummary: Int = 3
          val _result: ArtistDetailRow?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpName: String
            _tmpName = _cursor.getString(_cursorIndexOfName)
            val _tmpPictureUri: String?
            if (_cursor.isNull(_cursorIndexOfPictureUri)) {
              _tmpPictureUri = null
            } else {
              _tmpPictureUri = _cursor.getString(_cursorIndexOfPictureUri)
            }
            val _tmpSummary: String?
            if (_cursor.isNull(_cursorIndexOfSummary)) {
              _tmpSummary = null
            } else {
              _tmpSummary = _cursor.getString(_cursorIndexOfSummary)
            }
            _result = ArtistDetailRow(_tmpId,_tmpName,_tmpPictureUri,_tmpSummary)
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

  public override suspend fun getArtworkLastResolvedAt(artistId: String): Long? {
    val _sql: String = "SELECT artwork_last_resolved_at FROM artists WHERE id = ? LIMIT 1"
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, artistId)
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
