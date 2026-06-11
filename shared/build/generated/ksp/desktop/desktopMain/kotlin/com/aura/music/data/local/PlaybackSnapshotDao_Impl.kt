package com.aura.music.`data`.local

import androidx.room3.EntityDeleteOrUpdateAdapter
import androidx.room3.EntityInsertAdapter
import androidx.room3.EntityUpsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.getColumnIndexOrThrow
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
internal class PlaybackSnapshotDao_Impl(
  __db: RoomDatabase,
) : PlaybackSnapshotDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfPlaybackSnapshotEntity: EntityUpsertAdapter<PlaybackSnapshotEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfPlaybackSnapshotEntity = EntityUpsertAdapter<PlaybackSnapshotEntity>(object : EntityInsertAdapter<PlaybackSnapshotEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `playback_snapshots` (`id`,`current_track_id`,`playback_context_type`,`playback_context_id`,`playback_context_index`,`position_ms`,`shuffle_enabled`,`repeat_mode`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PlaybackSnapshotEntity) {
        statement.bindText(1, entity.id)
        val _tmpCurrentTrackId: String? = entity.currentTrackId
        if (_tmpCurrentTrackId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpCurrentTrackId)
        }
        val _tmpPlaybackContextType: String? = entity.playbackContextType
        if (_tmpPlaybackContextType == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpPlaybackContextType)
        }
        val _tmpPlaybackContextId: String? = entity.playbackContextId
        if (_tmpPlaybackContextId == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpPlaybackContextId)
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
        statement.bindText(8, entity.repeatMode)
        statement.bindLong(9, entity.updatedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<PlaybackSnapshotEntity>() {
      protected override fun createQuery(): String = "UPDATE `playback_snapshots` SET `id` = ?,`current_track_id` = ?,`playback_context_type` = ?,`playback_context_id` = ?,`playback_context_index` = ?,`position_ms` = ?,`shuffle_enabled` = ?,`repeat_mode` = ?,`updated_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: PlaybackSnapshotEntity) {
        statement.bindText(1, entity.id)
        val _tmpCurrentTrackId: String? = entity.currentTrackId
        if (_tmpCurrentTrackId == null) {
          statement.bindNull(2)
        } else {
          statement.bindText(2, _tmpCurrentTrackId)
        }
        val _tmpPlaybackContextType: String? = entity.playbackContextType
        if (_tmpPlaybackContextType == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpPlaybackContextType)
        }
        val _tmpPlaybackContextId: String? = entity.playbackContextId
        if (_tmpPlaybackContextId == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpPlaybackContextId)
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
        statement.bindText(8, entity.repeatMode)
        statement.bindLong(9, entity.updatedAt)
        statement.bindText(10, entity.id)
      }
    })
  }

  public override suspend fun upsert(snapshot: PlaybackSnapshotEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfPlaybackSnapshotEntity.upsert(_connection, snapshot)
  }

  public override suspend fun getActiveSnapshot(): PlaybackSnapshotEntity? {
    val _sql: String = "SELECT * FROM playback_snapshots WHERE id = 'active' LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfCurrentTrackId: Int = getColumnIndexOrThrow(_stmt, "current_track_id")
        val _columnIndexOfPlaybackContextType: Int = getColumnIndexOrThrow(_stmt, "playback_context_type")
        val _columnIndexOfPlaybackContextId: Int = getColumnIndexOrThrow(_stmt, "playback_context_id")
        val _columnIndexOfPlaybackContextIndex: Int = getColumnIndexOrThrow(_stmt, "playback_context_index")
        val _columnIndexOfPositionMs: Int = getColumnIndexOrThrow(_stmt, "position_ms")
        val _columnIndexOfShuffleEnabled: Int = getColumnIndexOrThrow(_stmt, "shuffle_enabled")
        val _columnIndexOfRepeatMode: Int = getColumnIndexOrThrow(_stmt, "repeat_mode")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: PlaybackSnapshotEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpCurrentTrackId: String?
          if (_stmt.isNull(_columnIndexOfCurrentTrackId)) {
            _tmpCurrentTrackId = null
          } else {
            _tmpCurrentTrackId = _stmt.getText(_columnIndexOfCurrentTrackId)
          }
          val _tmpPlaybackContextType: String?
          if (_stmt.isNull(_columnIndexOfPlaybackContextType)) {
            _tmpPlaybackContextType = null
          } else {
            _tmpPlaybackContextType = _stmt.getText(_columnIndexOfPlaybackContextType)
          }
          val _tmpPlaybackContextId: String?
          if (_stmt.isNull(_columnIndexOfPlaybackContextId)) {
            _tmpPlaybackContextId = null
          } else {
            _tmpPlaybackContextId = _stmt.getText(_columnIndexOfPlaybackContextId)
          }
          val _tmpPlaybackContextIndex: Int?
          if (_stmt.isNull(_columnIndexOfPlaybackContextIndex)) {
            _tmpPlaybackContextIndex = null
          } else {
            _tmpPlaybackContextIndex = _stmt.getLong(_columnIndexOfPlaybackContextIndex).toInt()
          }
          val _tmpPositionMs: Long
          _tmpPositionMs = _stmt.getLong(_columnIndexOfPositionMs)
          val _tmpShuffleEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfShuffleEnabled).toInt()
          _tmpShuffleEnabled = _tmp != 0
          val _tmpRepeatMode: String
          _tmpRepeatMode = _stmt.getText(_columnIndexOfRepeatMode)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _result = PlaybackSnapshotEntity(_tmpId,_tmpCurrentTrackId,_tmpPlaybackContextType,_tmpPlaybackContextId,_tmpPlaybackContextIndex,_tmpPositionMs,_tmpShuffleEnabled,_tmpRepeatMode,_tmpUpdatedAt)
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
