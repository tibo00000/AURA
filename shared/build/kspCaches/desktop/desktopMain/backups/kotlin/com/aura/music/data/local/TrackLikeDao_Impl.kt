package com.aura.music.`data`.local

import androidx.room3.EntityInsertAdapter
import androidx.room3.RoomDatabase
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
import kotlin.reflect.KClass

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class TrackLikeDao_Impl(
  __db: RoomDatabase,
) : TrackLikeDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfTrackLikeEntity: EntityInsertAdapter<TrackLikeEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfTrackLikeEntity = object : EntityInsertAdapter<TrackLikeEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `track_likes` (`track_id`,`liked_at`,`source_context_type`,`source_context_id`) VALUES (?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: TrackLikeEntity) {
        statement.bindText(1, entity.trackId)
        statement.bindLong(2, entity.likedAt)
        val _tmpSourceContextType: String? = entity.sourceContextType
        if (_tmpSourceContextType == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpSourceContextType)
        }
        val _tmpSourceContextId: String? = entity.sourceContextId
        if (_tmpSourceContextId == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpSourceContextId)
        }
      }
    }
  }

  public override suspend fun insertLike(entity: TrackLikeEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfTrackLikeEntity.insert(_connection, entity)
  }

  public override suspend fun deleteLike(trackId: String) {
    val _sql: String = "DELETE FROM track_likes WHERE track_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, trackId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun setTrackIsLiked(
    trackId: String,
    liked: Boolean,
    updatedAt: Long,
  ) {
    val _sql: String = "UPDATE tracks SET is_liked = ?, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (liked) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 2
        _stmt.bindLong(_argIndex, updatedAt)
        _argIndex = 3
        _stmt.bindText(_argIndex, trackId)
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
