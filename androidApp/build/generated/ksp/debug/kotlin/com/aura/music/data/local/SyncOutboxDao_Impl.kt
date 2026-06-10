package com.aura.music.`data`.local

import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.CoroutinesRoom
import androidx.room.CoroutinesRoom.Companion.execute
import androidx.room.EntityInsertionAdapter
import androidx.room.RoomDatabase
import androidx.room.RoomSQLiteQuery
import androidx.room.RoomSQLiteQuery.Companion.acquire
import androidx.room.SharedSQLiteStatement
import androidx.room.util.createCancellationSignal
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.query
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.Class
import java.util.ArrayList
import java.util.concurrent.Callable
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.jvm.JvmStatic

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class SyncOutboxDao_Impl(
  __db: RoomDatabase,
) : SyncOutboxDao {
  private val __db: RoomDatabase

  private val __insertionAdapterOfSyncOutboxEntity: EntityInsertionAdapter<SyncOutboxEntity>

  private val __preparedStmtOfUpdateStatus: SharedSQLiteStatement

  private val __preparedStmtOfDeleteOperation: SharedSQLiteStatement

  private val __preparedStmtOfClearAll: SharedSQLiteStatement
  init {
    this.__db = __db
    this.__insertionAdapterOfSyncOutboxEntity = object :
        EntityInsertionAdapter<SyncOutboxEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `sync_outbox` (`id`,`entity_type`,`entity_id`,`operation_type`,`payload_json`,`status`,`attempt_count`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: SyncOutboxEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.entityType)
        statement.bindString(3, entity.entityId)
        statement.bindString(4, entity.operationType)
        statement.bindString(5, entity.payloadJson)
        statement.bindString(6, entity.status)
        statement.bindLong(7, entity.attemptCount.toLong())
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
      }
    }
    this.__preparedStmtOfUpdateStatus = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String =
            "UPDATE sync_outbox SET status = ?, attempt_count = attempt_count + 1, updated_at = ? WHERE id = ?"
        return _query
      }
    }
    this.__preparedStmtOfDeleteOperation = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "DELETE FROM sync_outbox WHERE id = ?"
        return _query
      }
    }
    this.__preparedStmtOfClearAll = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "DELETE FROM sync_outbox"
        return _query
      }
    }
  }

  public override suspend fun insert(entity: SyncOutboxEntity): Unit = CoroutinesRoom.execute(__db,
      true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __insertionAdapterOfSyncOutboxEntity.insert(entity)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun updateStatus(
    id: String,
    status: String,
    now: Long,
  ): Unit = CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateStatus.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, status)
      _argIndex = 2
      _stmt.bindLong(_argIndex, now)
      _argIndex = 3
      _stmt.bindString(_argIndex, id)
      try {
        __db.beginTransaction()
        try {
          _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfUpdateStatus.release(_stmt)
      }
    }
  })

  public override suspend fun deleteOperation(id: String): Int = CoroutinesRoom.execute(__db, true,
      object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfDeleteOperation.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, id)
      try {
        __db.beginTransaction()
        try {
          val _result: Int = _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
          return _result
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfDeleteOperation.release(_stmt)
      }
    }
  })

  public override suspend fun clearAll(): Unit = CoroutinesRoom.execute(__db, true, object :
      Callable<Unit> {
    public override fun call() {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfClearAll.acquire()
      try {
        __db.beginTransaction()
        try {
          _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfClearAll.release(_stmt)
      }
    }
  })

  public override suspend fun getPendingOperations(): List<SyncOutboxEntity> {
    val _sql: String = "SELECT * FROM sync_outbox WHERE status = 'pending' ORDER BY created_at ASC"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<SyncOutboxEntity>> {
      public override fun call(): List<SyncOutboxEntity> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfEntityType: Int = getColumnIndexOrThrow(_cursor, "entity_type")
          val _cursorIndexOfEntityId: Int = getColumnIndexOrThrow(_cursor, "entity_id")
          val _cursorIndexOfOperationType: Int = getColumnIndexOrThrow(_cursor, "operation_type")
          val _cursorIndexOfPayloadJson: Int = getColumnIndexOrThrow(_cursor, "payload_json")
          val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_cursor, "status")
          val _cursorIndexOfAttemptCount: Int = getColumnIndexOrThrow(_cursor, "attempt_count")
          val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_cursor, "created_at")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _result: MutableList<SyncOutboxEntity> =
              ArrayList<SyncOutboxEntity>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: SyncOutboxEntity
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpEntityType: String
            _tmpEntityType = _cursor.getString(_cursorIndexOfEntityType)
            val _tmpEntityId: String
            _tmpEntityId = _cursor.getString(_cursorIndexOfEntityId)
            val _tmpOperationType: String
            _tmpOperationType = _cursor.getString(_cursorIndexOfOperationType)
            val _tmpPayloadJson: String
            _tmpPayloadJson = _cursor.getString(_cursorIndexOfPayloadJson)
            val _tmpStatus: String
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus)
            val _tmpAttemptCount: Int
            _tmpAttemptCount = _cursor.getInt(_cursorIndexOfAttemptCount)
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            _item =
                SyncOutboxEntity(_tmpId,_tmpEntityType,_tmpEntityId,_tmpOperationType,_tmpPayloadJson,_tmpStatus,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt)
            _result.add(_item)
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
