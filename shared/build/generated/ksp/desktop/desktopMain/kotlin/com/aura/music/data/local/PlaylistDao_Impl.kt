package com.aura.music.`data`.local

import androidx.room3.EntityInsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.getTotalChangedRows
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

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class PlaylistDao_Impl(
  __db: RoomDatabase,
) : PlaylistDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfPlaylistEntity: EntityInsertAdapter<PlaylistEntity>

  private val __insertAdapterOfPlaylistItemEntity: EntityInsertAdapter<PlaylistItemEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfPlaylistEntity = object : EntityInsertAdapter<PlaylistEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `playlists` (`id`,`name`,`cover_uri`,`is_pinned`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PlaylistEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpCoverUri)
        }
        val _tmp: Int = if (entity.isPinned) 1 else 0
        statement.bindLong(4, _tmp.toLong())
        statement.bindLong(5, entity.createdAt)
        statement.bindLong(6, entity.updatedAt)
      }
    }
    this.__insertAdapterOfPlaylistItemEntity = object : EntityInsertAdapter<PlaylistItemEntity>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `playlist_items` (`id`,`playlist_id`,`track_id`,`position`,`added_at`,`added_from_context_type`,`added_from_context_id`) VALUES (?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PlaylistItemEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.playlistId)
        statement.bindText(3, entity.trackId)
        statement.bindLong(4, entity.position.toLong())
        statement.bindLong(5, entity.addedAt)
        val _tmpAddedFromContextType: String? = entity.addedFromContextType
        if (_tmpAddedFromContextType == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpAddedFromContextType)
        }
        val _tmpAddedFromContextId: String? = entity.addedFromContextId
        if (_tmpAddedFromContextId == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpAddedFromContextId)
        }
      }
    }
  }

  public override suspend fun insertPlaylist(entity: PlaylistEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfPlaylistEntity.insert(_connection, entity)
  }

  public override suspend fun insertPlaylistItem(entity: PlaylistItemEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfPlaylistItemEntity.insert(_connection, entity)
  }

  public override suspend fun getPlaylistCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM playlists"
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = 0
        val _columnIndexOfName: Int = 1
        val _columnIndexOfIsPinned: Int = 2
        val _columnIndexOfItemCount: Int = 3
        val _columnIndexOfUpdatedAt: Int = 4
        val _result: MutableList<PlaylistListRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: PlaylistListRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpIsPinned: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsPinned).toInt()
          _tmpIsPinned = _tmp != 0
          val _tmpItemCount: Int
          _tmpItemCount = _stmt.getLong(_columnIndexOfItemCount).toInt()
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = PlaylistListRow(_tmpId,_tmpName,_tmpIsPinned,_tmpItemCount,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, playlistId)
        val _columnIndexOfId: Int = 0
        val _columnIndexOfName: Int = 1
        val _columnIndexOfCoverUri: Int = 2
        val _columnIndexOfIsPinned: Int = 3
        val _columnIndexOfItemCount: Int = 4
        val _columnIndexOfUpdatedAt: Int = 5
        val _result: PlaylistDetailRow?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpIsPinned: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsPinned).toInt()
          _tmpIsPinned = _tmp != 0
          val _tmpItemCount: Int
          _tmpItemCount = _stmt.getLong(_columnIndexOfItemCount).toInt()
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = PlaylistDetailRow(_tmpId,_tmpName,_tmpCoverUri,_tmpIsPinned,_tmpItemCount,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, playlistId)
        val _columnIndexOfPlaylistItemId: Int = 0
        val _columnIndexOfPlaylistId: Int = 1
        val _columnIndexOfTrackId: Int = 2
        val _columnIndexOfPosition: Int = 3
        val _columnIndexOfAddedAt: Int = 4
        val _columnIndexOfTitle: Int = 5
        val _columnIndexOfArtistName: Int = 6
        val _columnIndexOfAlbumTitle: Int = 7
        val _columnIndexOfContentUri: Int = 8
        val _columnIndexOfDurationMs: Int = 9
        val _columnIndexOfCoverUri: Int = 10
        val _columnIndexOfArtistId: Int = 11
        val _columnIndexOfAlbumId: Int = 12
        val _columnIndexOfIsLiked: Int = 13
        val _result: MutableList<PlaylistTrackRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: PlaylistTrackRow
          val _tmpPlaylistItemId: String
          _tmpPlaylistItemId = _stmt.getText(_columnIndexOfPlaylistItemId)
          val _tmpPlaylistId: String
          _tmpPlaylistId = _stmt.getText(_columnIndexOfPlaylistId)
          val _tmpTrackId: String
          _tmpTrackId = _stmt.getText(_columnIndexOfTrackId)
          val _tmpPosition: Int
          _tmpPosition = _stmt.getLong(_columnIndexOfPosition).toInt()
          val _tmpAddedAt: Long
          _tmpAddedAt = _stmt.getLong(_columnIndexOfAddedAt)
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
          val _tmpIsLiked: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsLiked).toInt()
          _tmpIsLiked = _tmp != 0
          _item = PlaylistTrackRow(_tmpPlaylistItemId,_tmpPlaylistId,_tmpTrackId,_tmpPosition,_tmpAddedAt,_tmpTitle,_tmpArtistName,_tmpAlbumTitle,_tmpContentUri,_tmpDurationMs,_tmpCoverUri,_tmpArtistId,_tmpAlbumId,_tmpIsLiked)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getNextPlaylistPosition(playlistId: String): Int {
    val _sql: String = "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlist_id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, playlistId)
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

  public override suspend fun renamePlaylist(
    playlistId: String,
    name: String,
    updatedAt: Long,
  ): Int {
    val _sql: String = "UPDATE playlists SET name = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, name)
        _argIndex = 2
        _stmt.bindLong(_argIndex, updatedAt)
        _argIndex = 3
        _stmt.bindText(_argIndex, playlistId)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deletePlaylist(playlistId: String): Int {
    val _sql: String = "DELETE FROM playlists WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, playlistId)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deletePlaylistItem(playlistItemId: String): Int {
    val _sql: String = "DELETE FROM playlist_items WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, playlistItemId)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updatePlaylistItemPosition(playlistItemId: String, position: Int): Int {
    val _sql: String = "UPDATE playlist_items SET position = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, position.toLong())
        _argIndex = 2
        _stmt.bindText(_argIndex, playlistItemId)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun touchPlaylist(playlistId: String, updatedAt: Long): Int {
    val _sql: String = "UPDATE playlists SET updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, updatedAt)
        _argIndex = 2
        _stmt.bindText(_argIndex, playlistId)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
