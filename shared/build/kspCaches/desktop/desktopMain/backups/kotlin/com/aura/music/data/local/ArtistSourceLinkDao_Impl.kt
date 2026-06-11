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
internal class ArtistSourceLinkDao_Impl(
  __db: RoomDatabase,
) : ArtistSourceLinkDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfArtistSourceLinkEntity: EntityInsertAdapter<ArtistSourceLinkEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfArtistSourceLinkEntity = object : EntityInsertAdapter<ArtistSourceLinkEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `artist_source_links` (`id`,`artist_id`,`usage_type`,`provider_name`,`provider_artist_id`,`match_score`,`is_active_for_usage`,`metadata_json`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ArtistSourceLinkEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.artistId)
        statement.bindText(3, entity.usageType)
        statement.bindText(4, entity.providerName)
        statement.bindText(5, entity.providerArtistId)
        val _tmpMatchScore: Double? = entity.matchScore
        if (_tmpMatchScore == null) {
          statement.bindNull(6)
        } else {
          statement.bindDouble(6, _tmpMatchScore)
        }
        val _tmp: Int = if (entity.isActiveForUsage) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        val _tmpMetadataJson: String? = entity.metadataJson
        if (_tmpMetadataJson == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpMetadataJson)
        }
        statement.bindLong(9, entity.createdAt)
        statement.bindLong(10, entity.updatedAt)
      }
    }
  }

  public override suspend fun upsert(entity: ArtistSourceLinkEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfArtistSourceLinkEntity.insert(_connection, entity)
  }

  public override suspend fun getActiveLink(artistId: String, usageType: String): ArtistSourceLinkEntity? {
    val _sql: String = """
        |
        |        SELECT * FROM artist_source_links
        |        WHERE artist_id = ?
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
        _stmt.bindText(_argIndex, artistId)
        _argIndex = 2
        _stmt.bindText(_argIndex, usageType)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfArtistId: Int = getColumnIndexOrThrow(_stmt, "artist_id")
        val _columnIndexOfUsageType: Int = getColumnIndexOrThrow(_stmt, "usage_type")
        val _columnIndexOfProviderName: Int = getColumnIndexOrThrow(_stmt, "provider_name")
        val _columnIndexOfProviderArtistId: Int = getColumnIndexOrThrow(_stmt, "provider_artist_id")
        val _columnIndexOfMatchScore: Int = getColumnIndexOrThrow(_stmt, "match_score")
        val _columnIndexOfIsActiveForUsage: Int = getColumnIndexOrThrow(_stmt, "is_active_for_usage")
        val _columnIndexOfMetadataJson: Int = getColumnIndexOrThrow(_stmt, "metadata_json")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: ArtistSourceLinkEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpArtistId: String
          _tmpArtistId = _stmt.getText(_columnIndexOfArtistId)
          val _tmpUsageType: String
          _tmpUsageType = _stmt.getText(_columnIndexOfUsageType)
          val _tmpProviderName: String
          _tmpProviderName = _stmt.getText(_columnIndexOfProviderName)
          val _tmpProviderArtistId: String
          _tmpProviderArtistId = _stmt.getText(_columnIndexOfProviderArtistId)
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
          _result = ArtistSourceLinkEntity(_tmpId,_tmpArtistId,_tmpUsageType,_tmpProviderName,_tmpProviderArtistId,_tmpMatchScore,_tmpIsActiveForUsage,_tmpMetadataJson,_tmpCreatedAt,_tmpUpdatedAt)
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
