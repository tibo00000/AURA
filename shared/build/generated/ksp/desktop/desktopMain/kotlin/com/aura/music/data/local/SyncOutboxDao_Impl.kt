package com.aura.music.`data`.local

import androidx.room3.EntityInsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.getColumnIndexOrThrow
import androidx.room3.util.getTotalChangedRows
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
internal class SyncOutboxDao_Impl(
  __db: RoomDatabase,
) : SyncOutboxDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfSyncOutboxEntity: EntityInsertAdapter<SyncOutboxEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfSyncOutboxEntity = object : EntityInsertAdapter<SyncOutboxEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `sync_outbox` (`id`,`entity_type`,`entity_id`,`operation_type`,`payload_json`,`status`,`attempt_count`,`created_at`,`updated_at`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: SyncOutboxEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.entityType)
        statement.bindText(3, entity.entityId)
        statement.bindText(4, entity.operationType)
        statement.bindText(5, entity.payloadJson)
        statement.bindText(6, entity.status)
        statement.bindLong(7, entity.attemptCount.toLong())
        statement.bindLong(8, entity.createdAt)
        statement.bindLong(9, entity.updatedAt)
      }
    }
  }

  public override suspend fun insert(entity: SyncOutboxEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfSyncOutboxEntity.insert(_connection, entity)
  }

  public override suspend fun getPendingOperations(): List<SyncOutboxEntity> {
    val _sql: String = "SELECT * FROM sync_outbox WHERE status = 'pending' ORDER BY created_at ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfEntityType: Int = getColumnIndexOrThrow(_stmt, "entity_type")
        val _columnIndexOfEntityId: Int = getColumnIndexOrThrow(_stmt, "entity_id")
        val _columnIndexOfOperationType: Int = getColumnIndexOrThrow(_stmt, "operation_type")
        val _columnIndexOfPayloadJson: Int = getColumnIndexOrThrow(_stmt, "payload_json")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfAttemptCount: Int = getColumnIndexOrThrow(_stmt, "attempt_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _result: MutableList<SyncOutboxEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: SyncOutboxEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpEntityType: String
          _tmpEntityType = _stmt.getText(_columnIndexOfEntityType)
          val _tmpEntityId: String
          _tmpEntityId = _stmt.getText(_columnIndexOfEntityId)
          val _tmpOperationType: String
          _tmpOperationType = _stmt.getText(_columnIndexOfOperationType)
          val _tmpPayloadJson: String
          _tmpPayloadJson = _stmt.getText(_columnIndexOfPayloadJson)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpAttemptCount: Int
          _tmpAttemptCount = _stmt.getLong(_columnIndexOfAttemptCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          _item = SyncOutboxEntity(_tmpId,_tmpEntityType,_tmpEntityId,_tmpOperationType,_tmpPayloadJson,_tmpStatus,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateStatus(
    id: String,
    status: String,
    now: Long,
  ) {
    val _sql: String = "UPDATE sync_outbox SET status = ?, attempt_count = attempt_count + 1, updated_at = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, status)
        _argIndex = 2
        _stmt.bindLong(_argIndex, now)
        _argIndex = 3
        _stmt.bindText(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOperation(id: String): Int {
    val _sql: String = "DELETE FROM sync_outbox WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, id)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clearAll() {
    val _sql: String = "DELETE FROM sync_outbox"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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
