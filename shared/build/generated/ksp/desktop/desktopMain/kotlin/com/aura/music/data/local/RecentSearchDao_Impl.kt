package com.aura.music.`data`.local

import androidx.room3.EntityDeleteOrUpdateAdapter
import androidx.room3.EntityInsertAdapter
import androidx.room3.EntityUpsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.prepare
import androidx.sqlite.step
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class RecentSearchDao_Impl(
  __db: RoomDatabase,
) : RecentSearchDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfRecentSearchEntity: EntityUpsertAdapter<RecentSearchEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfRecentSearchEntity = EntityUpsertAdapter<RecentSearchEntity>(object : EntityInsertAdapter<RecentSearchEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `recent_searches` (`id`,`query`,`searched_at`) VALUES (?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: RecentSearchEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.query)
        statement.bindLong(3, entity.searchedAt)
      }
    }, object : EntityDeleteOrUpdateAdapter<RecentSearchEntity>() {
      protected override fun createQuery(): String = "UPDATE `recent_searches` SET `id` = ?,`query` = ?,`searched_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: RecentSearchEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.query)
        statement.bindLong(3, entity.searchedAt)
        statement.bindText(4, entity.id)
      }
    })
  }

  public override suspend fun upsert(entity: RecentSearchEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfRecentSearchEntity.upsert(_connection, entity)
  }

  public override suspend fun getRecentQueries(limit: Int): List<String> {
    val _sql: String = "SELECT query FROM recent_searches ORDER BY searched_at DESC LIMIT ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getRecentSearchCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM recent_searches"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun trimTo(limit: Int) {
    val _sql: String = """
        |
        |        DELETE FROM recent_searches
        |        WHERE id NOT IN (
        |            SELECT id FROM recent_searches
        |            ORDER BY searched_at DESC
        |            LIMIT ?
        |        )
        |        
        """.trimMargin()
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, limit.toLong())
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
