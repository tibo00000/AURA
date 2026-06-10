package com.aura.music.`data`.local

import androidx.room.CoroutinesRoom
import androidx.room.EntityInsertionAdapter
import androidx.room.RoomDatabase
import androidx.room.SharedSQLiteStatement
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.Class
import java.util.concurrent.Callable
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.jvm.JvmStatic

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class TrackLikeDao_Impl(
  __db: RoomDatabase,
) : TrackLikeDao {
  private val __db: RoomDatabase

  private val __insertionAdapterOfTrackLikeEntity: EntityInsertionAdapter<TrackLikeEntity>

  private val __preparedStmtOfDeleteLike: SharedSQLiteStatement

  private val __preparedStmtOfSetTrackIsLiked: SharedSQLiteStatement
  init {
    this.__db = __db
    this.__insertionAdapterOfTrackLikeEntity = object :
        EntityInsertionAdapter<TrackLikeEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `track_likes` (`track_id`,`liked_at`,`source_context_type`,`source_context_id`) VALUES (?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: TrackLikeEntity) {
        statement.bindString(1, entity.trackId)
        statement.bindLong(2, entity.likedAt)
        val _tmpSourceContextType: String? = entity.sourceContextType
        if (_tmpSourceContextType == null) {
          statement.bindNull(3)
        } else {
          statement.bindString(3, _tmpSourceContextType)
        }
        val _tmpSourceContextId: String? = entity.sourceContextId
        if (_tmpSourceContextId == null) {
          statement.bindNull(4)
        } else {
          statement.bindString(4, _tmpSourceContextId)
        }
      }
    }
    this.__preparedStmtOfDeleteLike = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "DELETE FROM track_likes WHERE track_id = ?"
        return _query
      }
    }
    this.__preparedStmtOfSetTrackIsLiked = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "UPDATE tracks SET is_liked = ?, updated_at = ? WHERE id = ?"
        return _query
      }
    }
  }

  public override suspend fun insertLike(entity: TrackLikeEntity): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __insertionAdapterOfTrackLikeEntity.insert(entity)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun deleteLike(trackId: String): Unit = CoroutinesRoom.execute(__db, true,
      object : Callable<Unit> {
    public override fun call() {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfDeleteLike.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, trackId)
      try {
        __db.beginTransaction()
        try {
          _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfDeleteLike.release(_stmt)
      }
    }
  })

  public override suspend fun setTrackIsLiked(
    trackId: String,
    liked: Boolean,
    updatedAt: Long,
  ): Unit = CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfSetTrackIsLiked.acquire()
      var _argIndex: Int = 1
      val _tmp: Int = if (liked) 1 else 0
      _stmt.bindLong(_argIndex, _tmp.toLong())
      _argIndex = 2
      _stmt.bindLong(_argIndex, updatedAt)
      _argIndex = 3
      _stmt.bindString(_argIndex, trackId)
      try {
        __db.beginTransaction()
        try {
          _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfSetTrackIsLiked.release(_stmt)
      }
    }
  })

  public companion object {
    @JvmStatic
    public fun getRequiredConverters(): List<Class<*>> = emptyList()
  }
}
