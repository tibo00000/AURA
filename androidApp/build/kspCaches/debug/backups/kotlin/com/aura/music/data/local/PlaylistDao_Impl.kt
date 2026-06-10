package com.aura.music.`data`.local

import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.CoroutinesRoom
import androidx.room.CoroutinesRoom.Companion.execute
import androidx.room.EntityInsertionAdapter
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
public class PlaylistDao_Impl(
  __db: RoomDatabase,
) : PlaylistDao {
  private val __db: RoomDatabase

  private val __insertionAdapterOfPlaylistEntity: EntityInsertionAdapter<PlaylistEntity>

  private val __insertionAdapterOfPlaylistItemEntity: EntityInsertionAdapter<PlaylistItemEntity>

  private val __preparedStmtOfRenamePlaylist: SharedSQLiteStatement

  private val __preparedStmtOfDeletePlaylist: SharedSQLiteStatement

  private val __preparedStmtOfDeletePlaylistItem: SharedSQLiteStatement

  private val __preparedStmtOfUpdatePlaylistItemPosition: SharedSQLiteStatement

  private val __preparedStmtOfTouchPlaylist: SharedSQLiteStatement
  init {
    this.__db = __db
    this.__insertionAdapterOfPlaylistEntity = object : EntityInsertionAdapter<PlaylistEntity>(__db)
        {
      protected override fun createQuery(): String =
          "INSERT OR ABORT INTO `playlists` (`id`,`name`,`cover_uri`,`is_pinned`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: PlaylistEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.name)
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(3)
        } else {
          statement.bindString(3, _tmpCoverUri)
        }
        val _tmp: Int = if (entity.isPinned) 1 else 0
        statement.bindLong(4, _tmp.toLong())
        statement.bindLong(5, entity.createdAt)
        statement.bindLong(6, entity.updatedAt)
      }
    }
    this.__insertionAdapterOfPlaylistItemEntity = object :
        EntityInsertionAdapter<PlaylistItemEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT OR ABORT INTO `playlist_items` (`id`,`playlist_id`,`track_id`,`position`,`added_at`,`added_from_context_type`,`added_from_context_id`) VALUES (?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: PlaylistItemEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.playlistId)
        statement.bindString(3, entity.trackId)
        statement.bindLong(4, entity.position.toLong())
        statement.bindLong(5, entity.addedAt)
        val _tmpAddedFromContextType: String? = entity.addedFromContextType
        if (_tmpAddedFromContextType == null) {
          statement.bindNull(6)
        } else {
          statement.bindString(6, _tmpAddedFromContextType)
        }
        val _tmpAddedFromContextId: String? = entity.addedFromContextId
        if (_tmpAddedFromContextId == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpAddedFromContextId)
        }
      }
    }
    this.__preparedStmtOfRenamePlaylist = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "UPDATE playlists SET name = ?, updated_at = ? WHERE id = ?"
        return _query
      }
    }
    this.__preparedStmtOfDeletePlaylist = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "DELETE FROM playlists WHERE id = ?"
        return _query
      }
    }
    this.__preparedStmtOfDeletePlaylistItem = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "DELETE FROM playlist_items WHERE id = ?"
        return _query
      }
    }
    this.__preparedStmtOfUpdatePlaylistItemPosition = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "UPDATE playlist_items SET position = ? WHERE id = ?"
        return _query
      }
    }
    this.__preparedStmtOfTouchPlaylist = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "UPDATE playlists SET updated_at = ? WHERE id = ?"
        return _query
      }
    }
  }

  public override suspend fun insertPlaylist(entity: PlaylistEntity): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __insertionAdapterOfPlaylistEntity.insert(entity)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun insertPlaylistItem(entity: PlaylistItemEntity): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __insertionAdapterOfPlaylistItemEntity.insert(entity)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun renamePlaylist(
    playlistId: String,
    name: String,
    updatedAt: Long,
  ): Int = CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfRenamePlaylist.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, name)
      _argIndex = 2
      _stmt.bindLong(_argIndex, updatedAt)
      _argIndex = 3
      _stmt.bindString(_argIndex, playlistId)
      try {
        __db.beginTransaction()
        try {
          val _result: Int = _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
          return _result
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfRenamePlaylist.release(_stmt)
      }
    }
  })

  public override suspend fun deletePlaylist(playlistId: String): Int = CoroutinesRoom.execute(__db,
      true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfDeletePlaylist.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, playlistId)
      try {
        __db.beginTransaction()
        try {
          val _result: Int = _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
          return _result
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfDeletePlaylist.release(_stmt)
      }
    }
  })

  public override suspend fun deletePlaylistItem(playlistItemId: String): Int =
      CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfDeletePlaylistItem.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, playlistItemId)
      try {
        __db.beginTransaction()
        try {
          val _result: Int = _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
          return _result
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfDeletePlaylistItem.release(_stmt)
      }
    }
  })

  public override suspend fun updatePlaylistItemPosition(playlistItemId: String, position: Int): Int
      = CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdatePlaylistItemPosition.acquire()
      var _argIndex: Int = 1
      _stmt.bindLong(_argIndex, position.toLong())
      _argIndex = 2
      _stmt.bindString(_argIndex, playlistItemId)
      try {
        __db.beginTransaction()
        try {
          val _result: Int = _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
          return _result
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfUpdatePlaylistItemPosition.release(_stmt)
      }
    }
  })

  public override suspend fun touchPlaylist(playlistId: String, updatedAt: Long): Int =
      CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfTouchPlaylist.acquire()
      var _argIndex: Int = 1
      _stmt.bindLong(_argIndex, updatedAt)
      _argIndex = 2
      _stmt.bindString(_argIndex, playlistId)
      try {
        __db.beginTransaction()
        try {
          val _result: Int = _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
          return _result
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfTouchPlaylist.release(_stmt)
      }
    }
  })

  public override suspend fun getPlaylistCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM playlists"
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

  public override suspend fun getPlaylists(): List<PlaylistListRow> {
    val _sql: String = """
        |
        |        SELECT
        |            playlists.id AS id,
        |            playlists.name AS name,
        |            playlists.is_pinned AS is_pinned,
        |            COUNT(playlist_items.id) AS item_count,
        |            playlists.updated_at AS updated_at
        |        FROM playlists
        |        LEFT JOIN playlist_items ON playlist_items.playlist_id = playlists.id
        |        GROUP BY playlists.id
        |        ORDER BY is_pinned DESC, updated_at DESC
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<PlaylistListRow>> {
      public override fun call(): List<PlaylistListRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfName: Int = 1
          val _cursorIndexOfIsPinned: Int = 2
          val _cursorIndexOfItemCount: Int = 3
          val _cursorIndexOfUpdatedAt: Int = 4
          val _result: MutableList<PlaylistListRow> = ArrayList<PlaylistListRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: PlaylistListRow
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpName: String
            _tmpName = _cursor.getString(_cursorIndexOfName)
            val _tmpIsPinned: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsPinned)
            _tmpIsPinned = _tmp != 0
            val _tmpItemCount: Int
            _tmpItemCount = _cursor.getInt(_cursorIndexOfItemCount)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item = PlaylistListRow(_tmpId,_tmpName,_tmpIsPinned,_tmpItemCount,_tmpUpdatedAt)
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

  public override suspend fun getPlaylistDetail(playlistId: String): PlaylistDetailRow? {
    val _sql: String = """
        |
        |        SELECT
        |            playlists.id AS id,
        |            playlists.name AS name,
        |            playlists.cover_uri AS cover_uri,
        |            playlists.is_pinned AS is_pinned,
        |            COUNT(playlist_items.id) AS item_count,
        |            playlists.updated_at AS updated_at
        |        FROM playlists
        |        LEFT JOIN playlist_items ON playlist_items.playlist_id = playlists.id
        |        WHERE playlists.id = ?
        |        GROUP BY playlists.id
        |        LIMIT 1
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, playlistId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<PlaylistDetailRow?> {
      public override fun call(): PlaylistDetailRow? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = 0
          val _cursorIndexOfName: Int = 1
          val _cursorIndexOfCoverUri: Int = 2
          val _cursorIndexOfIsPinned: Int = 3
          val _cursorIndexOfItemCount: Int = 4
          val _cursorIndexOfUpdatedAt: Int = 5
          val _result: PlaylistDetailRow?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpName: String
            _tmpName = _cursor.getString(_cursorIndexOfName)
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpIsPinned: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsPinned)
            _tmpIsPinned = _tmp != 0
            val _tmpItemCount: Int
            _tmpItemCount = _cursor.getInt(_cursorIndexOfItemCount)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _result =
                PlaylistDetailRow(_tmpId,_tmpName,_tmpCoverUri,_tmpIsPinned,_tmpItemCount,_tmpUpdatedAt)
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

  public override suspend fun getPlaylistTracks(playlistId: String): List<PlaylistTrackRow> {
    val _sql: String = """
        |
        |        SELECT
        |            playlist_items.id AS playlist_item_id,
        |            playlist_items.playlist_id AS playlist_id,
        |            playlist_items.track_id AS track_id,
        |            playlist_items.position AS position,
        |            playlist_items.added_at AS added_at,
        |            tracks.title AS title,
        |            tracks.display_artist_name AS artist_name,
        |            tracks.display_album_title AS album_title,
        |            track_media_links.content_uri AS content_uri,
        |            tracks.duration_ms AS duration_ms,
        |            tracks.cover_uri AS cover_uri,
        |            tracks.primary_artist_id AS artist_id,
        |            tracks.album_id AS album_id,
        |            tracks.is_liked AS is_liked
        |        FROM playlist_items
        |        INNER JOIN tracks ON tracks.id = playlist_items.track_id
        |        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        |        WHERE playlist_items.playlist_id = ?
        |        ORDER BY playlist_items.position ASC
        |        
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, playlistId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<PlaylistTrackRow>> {
      public override fun call(): List<PlaylistTrackRow> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfPlaylistItemId: Int = 0
          val _cursorIndexOfPlaylistId: Int = 1
          val _cursorIndexOfTrackId: Int = 2
          val _cursorIndexOfPosition: Int = 3
          val _cursorIndexOfAddedAt: Int = 4
          val _cursorIndexOfTitle: Int = 5
          val _cursorIndexOfArtistName: Int = 6
          val _cursorIndexOfAlbumTitle: Int = 7
          val _cursorIndexOfContentUri: Int = 8
          val _cursorIndexOfDurationMs: Int = 9
          val _cursorIndexOfCoverUri: Int = 10
          val _cursorIndexOfArtistId: Int = 11
          val _cursorIndexOfAlbumId: Int = 12
          val _cursorIndexOfIsLiked: Int = 13
          val _result: MutableList<PlaylistTrackRow> =
              ArrayList<PlaylistTrackRow>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: PlaylistTrackRow
            val _tmpPlaylistItemId: String
            _tmpPlaylistItemId = _cursor.getString(_cursorIndexOfPlaylistItemId)
            val _tmpPlaylistId: String
            _tmpPlaylistId = _cursor.getString(_cursorIndexOfPlaylistId)
            val _tmpTrackId: String
            _tmpTrackId = _cursor.getString(_cursorIndexOfTrackId)
            val _tmpPosition: Int
            _tmpPosition = _cursor.getInt(_cursorIndexOfPosition)
            val _tmpAddedAt: Long
            _tmpAddedAt = _cursor.getLong(_cursorIndexOfAddedAt)
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
            val _tmpIsLiked: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsLiked)
            _tmpIsLiked = _tmp != 0
            _item =
                PlaylistTrackRow(_tmpPlaylistItemId,_tmpPlaylistId,_tmpTrackId,_tmpPosition,_tmpAddedAt,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpArtistId,_tmpAlbumId,_tmpIsLiked)
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

  public override suspend fun getNextPlaylistPosition(playlistId: String): Int {
    val _sql: String =
        "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlist_id = ?"
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, playlistId)
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

  public companion object {
    @JvmStatic
    public fun getRequiredConverters(): List<Class<*>> = emptyList()
  }
}
