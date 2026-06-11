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
internal class ArtistDao_Impl(
  __db: RoomDatabase,
) : ArtistDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfArtistEntity: EntityUpsertAdapter<ArtistEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfArtistEntity = EntityUpsertAdapter<ArtistEntity>(object : EntityInsertAdapter<ArtistEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `artists` (`id`,`name`,`normalized_name`,`picture_uri`,`artwork_origin`,`artwork_last_resolved_at`,`summary`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ArtistEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.normalizedName)
        val _tmpPictureUri: String? = entity.pictureUri
        if (_tmpPictureUri == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpPictureUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpArtworkOrigin)
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
          statement.bindText(7, _tmpSummary)
        }
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<ArtistEntity>() {
      protected override fun createQuery(): String = "UPDATE `artists` SET `id` = ?,`name` = ?,`normalized_name` = ?,`picture_uri` = ?,`artwork_origin` = ?,`artwork_last_resolved_at` = ?,`summary` = ?,`created_at` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: ArtistEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.normalizedName)
        val _tmpPictureUri: String? = entity.pictureUri
        if (_tmpPictureUri == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpPictureUri)
        }
        val _tmpArtworkOrigin: String? = entity.artworkOrigin
        if (_tmpArtworkOrigin == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpArtworkOrigin)
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
          statement.bindText(7, _tmpSummary)
        }
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
        statement.bindText(10, entity.id)
      }
    })
  }

  public override suspend fun upsertArtists(items: List<ArtistEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfArtistEntity.upsert(_connection, items)
  }

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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfName: Int = 1
        val _columnIndexOfPictureUri: Int = 2
        val _columnIndexOfTrackCount: Int = 3
        val _columnIndexOfAlbumCount: Int = 4
        val _columnIndexOfUpdatedAt: Int = 5
        val _result: MutableList<ArtistBrowseRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: ArtistBrowseRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpPictureUri: String?
          if (_stmt.isNull(_columnIndexOfPictureUri)) {
            _tmpPictureUri = null
          } else {
            _tmpPictureUri = _stmt.getText(_columnIndexOfPictureUri)
          }
          val _tmpTrackCount: Int
          _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          val _tmpAlbumCount: Int
          _tmpAlbumCount = _stmt.getLong(_columnIndexOfAlbumCount).toInt()
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ArtistBrowseRow(_tmpId,_tmpName,_tmpPictureUri,_tmpTrackCount,_tmpAlbumCount,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = 0
        val _columnIndexOfName: Int = 1
        val _columnIndexOfPictureUri: Int = 2
        val _columnIndexOfTrackCount: Int = 3
        val _columnIndexOfAlbumCount: Int = 4
        val _columnIndexOfUpdatedAt: Int = 5
        val _result: MutableList<ArtistBrowseRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: ArtistBrowseRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpPictureUri: String?
          if (_stmt.isNull(_columnIndexOfPictureUri)) {
            _tmpPictureUri = null
          } else {
            _tmpPictureUri = _stmt.getText(_columnIndexOfPictureUri)
          }
          val _tmpTrackCount: Int
          _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          val _tmpAlbumCount: Int
          _tmpAlbumCount = _stmt.getLong(_columnIndexOfAlbumCount).toInt()
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ArtistBrowseRow(_tmpId,_tmpName,_tmpPictureUri,_tmpTrackCount,_tmpAlbumCount,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, query)
        _argIndex = 2
        _stmt.bindLong(_argIndex, limit.toLong())
        val _columnIndexOfId: Int = 0
        val _columnIndexOfName: Int = 1
        val _columnIndexOfPictureUri: Int = 2
        val _columnIndexOfTrackCount: Int = 3
        val _columnIndexOfAlbumCount: Int = 4
        val _columnIndexOfUpdatedAt: Int = 5
        val _result: MutableList<ArtistBrowseRow> = mutableListOf()
        while (_stmt.step()) {
          val _item: ArtistBrowseRow
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpPictureUri: String?
          if (_stmt.isNull(_columnIndexOfPictureUri)) {
            _tmpPictureUri = null
          } else {
            _tmpPictureUri = _stmt.getText(_columnIndexOfPictureUri)
          }
          val _tmpTrackCount: Int
          _tmpTrackCount = _stmt.getLong(_columnIndexOfTrackCount).toInt()
          val _tmpAlbumCount: Int
          _tmpAlbumCount = _stmt.getLong(_columnIndexOfAlbumCount).toInt()
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = ArtistBrowseRow(_tmpId,_tmpName,_tmpPictureUri,_tmpTrackCount,_tmpAlbumCount,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, artistId)
        val _columnIndexOfId: Int = 0
        val _columnIndexOfName: Int = 1
        val _columnIndexOfPictureUri: Int = 2
        val _columnIndexOfSummary: Int = 3
        val _result: ArtistDetailRow?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpPictureUri: String?
          if (_stmt.isNull(_columnIndexOfPictureUri)) {
            _tmpPictureUri = null
          } else {
            _tmpPictureUri = _stmt.getText(_columnIndexOfPictureUri)
          }
          val _tmpSummary: String?
          if (_stmt.isNull(_columnIndexOfSummary)) {
            _tmpSummary = null
          } else {
            _tmpSummary = _stmt.getText(_columnIndexOfSummary)
          }
          _result = ArtistDetailRow(_tmpId,_tmpName,_tmpPictureUri,_tmpSummary)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getArtworkLastResolvedAt(artistId: String): Long? {
    val _sql: String = "SELECT artwork_last_resolved_at FROM artists WHERE id = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, artistId)
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
    artistId: String,
    pictureUri: String,
    artworkOrigin: String,
    resolvedAt: Long,
    updatedAt: Long,
  ) {
    val _sql: String = """
        |
        |        UPDATE artists
        |        SET picture_uri = ?,
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
        _stmt.bindText(_argIndex, pictureUri)
        _argIndex = 2
        _stmt.bindText(_argIndex, artworkOrigin)
        _argIndex = 3
        _stmt.bindLong(_argIndex, resolvedAt)
        _argIndex = 4
        _stmt.bindLong(_argIndex, updatedAt)
        _argIndex = 5
        _stmt.bindText(_argIndex, artistId)
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
