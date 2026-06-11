package com.aura.music.`data`.local

import androidx.room3.EntityInsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.getColumnIndexOrThrow
import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.prepare
import androidx.sqlite.step
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Double
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.reflect.KClass

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class AlbumSourceLinkDao_Impl(
  __db: RoomDatabase,
) : AlbumSourceLinkDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfAlbumSourceLinkEntity: EntityInsertAdapter<AlbumSourceLinkEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfAlbumSourceLinkEntity = object : EntityInsertAdapter<AlbumSourceLinkEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `album_source_links` (`id`,`album_id`,`usage_type`,`provider_name`,`provider_album_id`,`provider_artist_id`,`match_score`,`is_active_for_usage`,`metadata_json`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AlbumSourceLinkEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.albumId)
        statement.bindText(3, entity.usageType)
        statement.bindText(4, entity.providerName)
        statement.bindText(5, entity.providerAlbumId)
        val _tmpProviderArtistId: String? = entity.providerArtistId
        if (_tmpProviderArtistId == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpProviderArtistId)
        }
        val _tmpMatchScore: Double? = entity.matchScore
        if (_tmpMatchScore == null) {
          statement.bindNull(7)
        } else {
          statement.bindDouble(7, _tmpMatchScore)
        }
        val _tmp: Int = if (entity.isActiveForUsage) 1 else 0
        statement.bindLong(8, _tmp.toLong())
        val _tmpMetadataJson: String? = entity.metadataJson
        if (_tmpMetadataJson == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpMetadataJson)
        }
        statement.bindLong(10, entity.createdAt)
        statement.bindLong(11, entity.updatedAt)
      }
    }
  }

  public override suspend fun upsert(entity: AlbumSourceLinkEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfAlbumSourceLinkEntity.insert(_connection, entity)
  }

  public override suspend fun getActiveLink(albumId: String, usageType: String): AlbumSourceLinkEntity? {
    val _sql: String = """
        |
        |        SELECT * FROM album_source_links
        |        WHERE album_id = ?
        |          AND usage_type = ?
        |          AND is_active_for_usage = 1
        |        ORDER BY created_at DESC
        |        LIMIT 1
        |        
        """.trimMargin()
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, albumId)
        _argIndex = 2
        _stmt.bindText(_argIndex, usageType)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfAlbumId: Int = getColumnIndexOrThrow(_stmt, "album_id")
        val _columnIndexOfUsageType: Int = getColumnIndexOrThrow(_stmt, "usage_type")
        val _columnIndexOfProviderName: Int = getColumnIndexOrThrow(_stmt, "provider_name")
        val _columnIndexOfProviderAlbumId: Int = getColumnIndexOrThrow(_stmt, "provider_album_id")
        val _columnIndexOfProviderArtistId: Int = getColumnIndexOrThrow(_stmt, "provider_artist_id")
        val _columnIndexOfMatchScore: Int = getColumnIndexOrThrow(_stmt, "match_score")
        val _columnIndexOfIsActiveForUsage: Int = getColumnIndexOrThrow(_stmt, "is_active_for_usage")
        val _columnIndexOfMetadataJson: Int = getColumnIndexOrThrow(_stmt, "metadata_json")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: AlbumSourceLinkEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpAlbumId: String
          _tmpAlbumId = _stmt.getText(_columnIndexOfAlbumId)
          val _tmpUsageType: String
          _tmpUsageType = _stmt.getText(_columnIndexOfUsageType)
          val _tmpProviderName: String
          _tmpProviderName = _stmt.getText(_columnIndexOfProviderName)
          val _tmpProviderAlbumId: String
          _tmpProviderAlbumId = _stmt.getText(_columnIndexOfProviderAlbumId)
          val _tmpProviderArtistId: String?
          if (_stmt.isNull(_columnIndexOfProviderArtistId)) {
            _tmpProviderArtistId = null
          } else {
            _tmpProviderArtistId = _stmt.getText(_columnIndexOfProviderArtistId)
          }
          val _tmpMatchScore: Double?
          if (_stmt.isNull(_columnIndexOfMatchScore)) {
            _tmpMatchScore = null
          } else {
            _tmpMatchScore = _stmt.getDouble(_columnIndexOfMatchScore)
          }
          val _tmpIsActiveForUsage: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsActiveForUsage).toInt()
          _tmpIsActiveForUsage = _tmp != 0
          val _tmpMetadataJson: String?
          if (_stmt.isNull(_columnIndexOfMetadataJson)) {
            _tmpMetadataJson = null
          } else {
            _tmpMetadataJson = _stmt.getText(_columnIndexOfMetadataJson)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = AlbumSourceLinkEntity(_tmpId,_tmpAlbumId,_tmpUsageType,_tmpProviderName,_tmpProviderAlbumId,_tmpProviderArtistId,_tmpMatchScore,_tmpIsActiveForUsage,_tmpMetadataJson,_tmpCreatedAt,_tmpUpdatedAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
