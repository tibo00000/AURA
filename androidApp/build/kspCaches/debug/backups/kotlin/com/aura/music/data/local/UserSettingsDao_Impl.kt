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
public class UserSettingsDao_Impl(
  __db: RoomDatabase,
) : UserSettingsDao {
  private val __db: RoomDatabase

  private val __insertionAdapterOfUserSettingsEntity: EntityInsertionAdapter<UserSettingsEntity>

  private val __preparedStmtOfUpdateSyncEnabled: SharedSQLiteStatement

  private val __preparedStmtOfUpdateOnlineSearchEnabled: SharedSQLiteStatement

  private val __preparedStmtOfUpdateOnlineSearchNetworkPolicy: SharedSQLiteStatement

  private val __preparedStmtOfUpdateStatsSyncNetworkPolicy: SharedSQLiteStatement

  private val __preparedStmtOfUpdateSyncToken: SharedSQLiteStatement
  init {
    this.__db = __db
    this.__insertionAdapterOfUserSettingsEntity = object :
        EntityInsertionAdapter<UserSettingsEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `user_settings` (`id`,`sync_enabled`,`online_search_enabled`,`online_search_network_policy`,`stats_sync_network_policy`,`last_sync_at`,`sync_token`) VALUES (?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: UserSettingsEntity) {
        statement.bindString(1, entity.id)
        val _tmp: Int = if (entity.syncEnabled) 1 else 0
        statement.bindLong(2, _tmp.toLong())
        val _tmp_1: Int = if (entity.onlineSearchEnabled) 1 else 0
        statement.bindLong(3, _tmp_1.toLong())
        statement.bindString(4, entity.onlineSearchNetworkPolicy)
        statement.bindString(5, entity.statsSyncNetworkPolicy)
        val _tmpLastSyncAt: Long? = entity.lastSyncAt
        if (_tmpLastSyncAt == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpLastSyncAt)
        }
        val _tmpSyncToken: String? = entity.syncToken
        if (_tmpSyncToken == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpSyncToken)
        }
      }
    }
    this.__preparedStmtOfUpdateSyncEnabled = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "UPDATE user_settings SET sync_enabled = ? WHERE id = 'default'"
        return _query
      }
    }
    this.__preparedStmtOfUpdateOnlineSearchEnabled = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String =
            "UPDATE user_settings SET online_search_enabled = ? WHERE id = 'default'"
        return _query
      }
    }
    this.__preparedStmtOfUpdateOnlineSearchNetworkPolicy = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String =
            "UPDATE user_settings SET online_search_network_policy = ? WHERE id = 'default'"
        return _query
      }
    }
    this.__preparedStmtOfUpdateStatsSyncNetworkPolicy = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String =
            "UPDATE user_settings SET stats_sync_network_policy = ? WHERE id = 'default'"
        return _query
      }
    }
    this.__preparedStmtOfUpdateSyncToken = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String =
            "UPDATE user_settings SET sync_token = ?, last_sync_at = ? WHERE id = 'default'"
        return _query
      }
    }
  }

  public override suspend fun insertOrReplace(entity: UserSettingsEntity): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __insertionAdapterOfUserSettingsEntity.insert(entity)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun updateSyncEnabled(enabled: Boolean): Int =
      CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateSyncEnabled.acquire()
      var _argIndex: Int = 1
      val _tmp: Int = if (enabled) 1 else 0
      _stmt.bindLong(_argIndex, _tmp.toLong())
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
        __preparedStmtOfUpdateSyncEnabled.release(_stmt)
      }
    }
  })

  public override suspend fun updateOnlineSearchEnabled(enabled: Boolean): Int =
      CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateOnlineSearchEnabled.acquire()
      var _argIndex: Int = 1
      val _tmp: Int = if (enabled) 1 else 0
      _stmt.bindLong(_argIndex, _tmp.toLong())
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
        __preparedStmtOfUpdateOnlineSearchEnabled.release(_stmt)
      }
    }
  })

  public override suspend fun updateOnlineSearchNetworkPolicy(policy: String): Int =
      CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateOnlineSearchNetworkPolicy.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, policy)
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
        __preparedStmtOfUpdateOnlineSearchNetworkPolicy.release(_stmt)
      }
    }
  })

  public override suspend fun updateStatsSyncNetworkPolicy(policy: String): Int =
      CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateStatsSyncNetworkPolicy.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, policy)
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
        __preparedStmtOfUpdateStatsSyncNetworkPolicy.release(_stmt)
      }
    }
  })

  public override suspend fun updateSyncToken(token: String?, lastSyncAt: Long): Int =
      CoroutinesRoom.execute(__db, true, object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfUpdateSyncToken.acquire()
      var _argIndex: Int = 1
      if (token == null) {
        _stmt.bindNull(_argIndex)
      } else {
        _stmt.bindString(_argIndex, token)
      }
      _argIndex = 2
      _stmt.bindLong(_argIndex, lastSyncAt)
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
        __preparedStmtOfUpdateSyncToken.release(_stmt)
      }
    }
  })

  public override suspend fun getSettings(): UserSettingsEntity? {
    val _sql: String = "SELECT * FROM user_settings WHERE id = 'default' LIMIT 1"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<UserSettingsEntity?> {
      public override fun call(): UserSettingsEntity? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfSyncEnabled: Int = getColumnIndexOrThrow(_cursor, "sync_enabled")
          val _cursorIndexOfOnlineSearchEnabled: Int = getColumnIndexOrThrow(_cursor,
              "online_search_enabled")
          val _cursorIndexOfOnlineSearchNetworkPolicy: Int = getColumnIndexOrThrow(_cursor,
              "online_search_network_policy")
          val _cursorIndexOfStatsSyncNetworkPolicy: Int = getColumnIndexOrThrow(_cursor,
              "stats_sync_network_policy")
          val _cursorIndexOfLastSyncAt: Int = getColumnIndexOrThrow(_cursor, "last_sync_at")
          val _cursorIndexOfSyncToken: Int = getColumnIndexOrThrow(_cursor, "sync_token")
          val _result: UserSettingsEntity?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpSyncEnabled: Boolean
            val _tmp: Int
            _tmp = _cursor.getInt(_cursorIndexOfSyncEnabled)
            _tmpSyncEnabled = _tmp != 0
            val _tmpOnlineSearchEnabled: Boolean
            val _tmp_1: Int
            _tmp_1 = _cursor.getInt(_cursorIndexOfOnlineSearchEnabled)
            _tmpOnlineSearchEnabled = _tmp_1 != 0
            val _tmpOnlineSearchNetworkPolicy: String
            _tmpOnlineSearchNetworkPolicy =
                _cursor.getString(_cursorIndexOfOnlineSearchNetworkPolicy)
            val _tmpStatsSyncNetworkPolicy: String
            _tmpStatsSyncNetworkPolicy = _cursor.getString(_cursorIndexOfStatsSyncNetworkPolicy)
            val _tmpLastSyncAt: Long?
            if (_cursor.isNull(_cursorIndexOfLastSyncAt)) {
              _tmpLastSyncAt = null
            } else {
              _tmpLastSyncAt = _cursor.getLong(_cursorIndexOfLastSyncAt)
            }
            val _tmpSyncToken: String?
            if (_cursor.isNull(_cursorIndexOfSyncToken)) {
              _tmpSyncToken = null
            } else {
              _tmpSyncToken = _cursor.getString(_cursorIndexOfSyncToken)
            }
            _result =
                UserSettingsEntity(_tmpId,_tmpSyncEnabled,_tmpOnlineSearchEnabled,_tmpOnlineSearchNetworkPolicy,_tmpStatsSyncNetworkPolicy,_tmpLastSyncAt,_tmpSyncToken)
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
