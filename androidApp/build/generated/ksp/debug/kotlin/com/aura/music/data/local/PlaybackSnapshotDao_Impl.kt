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
import androidx.room.util.createCancellationSignal
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.query
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
public class PlaybackSnapshotDao_Impl(
  __db: RoomDatabase,
) : PlaybackSnapshotDao {
  private val __db: RoomDatabase

  private val __upsertionAdapterOfPlaybackSnapshotEntity:
      EntityUpsertionAdapter<PlaybackSnapshotEntity>
  init {
    this.__db = __db
    this.__upsertionAdapterOfPlaybackSnapshotEntity =
        EntityUpsertionAdapter<PlaybackSnapshotEntity>(object :
        EntityInsertionAdapter<PlaybackSnapshotEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT INTO `playback_snapshots` (`id`,`current_track_id`,`playback_context_type`,`playback_context_id`,`playback_context_index`,`position_ms`,`shuffle_enabled`,`repeat_mode`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement,
          entity: PlaybackSnapshotEntity) {
        statement.bindString(1, entity.id)
        val _tmpCurrentTrackId: String? = entity.currentTrackId
        if (_tmpCurrentTrackId == null) {
          statement.bindNull(2)
        } else {
          statement.bindString(2, _tmpCurrentTrackId)
        }
        val _tmpPlaybackContextType: String? = entity.playbackContextType
        if (_tmpPlaybackContextType == null) {
          statement.bindNull(3)
        } else {
          statement.bindString(3, _tmpPlaybackContextType)
        }
        val _tmpPlaybackContextId: String? = entity.playbackContextId
        if (_tmpPlaybackContextId == null) {
          statement.bindNull(4)
        } else {
          statement.bindString(4, _tmpPlaybackContextId)
        }
        val _tmpPlaybackContextIndex: Int? = entity.playbackContextIndex
        if (_tmpPlaybackContextIndex == null) {
          statement.bindNull(5)
        } else {
          statement.bindLong(5, _tmpPlaybackContextIndex.toLong())
        }
        statement.bindLong(6, entity.positionMs)
        val _tmp: Int = if (entity.shuffleEnabled) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        statement.bindString(8, entity.repeatMode)
        statement.bindLong(9, entity.updatedAt)
      }
    }, object : EntityDeletionOrUpdateAdapter<PlaybackSnapshotEntity>(__db) {
      protected override fun createQuery(): String =
          "UPDATE `playback_snapshots` SET `id` = ?,`current_track_id` = ?,`playback_context_type` = ?,`playback_context_id` = ?,`playback_context_index` = ?,`position_ms` = ?,`shuffle_enabled` = ?,`repeat_mode` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SupportSQLiteStatement,
          entity: PlaybackSnapshotEntity) {
        statement.bindString(1, entity.id)
        val _tmpCurrentTrackId: String? = entity.currentTrackId
        if (_tmpCurrentTrackId == null) {
          statement.bindNull(2)
        } else {
          statement.bindString(2, _tmpCurrentTrackId)
        }
        val _tmpPlaybackContextType: String? = entity.playbackContextType
        if (_tmpPlaybackContextType == null) {
          statement.bindNull(3)
        } else {
          statement.bindString(3, _tmpPlaybackContextType)
        }
        val _tmpPlaybackContextId: String? = entity.playbackContextId
        if (_tmpPlaybackContextId == null) {
          statement.bindNull(4)
        } else {
          statement.bindString(4, _tmpPlaybackContextId)
        }
        val _tmpPlaybackContextIndex: Int? = entity.playbackContextIndex
        if (_tmpPlaybackContextIndex == null) {
          statement.bindNull(5)
        } else {
          statement.bindLong(5, _tmpPlaybackContextIndex.toLong())
        }
        statement.bindLong(6, entity.positionMs)
        val _tmp: Int = if (entity.shuffleEnabled) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        statement.bindString(8, entity.repeatMode)
        statement.bindLong(9, entity.updatedAt)
        statement.bindString(10, entity.id)
      }
    })
  }

  public override suspend fun upsert(snapshot: PlaybackSnapshotEntity): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfPlaybackSnapshotEntity.upsert(snapshot)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun getActiveSnapshot(): PlaybackSnapshotEntity? {
    val _sql: String = "SELECT * FROM playback_snapshots WHERE id = 'active' LIMIT 1"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<PlaybackSnapshotEntity?> {
      public override fun call(): PlaybackSnapshotEntity? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfCurrentTrackId: Int = getColumnIndexOrThrow(_cursor, "current_track_id")
          val _cursorIndexOfPlaybackContextType: Int = getColumnIndexOrThrow(_cursor,
              "playback_context_type")
          val _cursorIndexOfPlaybackContextId: Int = getColumnIndexOrThrow(_cursor,
              "playback_context_id")
          val _cursorIndexOfPlaybackContextIndex: Int = getColumnIndexOrThrow(_cursor,
              "playback_context_index")
          val _cursorIndexOfPositionMs: Int = getColumnIndexOrThrow(_cursor, "position_ms")
          val _cursorIndexOfShuffleEnabled: Int = getColumnIndexOrThrow(_cursor, "shuffle_enabled")
          val _cursorIndexOfRepeatMode: Int = getColumnIndexOrThrow(_cursor, "repeat_mode")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _result: PlaybackSnapshotEntity?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpCurrentTrackId: String?
            if (_cursor.isNull(_cursorIndexOfCurrentTrackId)) {
              _tmpCurrentTrackId = null
            } else {
              _tmpCurrentTrackId = _cursor.getString(_cursorIndexOfCurrentTrackId)
            }
            val _tmpPlaybackContextType: String?
            if (_cursor.isNull(_cursorIndexOfPlaybackContextType)) {
              _tmpPlaybackContextType = null
            } else {
              _tmpPlaybackContextType = _cursor.getString(_cursorIndexOfPlaybackContextType)
            }
            val _tmpPlaybackContextId: String?
            if (_cursor.isNull(_cursorIndexOfPlaybackContextId)) {
              _tmpPlaybackContextId = null
            } else {
              _tmpPlaybackContextId = _cursor.getString(_cursorIndexOfPlaybackContextId)
            }
            val _tmpPlaybackContextIndex: Int?
            if (_cursor.isNull(_cursorIndexOfPlaybackContextIndex)) {
              _tmpPlaybackContextIndex = null
            } else {
              _tmpPlaybackContextIndex = _cursor.getInt(_cursorIndexOfPlaybackContextIndex)
            }
            val _tmpPositionMs: Long
            _tmpPositionMs = _cursor.getLong(_cursorIndexOfPositionMs)
            val _tmpShuffleEnabled: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfShuffleEnabled)
            _tmpShuffleEnabled = _tmp != 0
            val _tmpRepeatMode: String
            _tmpRepeatMode = _cursor.getString(_cursorIndexOfRepeatMode)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _result =
                PlaybackSnapshotEntity(_tmpId,_tmpCurrentTrackId,_tmpPlaybackContextType,_tmpPlaybackContextId,_tmpPlaybackContextIndex,_tmpPositionMs,_tmpShuffleEnabled,_tmpRepeatMode,_tmpUpdatedAt)
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
