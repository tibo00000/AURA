package com.aura.music.`data`.local

import androidx.room3.EntityDeleteOrUpdateAdapter
import androidx.room3.EntityInsertAdapter
import androidx.room3.EntityUpsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.prepare
import androidx.sqlite.step
import javax.`annotation`.processing.Generated
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
internal class AlbumDao_Impl(
  __db: RoomDatabase,
) : AlbumDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfAlbumEntity: EntityUpsertAdapter<AlbumEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfAlbumEntity = EntityUpsertAdapter<AlbumEntity>(object : EntityInsertAdapter<AlbumEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `albums` (`id`,`primary_artist_id`,`title`,`normalized_title`,`cover_uri`,`artwork_origin`,`artwork_last_resolved_at`,`release_date`,`track_count`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AlbumEntity) {
        statement.bindText(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpPrimaryArtistId)
        }
        statement.bindText(3, entity.title)
        statement.bindText(4, entity.normalizedTitle)
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpCoverUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpArtworkOrigin)
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
          statement.bindText(8, _tmpReleaseDate)
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
    }, object : EntityDeleteOrUpdateAdapter<AlbumEntity>() {
      protected override fun createQuery(): String = "UPDATE `albums` SET `id` = ?,`primary_artist_id` = ?,`title` = ?,`normalized_title` = ?,`cover_uri` = ?,`artwork_origin` = ?,`artwork_last_resolved_at` = ?,`release_date` = ?,`track_count` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: AlbumEntity) {
        statement.bindText(1, entity.id)
        val _tmpPrimaryArtistId: String? = entity.primaryArtistId
        if (_tmpPrimaryArtistId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpPrimaryArtistId)
        }
        statement.bindText(3, entity.title)
        statement.bindText(4, entity.normalizedTitle)
        val _tmpCoverUri: String? = entity.coverUri
        if (_tmpCoverUri == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpCoverUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpArtworkOrigin)
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
          statement.bindText(8, _tmpReleaseDate)
        }
        val _tmpTrackCount: Int? = entity.trackCount
        if (_tmpTrackCount == null) {
          statement.bindNull(9)
        } else {
          statement.bindLong(9, _tmpTrackCount.toLong())
        }
        statement.bindLong(10, entity.createdAt)
        statement.bindLong(11, entity.updatedAt)
        statement.bindText(12, entity.id)
      }
    })
  }

  public override suspend fun upsertAlbums(items: List<AlbumEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfAlbumEntity.upsert(_connection, items)
  }

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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfTitle: Int = 1
        val _columnIndexOfArtistId: Int = 2
        val _columnIndexOfArtistName: Int = 3
        val _columnIndexOfCoverUri: Int = 4
        val _columnIndexOfTrackCount: Int = 5
        val _result: MutableList<AlbumBrowseRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: AlbumBrowseRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpArtistName: String?
          if (_stmt.isNull(_columnIndexOfArtistName)) {
            _tmpArtistName = null
          } else {
            _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpTrackCount: Int?
          if (_stmt.isNull(_columnIndexOfTrackCount)) {
            _tmpTrackCount = null
          } else {
            _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          }
          _item = AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = 0
        val _columnIndexOfTitle: Int = 1
        val _columnIndexOfArtistId: Int = 2
        val _columnIndexOfArtistName: Int = 3
        val _columnIndexOfCoverUri: Int = 4
        val _columnIndexOfTrackCount: Int = 5
        val _result: MutableList<AlbumBrowseRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: AlbumBrowseRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpArtistName: String?
          if (_stmt.isNull(_columnIndexOfArtistName)) {
            _tmpArtistName = null
          } else {
            _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpTrackCount: Int?
          if (_stmt.isNull(_columnIndexOfTrackCount)) {
            _tmpTrackCount = null
          } else {
            _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          }
          _item = AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, query)
        _argIndex = 2
        _stmt.bindText(_argIndex, query)
        _argIndex = 3
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfTitle: Int = 1
        val _columnIndexOfArtistId: Int = 2
        val _columnIndexOfArtistName: Int = 3
        val _columnIndexOfCoverUri: Int = 4
        val _columnIndexOfTrackCount: Int = 5
        val _result: MutableList<AlbumBrowseRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: AlbumBrowseRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpArtistName: String?
          if (_stmt.isNull(_columnIndexOfArtistName)) {
            _tmpArtistName = null
          } else {
            _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpTrackCount: Int?
          if (_stmt.isNull(_columnIndexOfTrackCount)) {
            _tmpTrackCount = null
          } else {
            _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          }
          _item = AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAlbumsForArtist(artistId: String, limit: Int): List<AlbumBrowseRow> {
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, artistId)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfTitle: Int = 1
        val _columnIndexOfArtistId: Int = 2
        val _columnIndexOfArtistName: Int = 3
        val _columnIndexOfCoverUri: Int = 4
        val _columnIndexOfTrackCount: Int = 5
        val _result: MutableList<AlbumBrowseRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: AlbumBrowseRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpArtistName: String?
          if (_stmt.isNull(_columnIndexOfArtistName)) {
            _tmpArtistName = null
          } else {
            _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpTrackCount: Int?
          if (_stmt.isNull(_columnIndexOfTrackCount)) {
            _tmpTrackCount = null
          } else {
            _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          }
          _item = AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, albumId)
        val _columnIndexOfId: Int = 0
        val _columnIndexOfTitle: Int = 1
        val _columnIndexOfArtistId: Int = 2
        val _columnIndexOfArtistName: Int = 3
        val _columnIndexOfCoverUri: Int = 4
        val _columnIndexOfReleaseDate: Int = 5
        val _columnIndexOfTrackCount: Int = 6
        val _result: AlbumDetailRow?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpArtistName: String?
          if (_stmt.isNull(_columnIndexOfArtistName)) {
            _tmpArtistName = null
          } else {
            _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpReleaseDate: String?
          if (_stmt.isNull(_columnIndexOfReleaseDate)) {
            _tmpReleaseDate = null
          } else {
            _tmpReleaseDate = _stmt.getText(_columnIndexOfReleaseDate)
          }
          val _tmpTrackCount: Int?
          if (_stmt.isNull(_columnIndexOfTrackCount)) {
            _tmpTrackCount = null
          } else {
            _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          }
          _result = AlbumDetailRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpReleaseDate,_tmpTrackCount)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAlbumByTitleAndArtist(title: String, artistName: String): AlbumBrowseRow? {
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, title)
        _argIndex = 2
        _stmt.bindText(_argIndex, artistName)
        val _columnIndexOfId: Int = 0
        val _columnIndexOfTitle: Int = 1
        val _columnIndexOfArtistId: Int = 2
        val _columnIndexOfArtistName: Int = 3
        val _columnIndexOfCoverUri: Int = 4
        val _columnIndexOfTrackCount: Int = 5
        val _result: AlbumBrowseRow?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistId: String?
          if (_stmt.isNull(_columnIndexOfArtistId)) {
            _tmpArtistId = null
          } else {
            _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          }
          val _tmpArtistName: String?
          if (_stmt.isNull(_columnIndexOfArtistName)) {
            _tmpArtistName = null
          } else {
            _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          }
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpTrackCount: Int?
          if (_stmt.isNull(_columnIndexOfTrackCount)) {
            _tmpTrackCount = null
          } else {
            _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          }
          _result = AlbumBrowseRow(_tmpId,_tmpTitle,_tmpArtistId,_tmpArtistName,_tmpCoverUri,_tmpTrackCount)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getArtworkLastResolvedAt(albumId: String): Long? {
    val _sql: String = "SELECT artwork_last_resolved_at FROM albums WHERE id = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, albumId)
        val _result: Long?
        if (_stmt.step()) {
          if (_stmt.isNull(0)) {
            _result = null
          } else {
            _result = _stmt.getLong(0)
          }
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateArtwork(
    albumId: String,
    coverUri: String,
    artworkOrigin: String,
    resolvedAt: Long,
    updatedAt: Long,
  ) {
    val _sql: String = """
        |
        |        UPDATE albums
        |        SET cover_uri = ?,
        |            artwork_origin = ?,
        |            artwork_last_resolved_at = ?,
        |            updated_at = ?
        |        WHERE id = ?
        |        
        """.trimMargin()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, coverUri)
        _argIndex = 2
        _stmt.bindText(_argIndex, artworkOrigin)
        _argIndex = 3
        _stmt.bindLong(_argIndex, resolvedAt)
        _argIndex = 4
        _stmt.bindLong(_argIndex, updatedAt)
        _argIndex = 5
        _stmt.bindText(_argIndex, albumId)
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
