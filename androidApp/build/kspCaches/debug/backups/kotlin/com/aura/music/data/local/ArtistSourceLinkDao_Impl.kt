package com.aura.music.`data`.local

import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.CoroutinesRoom
import androidx.room.CoroutinesRoom.Companion.execute
import androidx.room.EntityInsertionAdapter
import androidx.room.RoomDatabase
import androidx.room.RoomSQLiteQuery
import androidx.room.RoomSQLiteQuery.Companion.acquire
import androidx.room.util.createCancellationSignal
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.query
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.Class
import java.util.concurrent.Callable
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Double
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.jvm.JvmStatic

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class ArtistSourceLinkDao_Impl(
  __db: RoomDatabase,
) : ArtistSourceLinkDao {
  private val __db: RoomDatabase

  private val __insertionAdapterOfArtistSourceLinkEntity:
      EntityInsertionAdapter<ArtistSourceLinkEntity>
  init {
    this.__db = __db
    this.__insertionAdapterOfArtistSourceLinkEntity = object :
        EntityInsertionAdapter<ArtistSourceLinkEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `artist_source_links` (`id`,`artist_id`,`usage_type`,`provider_name`,`provider_artist_id`,`match_score`,`is_active_for_usage`,`metadata_json`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement,
          entity: ArtistSourceLinkEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.artistId)
        statement.bindString(3, entity.usageType)
        statement.bindString(4, entity.providerName)
        statement.bindString(5, entity.providerArtistId)
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
          statement.bindString(8, _tmpMetadataJson)
        }
        statement.bindLong(9, entity.createdAt)
        statement.bindLong(10, entity.updatedAt)
      }
    }
  }

  public override suspend fun upsert(entity: ArtistSourceLinkEntity): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __insertionAdapterOfArtistSourceLinkEntity.insert(entity)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun getActiveLink(artistId: String, usageType: String):
      ArtistSourceLinkEntity? {
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
    val _statement: RoomSQLiteQuery = acquire(_sql, 2)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, artistId)
    _argIndex = 2
    _statement.bindString(_argIndex, usageType)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<ArtistSourceLinkEntity?> {
      public override fun call(): ArtistSourceLinkEntity? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfArtistId: Int = getColumnIndexOrThrow(_cursor, "artist_id")
          val _cursorIndexOfUsageType: Int = getColumnIndexOrThrow(_cursor, "usage_type")
          val _cursorIndexOfProviderName: Int = getColumnIndexOrThrow(_cursor, "provider_name")
          val _cursorIndexOfProviderArtistId: Int = getColumnIndexOrThrow(_cursor,
              "provider_artist_id")
          val _cursorIndexOfMatchScore: Int = getColumnIndexOrThrow(_cursor, "match_score")
          val _cursorIndexOfIsActiveForUsage: Int = getColumnIndexOrThrow(_cursor,
              "is_active_for_usage")
          val _cursorIndexOfMetadataJson: Int = getColumnIndexOrThrow(_cursor, "metadata_json")
          val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_cursor, "created_at")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _result: ArtistSourceLinkEntity?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpArtistId: String
            _tmpArtistId = _cursor.getString(_cursorIndexOfArtistId)
            val _tmpUsageType: String
            _tmpUsageType = _cursor.getString(_cursorIndexOfUsageType)
            val _tmpProviderName: String
            _tmpProviderName = _cursor.getString(_cursorIndexOfProviderName)
            val _tmpProviderArtistId: String
            _tmpProviderArtistId = _cursor.getString(_cursorIndexOfProviderArtistId)
            val _tmpMatchScore: Double?
            if (_cursor.isNull(_cursorIndexOfMatchScore)) {
              _tmpMatchScore = null
            } else {
              _tmpMatchScore = _cursor.getDouble(_cursorIndexOfMatchScore)
            }
            val _tmpIsActiveForUsage: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfIsActiveForUsage)
            _tmpIsActiveForUsage = _tmp != 0
            val _tmpMetadataJson: String?
            if (_cursor.isNull(_cursorIndexOfMetadataJson)) {
              _tmpMetadataJson = null
            } else {
              _tmpMetadataJson = _cursor.getString(_cursorIndexOfMetadataJson)
            }
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _result =
                ArtistSourceLinkEntity(_tmpId,_tmpArtistId,_tmpUsageType,_tmpProviderName,_tmpProviderArtistId,_tmpMatchScore,_tmpIsActiveForUsage,_tmpMetadataJson,_tmpCreatedAt,_tmpUpdatedAt)
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
