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
internal class UserSettingsDao_Impl(
  __db: RoomDatabase,
) : UserSettingsDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfUserSettingsEntity: EntityInsertAdapter<UserSettingsEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfUserSettingsEntity = object : EntityInsertAdapter<UserSettingsEntity>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `user_settings` (`id`,`sync_enabled`,`online_search_enabled`,`online_search_network_policy`,`stats_sync_network_policy`,`last_sync_at`,`sync_token`) VALUES (?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: UserSettingsEntity) {
        statement.bindText(1, entity.id)
        val _tmp: Int = if (entity.syncEnabled) 1 else 0
        statement.bindLong(2, _tmp.toLong())
        val _tmp_1: Int = if (entity.onlineSearchEnabled) 1 else 0
        statement.bindLong(3, _tmp_1.toLong())
        statement.bindText(4, entity.onlineSearchNetworkPolicy)
        statement.bindText(5, entity.statsSyncNetworkPolicy)
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
          statement.bindText(7, _tmpSyncToken)
        }
      }
    }
  }

  public override suspend fun insertOrReplace(entity: UserSettingsEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfUserSettingsEntity.insert(_connection, entity)
  }

  public override suspend fun getSettings(): UserSettingsEntity? {
    val _sql: String = "SELECT * FROM user_settings WHERE id = 'default' LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfSyncEnabled: Int = getColumnIndexOrThrow(_stmt, "sync_enabled")
        val _columnIndexOfOnlineSearchEnabled: Int = getColumnIndexOrThrow(_stmt, "online_search_enabled")
        val _columnIndexOfOnlineSearchNetworkPolicy: Int = getColumnIndexOrThrow(_stmt, "online_search_network_policy")
        val _columnIndexOfStatsSyncNetworkPolicy: Int = getColumnIndexOrThrow(_stmt, "stats_sync_network_policy")
        val _columnIndexOfLastSyncAt: Int = getColumnIndexOrThrow(_stmt, "last_sync_at")
        val _columnIndexOfSyncToken: Int = getColumnIndexOrThrow(_stmt, "sync_token")
        val _result: UserSettingsEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpSyncEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfSyncEnabled).toInt()
          _tmpSyncEnabled = _tmp != 0
          val _tmpOnlineSearchEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfOnlineSearchEnabled).toInt()
          _tmpOnlineSearchEnabled = _tmp_1 != 0
          val _tmpOnlineSearchNetworkPolicy: String
          _tmpOnlineSearchNetworkPolicy = _stmt.getText(_columnIndexOfOnlineSearchNetworkPolicy)
          val _tmpStatsSyncNetworkPolicy: String
          _tmpStatsSyncNetworkPolicy = _stmt.getText(_columnIndexOfStatsSyncNetworkPolicy)
          val _tmpLastSyncAt: Long?
          if (_stmt.isNull(_columnIndexOfLastSyncAt)) {
            _tmpLastSyncAt = null
          } else {
            _tmpLastSyncAt = _stmt.getLong(_columnIndexOfLastSyncAt)
          }
          val _tmpSyncToken: String?
          if (_stmt.isNull(_columnIndexOfSyncToken)) {
            _tmpSyncToken = null
          } else {
            _tmpSyncToken = _stmt.getText(_columnIndexOfSyncToken)
          }
          _result = UserSettingsEntity(_tmpId,_tmpSyncEnabled,_tmpOnlineSearchEnabled,_tmpOnlineSearchNetworkPolicy,_tmpStatsSyncNetworkPolicy,_tmpLastSyncAt,_tmpSyncToken)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateSyncEnabled(enabled: Boolean): Int {
    val _sql: String = "UPDATE user_settings SET sync_enabled = ? WHERE id = 'default'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (enabled) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateOnlineSearchEnabled(enabled: Boolean): Int {
    val _sql: String = "UPDATE user_settings SET online_search_enabled = ? WHERE id = 'default'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        val _tmp: Int = if (enabled) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateOnlineSearchNetworkPolicy(policy: String): Int {
    val _sql: String = "UPDATE user_settings SET online_search_network_policy = ? WHERE id = 'default'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, policy)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateStatsSyncNetworkPolicy(policy: String): Int {
    val _sql: String = "UPDATE user_settings SET stats_sync_network_policy = ? WHERE id = 'default'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, policy)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateSyncToken(token: String?, lastSyncAt: Long): Int {
    val _sql: String = "UPDATE user_settings SET sync_token = ?, last_sync_at = ? WHERE id = 'default'"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        if (token == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, token)
        }
        _argIndex = 2
        _stmt.bindLong(_argIndex, lastSyncAt)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
