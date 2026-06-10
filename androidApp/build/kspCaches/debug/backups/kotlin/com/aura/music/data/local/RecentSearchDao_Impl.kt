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
import androidx.room.SharedSQLiteStatement
import androidx.room.util.createCancellationSignal
import androidx.room.util.query
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.Class
import java.util.ArrayList
import java.util.concurrent.Callable
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.jvm.JvmStatic

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class RecentSearchDao_Impl(
  __db: RoomDatabase,
) : RecentSearchDao {
  private val __db: RoomDatabase

  private val __preparedStmtOfTrimTo: SharedSQLiteStatement

  private val __upsertionAdapterOfRecentSearchEntity: EntityUpsertionAdapter<RecentSearchEntity>
  init {
    this.__db = __db
    this.__preparedStmtOfTrimTo = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = """
            |
            |        DELETE FROM recent_searches
            |        WHERE id NOT IN (
            |            SELECT id FROM recent_searches
            |            ORDER BY searched_at DESC
            |            LIMIT ?
            |        )
            |        
            """.trimMargin()
        return _query
      }
    }
    this.__upsertionAdapterOfRecentSearchEntity = EntityUpsertionAdapter<RecentSearchEntity>(object
        : EntityInsertionAdapter<RecentSearchEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT INTO `recent_searches` (`id`,`query`,`searched_at`) VALUES (?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: RecentSearchEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.query)
        statement.bindLong(3, entity.searchedAt)
      }
    }, object : EntityDeletionOrUpdateAdapter<RecentSearchEntity>(__db) {
      protected override fun createQuery(): String =
          "UPDATE `recent_searches` SET `id` = ?,`query` = ?,`searched_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SupportSQLiteStatement, entity: RecentSearchEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.query)
        statement.bindLong(3, entity.searchedAt)
        statement.bindString(4, entity.id)
      }
    })
  }

  public override suspend fun trimTo(limit: Int): Unit = CoroutinesRoom.execute(__db, true, object :
      Callable<Unit> {
    public override fun call() {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfTrimTo.acquire()
      var _argIndex: Int = 1
      _stmt.bindLong(_argIndex, limit.toLong())
      try {
        __db.beginTransaction()
        try {
          _stmt.executeUpdateDelete()
          __db.setTransactionSuccessful()
        } finally {
          __db.endTransaction()
        }
      } finally {
        __preparedStmtOfTrimTo.release(_stmt)
      }
    }
  })

  public override suspend fun upsert(entity: RecentSearchEntity): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfRecentSearchEntity.upsert(entity)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun getRecentQueries(limit: Int): List<String> {
    val _sql: String = "SELECT query FROM recent_searches ORDER BY searched_at DESC LIMIT ?"
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindLong(_argIndex, limit.toLong())
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<String>> {
      public override fun call(): List<String> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _result: MutableList<String> = ArrayList<String>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: String
            _item = _cursor.getString(0)
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

  public override suspend fun getRecentSearchCount(): Int {
    val _sql: String = "SELECT COUNT(*) FROM recent_searches"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<Int> {
      public override fun call(): Int {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _result: Int
          if (_cursor.moveToFirst()) {
            val _tmp: Int
            _tmp = _cursor.getInt(0)
            _result = _tmp
          } else {
            _result = 0
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
