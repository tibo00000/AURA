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
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.query
import androidx.sqlite.db.SupportSQLiteStatement
import java.lang.Class
import java.util.ArrayList
import java.util.concurrent.Callable
import javax.`annotation`.processing.Generated
import kotlin.Float
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION"])
public class DownloadJobDao_Impl(
  __db: RoomDatabase,
) : DownloadJobDao {
  private val __db: RoomDatabase

  private val __preparedStmtOfDeleteJob: SharedSQLiteStatement

  private val __preparedStmtOfClearCompletedJobs: SharedSQLiteStatement

  private val __upsertionAdapterOfDownloadJobEntity: EntityUpsertionAdapter<DownloadJobEntity>
  init {
    this.__db = __db
    this.__preparedStmtOfDeleteJob = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String = "DELETE FROM download_jobs WHERE id = ?"
        return _query
      }
    }
    this.__preparedStmtOfClearCompletedJobs = object : SharedSQLiteStatement(__db) {
      public override fun createQuery(): String {
        val _query: String =
            "DELETE FROM download_jobs WHERE status IN ('succeeded', 'failed', 'cancelled')"
        return _query
      }
    }
    this.__upsertionAdapterOfDownloadJobEntity = EntityUpsertionAdapter<DownloadJobEntity>(object :
        EntityInsertionAdapter<DownloadJobEntity>(__db) {
      protected override fun createQuery(): String =
          "INSERT INTO `download_jobs` (`id`,`track_id`,`provider_name`,`status`,`progress_percent`,`error_code`,`error_message`,`attempt_count`,`created_at`,`updated_at`,`archived_in_cloud_at`,`purge_after_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SupportSQLiteStatement, entity: DownloadJobEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.trackId)
        statement.bindString(3, entity.providerName)
        statement.bindString(4, entity.status)
        val _tmpProgressPercent: Float? = entity.progressPercent
        if (_tmpProgressPercent == null) {
          statement.bindNull(5)
        } else {
          statement.bindDouble(5, _tmpProgressPercent.toDouble())
        }
        val _tmpErrorCode: String? = entity.errorCode
        if (_tmpErrorCode == null) {
          statement.bindNull(6)
        } else {
          statement.bindString(6, _tmpErrorCode)
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpErrorMessage)
        }
        statement.bindLong(8, entity.attemptCount.toLong())
        statement.bindLong(9, entity.createdAt)
        statement.bindLong(10, entity.updatedAt)
        val _tmpArchivedInCloudAt: Long? = entity.archivedInCloudAt
        if (_tmpArchivedInCloudAt == null) {
          statement.bindNull(11)
        } else {
          statement.bindLong(11, _tmpArchivedInCloudAt)
        }
        val _tmpPurgeAfterAt: Long? = entity.purgeAfterAt
        if (_tmpPurgeAfterAt == null) {
          statement.bindNull(12)
        } else {
          statement.bindLong(12, _tmpPurgeAfterAt)
        }
      }
    }, object : EntityDeletionOrUpdateAdapter<DownloadJobEntity>(__db) {
      protected override fun createQuery(): String =
          "UPDATE `download_jobs` SET `id` = ?,`track_id` = ?,`provider_name` = ?,`status` = ?,`progress_percent` = ?,`error_code` = ?,`error_message` = ?,`attempt_count` = ?,`created_at` = ?,`updated_at` = ?,`archived_in_cloud_at` = ?,`purge_after_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SupportSQLiteStatement, entity: DownloadJobEntity) {
        statement.bindString(1, entity.id)
        statement.bindString(2, entity.trackId)
        statement.bindString(3, entity.providerName)
        statement.bindString(4, entity.status)
        val _tmpProgressPercent: Float? = entity.progressPercent
        if (_tmpProgressPercent == null) {
          statement.bindNull(5)
        } else {
          statement.bindDouble(5, _tmpProgressPercent.toDouble())
        }
        val _tmpErrorCode: String? = entity.errorCode
        if (_tmpErrorCode == null) {
          statement.bindNull(6)
        } else {
          statement.bindString(6, _tmpErrorCode)
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(7)
        } else {
          statement.bindString(7, _tmpErrorMessage)
        }
        statement.bindLong(8, entity.attemptCount.toLong())
        statement.bindLong(9, entity.createdAt)
        statement.bindLong(10, entity.updatedAt)
        val _tmpArchivedInCloudAt: Long? = entity.archivedInCloudAt
        if (_tmpArchivedInCloudAt == null) {
          statement.bindNull(11)
        } else {
          statement.bindLong(11, _tmpArchivedInCloudAt)
        }
        val _tmpPurgeAfterAt: Long? = entity.purgeAfterAt
        if (_tmpPurgeAfterAt == null) {
          statement.bindNull(12)
        } else {
          statement.bindLong(12, _tmpPurgeAfterAt)
        }
        statement.bindString(13, entity.id)
      }
    })
  }

  public override suspend fun deleteJob(jobId: String): Int = CoroutinesRoom.execute(__db, true,
      object : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfDeleteJob.acquire()
      var _argIndex: Int = 1
      _stmt.bindString(_argIndex, jobId)
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
        __preparedStmtOfDeleteJob.release(_stmt)
      }
    }
  })

  public override suspend fun clearCompletedJobs(): Int = CoroutinesRoom.execute(__db, true, object
      : Callable<Int> {
    public override fun call(): Int {
      val _stmt: SupportSQLiteStatement = __preparedStmtOfClearCompletedJobs.acquire()
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
        __preparedStmtOfClearCompletedJobs.release(_stmt)
      }
    }
  })

  public override suspend fun upsert(job: DownloadJobEntity): Unit = CoroutinesRoom.execute(__db,
      true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfDownloadJobEntity.upsert(job)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun upsert(jobs: List<DownloadJobEntity>): Unit =
      CoroutinesRoom.execute(__db, true, object : Callable<Unit> {
    public override fun call() {
      __db.beginTransaction()
      try {
        __upsertionAdapterOfDownloadJobEntity.upsert(jobs)
        __db.setTransactionSuccessful()
      } finally {
        __db.endTransaction()
      }
    }
  })

  public override suspend fun getJobById(jobId: String): DownloadJobEntity? {
    val _sql: String = "SELECT * FROM download_jobs WHERE id = ? LIMIT 1"
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, jobId)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<DownloadJobEntity?> {
      public override fun call(): DownloadJobEntity? {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfTrackId: Int = getColumnIndexOrThrow(_cursor, "track_id")
          val _cursorIndexOfProviderName: Int = getColumnIndexOrThrow(_cursor, "provider_name")
          val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_cursor, "status")
          val _cursorIndexOfProgressPercent: Int = getColumnIndexOrThrow(_cursor,
              "progress_percent")
          val _cursorIndexOfErrorCode: Int = getColumnIndexOrThrow(_cursor, "error_code")
          val _cursorIndexOfErrorMessage: Int = getColumnIndexOrThrow(_cursor, "error_message")
          val _cursorIndexOfAttemptCount: Int = getColumnIndexOrThrow(_cursor, "attempt_count")
          val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_cursor, "created_at")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _cursorIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_cursor,
              "archived_in_cloud_at")
          val _cursorIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_cursor, "purge_after_at")
          val _result: DownloadJobEntity?
          if (_cursor.moveToFirst()) {
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTrackId: String
            _tmpTrackId = _cursor.getString(_cursorIndexOfTrackId)
            val _tmpProviderName: String
            _tmpProviderName = _cursor.getString(_cursorIndexOfProviderName)
            val _tmpStatus: String
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus)
            val _tmpProgressPercent: Float?
            if (_cursor.isNull(_cursorIndexOfProgressPercent)) {
              _tmpProgressPercent = null
            } else {
              _tmpProgressPercent = _cursor.getFloat(_cursorIndexOfProgressPercent)
            }
            val _tmpErrorCode: String?
            if (_cursor.isNull(_cursorIndexOfErrorCode)) {
              _tmpErrorCode = null
            } else {
              _tmpErrorCode = _cursor.getString(_cursorIndexOfErrorCode)
            }
            val _tmpErrorMessage: String?
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage)
            }
            val _tmpAttemptCount: Int
            _tmpAttemptCount = _cursor.getInt(_cursorIndexOfAttemptCount)
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            val _tmpArchivedInCloudAt: Long?
            if (_cursor.isNull(_cursorIndexOfArchivedInCloudAt)) {
              _tmpArchivedInCloudAt = null
            } else {
              _tmpArchivedInCloudAt = _cursor.getLong(_cursorIndexOfArchivedInCloudAt)
            }
            val _tmpPurgeAfterAt: Long?
            if (_cursor.isNull(_cursorIndexOfPurgeAfterAt)) {
              _tmpPurgeAfterAt = null
            } else {
              _tmpPurgeAfterAt = _cursor.getLong(_cursorIndexOfPurgeAfterAt)
            }
            _result =
                DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
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

  public override fun getAllJobsFlow(): Flow<List<DownloadJobEntity>> {
    val _sql: String = "SELECT * FROM download_jobs ORDER BY created_at DESC"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    return CoroutinesRoom.createFlow(__db, false, arrayOf("download_jobs"), object :
        Callable<List<DownloadJobEntity>> {
      public override fun call(): List<DownloadJobEntity> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfTrackId: Int = getColumnIndexOrThrow(_cursor, "track_id")
          val _cursorIndexOfProviderName: Int = getColumnIndexOrThrow(_cursor, "provider_name")
          val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_cursor, "status")
          val _cursorIndexOfProgressPercent: Int = getColumnIndexOrThrow(_cursor,
              "progress_percent")
          val _cursorIndexOfErrorCode: Int = getColumnIndexOrThrow(_cursor, "error_code")
          val _cursorIndexOfErrorMessage: Int = getColumnIndexOrThrow(_cursor, "error_message")
          val _cursorIndexOfAttemptCount: Int = getColumnIndexOrThrow(_cursor, "attempt_count")
          val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_cursor, "created_at")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _cursorIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_cursor,
              "archived_in_cloud_at")
          val _cursorIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_cursor, "purge_after_at")
          val _result: MutableList<DownloadJobEntity> =
              ArrayList<DownloadJobEntity>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: DownloadJobEntity
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTrackId: String
            _tmpTrackId = _cursor.getString(_cursorIndexOfTrackId)
            val _tmpProviderName: String
            _tmpProviderName = _cursor.getString(_cursorIndexOfProviderName)
            val _tmpStatus: String
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus)
            val _tmpProgressPercent: Float?
            if (_cursor.isNull(_cursorIndexOfProgressPercent)) {
              _tmpProgressPercent = null
            } else {
              _tmpProgressPercent = _cursor.getFloat(_cursorIndexOfProgressPercent)
            }
            val _tmpErrorCode: String?
            if (_cursor.isNull(_cursorIndexOfErrorCode)) {
              _tmpErrorCode = null
            } else {
              _tmpErrorCode = _cursor.getString(_cursorIndexOfErrorCode)
            }
            val _tmpErrorMessage: String?
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage)
            }
            val _tmpAttemptCount: Int
            _tmpAttemptCount = _cursor.getInt(_cursorIndexOfAttemptCount)
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            val _tmpArchivedInCloudAt: Long?
            if (_cursor.isNull(_cursorIndexOfArchivedInCloudAt)) {
              _tmpArchivedInCloudAt = null
            } else {
              _tmpArchivedInCloudAt = _cursor.getLong(_cursorIndexOfArchivedInCloudAt)
            }
            val _tmpPurgeAfterAt: Long?
            if (_cursor.isNull(_cursorIndexOfPurgeAfterAt)) {
              _tmpPurgeAfterAt = null
            } else {
              _tmpPurgeAfterAt = _cursor.getLong(_cursorIndexOfPurgeAfterAt)
            }
            _item =
                DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
        }
      }

      protected fun finalize() {
        _statement.release()
      }
    })
  }

  public override fun getJobsByStatusFlow(status: String): Flow<List<DownloadJobEntity>> {
    val _sql: String = "SELECT * FROM download_jobs WHERE status = ? ORDER BY created_at DESC"
    val _statement: RoomSQLiteQuery = acquire(_sql, 1)
    var _argIndex: Int = 1
    _statement.bindString(_argIndex, status)
    return CoroutinesRoom.createFlow(__db, false, arrayOf("download_jobs"), object :
        Callable<List<DownloadJobEntity>> {
      public override fun call(): List<DownloadJobEntity> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfTrackId: Int = getColumnIndexOrThrow(_cursor, "track_id")
          val _cursorIndexOfProviderName: Int = getColumnIndexOrThrow(_cursor, "provider_name")
          val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_cursor, "status")
          val _cursorIndexOfProgressPercent: Int = getColumnIndexOrThrow(_cursor,
              "progress_percent")
          val _cursorIndexOfErrorCode: Int = getColumnIndexOrThrow(_cursor, "error_code")
          val _cursorIndexOfErrorMessage: Int = getColumnIndexOrThrow(_cursor, "error_message")
          val _cursorIndexOfAttemptCount: Int = getColumnIndexOrThrow(_cursor, "attempt_count")
          val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_cursor, "created_at")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _cursorIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_cursor,
              "archived_in_cloud_at")
          val _cursorIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_cursor, "purge_after_at")
          val _result: MutableList<DownloadJobEntity> =
              ArrayList<DownloadJobEntity>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: DownloadJobEntity
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTrackId: String
            _tmpTrackId = _cursor.getString(_cursorIndexOfTrackId)
            val _tmpProviderName: String
            _tmpProviderName = _cursor.getString(_cursorIndexOfProviderName)
            val _tmpStatus: String
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus)
            val _tmpProgressPercent: Float?
            if (_cursor.isNull(_cursorIndexOfProgressPercent)) {
              _tmpProgressPercent = null
            } else {
              _tmpProgressPercent = _cursor.getFloat(_cursorIndexOfProgressPercent)
            }
            val _tmpErrorCode: String?
            if (_cursor.isNull(_cursorIndexOfErrorCode)) {
              _tmpErrorCode = null
            } else {
              _tmpErrorCode = _cursor.getString(_cursorIndexOfErrorCode)
            }
            val _tmpErrorMessage: String?
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage)
            }
            val _tmpAttemptCount: Int
            _tmpAttemptCount = _cursor.getInt(_cursorIndexOfAttemptCount)
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            val _tmpArchivedInCloudAt: Long?
            if (_cursor.isNull(_cursorIndexOfArchivedInCloudAt)) {
              _tmpArchivedInCloudAt = null
            } else {
              _tmpArchivedInCloudAt = _cursor.getLong(_cursorIndexOfArchivedInCloudAt)
            }
            val _tmpPurgeAfterAt: Long?
            if (_cursor.isNull(_cursorIndexOfPurgeAfterAt)) {
              _tmpPurgeAfterAt = null
            } else {
              _tmpPurgeAfterAt = _cursor.getLong(_cursorIndexOfPurgeAfterAt)
            }
            _item =
                DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
        }
      }

      protected fun finalize() {
        _statement.release()
      }
    })
  }

  public override suspend fun getActiveJobs(): List<DownloadJobEntity> {
    val _sql: String = "SELECT * FROM download_jobs WHERE status IN ('queued', 'running')"
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    val _cancellationSignal: CancellationSignal? = createCancellationSignal()
    return execute(__db, false, _cancellationSignal, object : Callable<List<DownloadJobEntity>> {
      public override fun call(): List<DownloadJobEntity> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfId: Int = getColumnIndexOrThrow(_cursor, "id")
          val _cursorIndexOfTrackId: Int = getColumnIndexOrThrow(_cursor, "track_id")
          val _cursorIndexOfProviderName: Int = getColumnIndexOrThrow(_cursor, "provider_name")
          val _cursorIndexOfStatus: Int = getColumnIndexOrThrow(_cursor, "status")
          val _cursorIndexOfProgressPercent: Int = getColumnIndexOrThrow(_cursor,
              "progress_percent")
          val _cursorIndexOfErrorCode: Int = getColumnIndexOrThrow(_cursor, "error_code")
          val _cursorIndexOfErrorMessage: Int = getColumnIndexOrThrow(_cursor, "error_message")
          val _cursorIndexOfAttemptCount: Int = getColumnIndexOrThrow(_cursor, "attempt_count")
          val _cursorIndexOfCreatedAt: Int = getColumnIndexOrThrow(_cursor, "created_at")
          val _cursorIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_cursor, "updated_at")
          val _cursorIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_cursor,
              "archived_in_cloud_at")
          val _cursorIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_cursor, "purge_after_at")
          val _result: MutableList<DownloadJobEntity> =
              ArrayList<DownloadJobEntity>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: DownloadJobEntity
            val _tmpId: String
            _tmpId = _cursor.getString(_cursorIndexOfId)
            val _tmpTrackId: String
            _tmpTrackId = _cursor.getString(_cursorIndexOfTrackId)
            val _tmpProviderName: String
            _tmpProviderName = _cursor.getString(_cursorIndexOfProviderName)
            val _tmpStatus: String
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus)
            val _tmpProgressPercent: Float?
            if (_cursor.isNull(_cursorIndexOfProgressPercent)) {
              _tmpProgressPercent = null
            } else {
              _tmpProgressPercent = _cursor.getFloat(_cursorIndexOfProgressPercent)
            }
            val _tmpErrorCode: String?
            if (_cursor.isNull(_cursorIndexOfErrorCode)) {
              _tmpErrorCode = null
            } else {
              _tmpErrorCode = _cursor.getString(_cursorIndexOfErrorCode)
            }
            val _tmpErrorMessage: String?
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage)
            }
            val _tmpAttemptCount: Int
            _tmpAttemptCount = _cursor.getInt(_cursorIndexOfAttemptCount)
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            val _tmpUpdatedAt: Long
            _tmpUpdatedAt = _cursor.getLong(_cursorIndexOfUpdatedAt)
            val _tmpArchivedInCloudAt: Long?
            if (_cursor.isNull(_cursorIndexOfArchivedInCloudAt)) {
              _tmpArchivedInCloudAt = null
            } else {
              _tmpArchivedInCloudAt = _cursor.getLong(_cursorIndexOfArchivedInCloudAt)
            }
            val _tmpPurgeAfterAt: Long?
            if (_cursor.isNull(_cursorIndexOfPurgeAfterAt)) {
              _tmpPurgeAfterAt = null
            } else {
              _tmpPurgeAfterAt = _cursor.getLong(_cursorIndexOfPurgeAfterAt)
            }
            _item =
                DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
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

  public override fun getAllJobsWithTrackFlow(): Flow<List<DownloadJobRowModel>> {
    val _sql: String = """
        |
        |        SELECT 
        |            download_jobs.id AS jobId,
        |            download_jobs.track_id AS trackId,
        |            COALESCE(tracks.title, 'Piste inconnue') AS title,
        |            COALESCE(tracks.display_artist_name, 'Artiste inconnu') AS artistName,
        |            tracks.cover_uri AS coverUri,
        |            download_jobs.status AS status,
        |            download_jobs.progress_percent AS progressPercent,
        |            download_jobs.error_code AS errorCode,
        |            download_jobs.error_message AS errorMessage,
        |            download_jobs.created_at AS createdAt
        |        FROM download_jobs
        |        LEFT JOIN tracks ON tracks.id = download_jobs.track_id
        |        ORDER BY download_jobs.created_at DESC
        |    
        """.trimMargin()
    val _statement: RoomSQLiteQuery = acquire(_sql, 0)
    return CoroutinesRoom.createFlow(__db, false, arrayOf("download_jobs", "tracks"), object :
        Callable<List<DownloadJobRowModel>> {
      public override fun call(): List<DownloadJobRowModel> {
        val _cursor: Cursor = query(__db, _statement, false, null)
        try {
          val _cursorIndexOfJobId: Int = 0
          val _cursorIndexOfTrackId: Int = 1
          val _cursorIndexOfTitle: Int = 2
          val _cursorIndexOfArtistName: Int = 3
          val _cursorIndexOfCoverUri: Int = 4
          val _cursorIndexOfStatus: Int = 5
          val _cursorIndexOfProgressPercent: Int = 6
          val _cursorIndexOfErrorCode: Int = 7
          val _cursorIndexOfErrorMessage: Int = 8
          val _cursorIndexOfCreatedAt: Int = 9
          val _result: MutableList<DownloadJobRowModel> =
              ArrayList<DownloadJobRowModel>(_cursor.getCount())
          while (_cursor.moveToNext()) {
            val _item: DownloadJobRowModel
            val _tmpJobId: String
            _tmpJobId = _cursor.getString(_cursorIndexOfJobId)
            val _tmpTrackId: String
            _tmpTrackId = _cursor.getString(_cursorIndexOfTrackId)
            val _tmpTitle: String
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle)
            val _tmpArtistName: String
            _tmpArtistName = _cursor.getString(_cursorIndexOfArtistName)
            val _tmpCoverUri: String?
            if (_cursor.isNull(_cursorIndexOfCoverUri)) {
              _tmpCoverUri = null
            } else {
              _tmpCoverUri = _cursor.getString(_cursorIndexOfCoverUri)
            }
            val _tmpStatus: String
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus)
            val _tmpProgressPercent: Float?
            if (_cursor.isNull(_cursorIndexOfProgressPercent)) {
              _tmpProgressPercent = null
            } else {
              _tmpProgressPercent = _cursor.getFloat(_cursorIndexOfProgressPercent)
            }
            val _tmpErrorCode: String?
            if (_cursor.isNull(_cursorIndexOfErrorCode)) {
              _tmpErrorCode = null
            } else {
              _tmpErrorCode = _cursor.getString(_cursorIndexOfErrorCode)
            }
            val _tmpErrorMessage: String?
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage)
            }
            val _tmpCreatedAt: Long
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt)
            _item =
                DownloadJobRowModel(_tmpJobId,_tmpTrackId,_tmpTitle,_tmpArtistName,_tmpCoverUri,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpCreatedAt)
            _result.add(_item)
          }
          return _result
        } finally {
          _cursor.close()
        }
      }

      protected fun finalize() {
        _statement.release()
      }
    })
  }

  public companion object {
    @JvmStatic
    public fun getRequiredConverters(): List<Class<*>> = emptyList()
  }
}
