package com.aura.music.`data`.local

import androidx.room3.EntityDeleteOrUpdateAdapter
import androidx.room3.EntityInsertAdapter
import androidx.room3.EntityUpsertAdapter
import androidx.room3.RoomDatabase
import androidx.room3.coroutines.createFlow
import androidx.room3.util.getColumnIndexOrThrow
import androidx.room3.util.getTotalChangedRows
import androidx.room3.util.performSuspending
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.prepare
import androidx.sqlite.step
import javax.`annotation`.processing.Generated
import kotlin.Float
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room3.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
internal class DownloadJobDao_Impl(
  __db: RoomDatabase,
) : DownloadJobDao {
  private val __db: RoomDatabase

  private val __upsertAdapterOfDownloadJobEntity: EntityUpsertAdapter<DownloadJobEntity>
  init {
    this.__db = __db
    this.__upsertAdapterOfDownloadJobEntity = EntityUpsertAdapter<DownloadJobEntity>(object : EntityInsertAdapter<DownloadJobEntity>() {
      protected override fun createQuery(): String = "INSERT INTO `download_jobs` (`id`,`track_id`,`provider_name`,`status`,`progress_percent`,`error_code`,`error_message`,`attempt_count`,`created_at`,`updated_at`,`archived_in_cloud_at`,`purge_after_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: DownloadJobEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.trackId)
        statement.bindText(3, entity.providerName)
        statement.bindText(4, entity.status)
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
          statement.bindText(6, _tmpErrorCode)
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpErrorMessage)
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
    }, object : EntityDeleteOrUpdateAdapter<DownloadJobEntity>() {
      protected override fun createQuery(): String = "UPDATE `download_jobs` SET `id` = ?,`track_id` = ?,`provider_name` = ?,`status` = ?,`progress_percent` = ?,`error_code` = ?,`error_message` = ?,`attempt_count` = ?,`created_at` = ?,`updated_at` = ?,`archived_in_cloud_at` = ?,`purge_after_at` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: DownloadJobEntity) {
        statement.bindText(1, entity.id)
        statement.bindText(2, entity.trackId)
        statement.bindText(3, entity.providerName)
        statement.bindText(4, entity.status)
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
          statement.bindText(6, _tmpErrorCode)
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(7)
        } else {
          statement.bindText(7, _tmpErrorMessage)
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
        statement.bindText(13, entity.id)
      }
    })
  }

  public override suspend fun upsert(job: DownloadJobEntity): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfDownloadJobEntity.upsert(_connection, job)
  }

  public override suspend fun upsert(jobs: List<DownloadJobEntity>): Unit = performSuspending(__db, false, true) { _connection ->
    __upsertAdapterOfDownloadJobEntity.upsert(_connection, jobs)
  }

  public override suspend fun getJobById(jobId: String): DownloadJobEntity? {
    val _sql: String = "SELECT * FROM download_jobs WHERE id = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, jobId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTrackId: Int = getColumnIndexOrThrow(_stmt, "track_id")
        val _columnIndexOfProviderName: Int = getColumnIndexOrThrow(_stmt, "provider_name")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfProgressPercent: Int = getColumnIndexOrThrow(_stmt, "progress_percent")
        val _columnIndexOfErrorCode: Int = getColumnIndexOrThrow(_stmt, "error_code")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "error_message")
        val _columnIndexOfAttemptCount: Int = getColumnIndexOrThrow(_stmt, "attempt_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_stmt, "archived_in_cloud_at")
        val _columnIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_stmt, "purge_after_at")
        val _result: DownloadJobEntity?
        if (_stmt.step()) {
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTrackId: String
          _tmpTrackId = _stmt.getText(_columnIndexOfTrackId)
          val _tmpProviderName: String
          _tmpProviderName = _stmt.getText(_columnIndexOfProviderName)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpProgressPercent: Float?
          if (_stmt.isNull(_columnIndexOfProgressPercent)) {
            _tmpProgressPercent = null
          } else {
            _tmpProgressPercent = _stmt.getDouble(_columnIndexOfProgressPercent).toFloat()
          }
          val _tmpErrorCode: String?
          if (_stmt.isNull(_columnIndexOfErrorCode)) {
            _tmpErrorCode = null
          } else {
            _tmpErrorCode = _stmt.getText(_columnIndexOfErrorCode)
          }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpAttemptCount: Int
          _tmpAttemptCount = _stmt.getLong(_columnIndexOfAttemptCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpArchivedInCloudAt: Long?
          if (_stmt.isNull(_columnIndexOfArchivedInCloudAt)) {
            _tmpArchivedInCloudAt = null
          } else {
            _tmpArchivedInCloudAt = _stmt.getLong(_columnIndexOfArchivedInCloudAt)
          }
          val _tmpPurgeAfterAt: Long?
          if (_stmt.isNull(_columnIndexOfPurgeAfterAt)) {
            _tmpPurgeAfterAt = null
          } else {
            _tmpPurgeAfterAt = _stmt.getLong(_columnIndexOfPurgeAfterAt)
          }
          _result = DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getAllJobsFlow(): Flow<List<DownloadJobEntity>> {
    val _sql: String = "SELECT * FROM download_jobs ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("download_jobs")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTrackId: Int = getColumnIndexOrThrow(_stmt, "track_id")
        val _columnIndexOfProviderName: Int = getColumnIndexOrThrow(_stmt, "provider_name")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfProgressPercent: Int = getColumnIndexOrThrow(_stmt, "progress_percent")
        val _columnIndexOfErrorCode: Int = getColumnIndexOrThrow(_stmt, "error_code")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "error_message")
        val _columnIndexOfAttemptCount: Int = getColumnIndexOrThrow(_stmt, "attempt_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_stmt, "archived_in_cloud_at")
        val _columnIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_stmt, "purge_after_at")
        val _result: MutableList<DownloadJobEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DownloadJobEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTrackId: String
          _tmpTrackId = _stmt.getText(_columnIndexOfTrackId)
          val _tmpProviderName: String
          _tmpProviderName = _stmt.getText(_columnIndexOfProviderName)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpProgressPercent: Float?
          if (_stmt.isNull(_columnIndexOfProgressPercent)) {
            _tmpProgressPercent = null
          } else {
            _tmpProgressPercent = _stmt.getDouble(_columnIndexOfProgressPercent).toFloat()
          }
          val _tmpErrorCode: String?
          if (_stmt.isNull(_columnIndexOfErrorCode)) {
            _tmpErrorCode = null
          } else {
            _tmpErrorCode = _stmt.getText(_columnIndexOfErrorCode)
          }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpAttemptCount: Int
          _tmpAttemptCount = _stmt.getLong(_columnIndexOfAttemptCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpArchivedInCloudAt: Long?
          if (_stmt.isNull(_columnIndexOfArchivedInCloudAt)) {
            _tmpArchivedInCloudAt = null
          } else {
            _tmpArchivedInCloudAt = _stmt.getLong(_columnIndexOfArchivedInCloudAt)
          }
          val _tmpPurgeAfterAt: Long?
          if (_stmt.isNull(_columnIndexOfPurgeAfterAt)) {
            _tmpPurgeAfterAt = null
          } else {
            _tmpPurgeAfterAt = _stmt.getLong(_columnIndexOfPurgeAfterAt)
          }
          _item = DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getJobsByStatusFlow(status: String): Flow<List<DownloadJobEntity>> {
    val _sql: String = "SELECT * FROM download_jobs WHERE status = ? ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("download_jobs")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, status)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTrackId: Int = getColumnIndexOrThrow(_stmt, "track_id")
        val _columnIndexOfProviderName: Int = getColumnIndexOrThrow(_stmt, "provider_name")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfProgressPercent: Int = getColumnIndexOrThrow(_stmt, "progress_percent")
        val _columnIndexOfErrorCode: Int = getColumnIndexOrThrow(_stmt, "error_code")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "error_message")
        val _columnIndexOfAttemptCount: Int = getColumnIndexOrThrow(_stmt, "attempt_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_stmt, "archived_in_cloud_at")
        val _columnIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_stmt, "purge_after_at")
        val _result: MutableList<DownloadJobEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DownloadJobEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTrackId: String
          _tmpTrackId = _stmt.getText(_columnIndexOfTrackId)
          val _tmpProviderName: String
          _tmpProviderName = _stmt.getText(_columnIndexOfProviderName)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpProgressPercent: Float?
          if (_stmt.isNull(_columnIndexOfProgressPercent)) {
            _tmpProgressPercent = null
          } else {
            _tmpProgressPercent = _stmt.getDouble(_columnIndexOfProgressPercent).toFloat()
          }
          val _tmpErrorCode: String?
          if (_stmt.isNull(_columnIndexOfErrorCode)) {
            _tmpErrorCode = null
          } else {
            _tmpErrorCode = _stmt.getText(_columnIndexOfErrorCode)
          }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpAttemptCount: Int
          _tmpAttemptCount = _stmt.getLong(_columnIndexOfAttemptCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpArchivedInCloudAt: Long?
          if (_stmt.isNull(_columnIndexOfArchivedInCloudAt)) {
            _tmpArchivedInCloudAt = null
          } else {
            _tmpArchivedInCloudAt = _stmt.getLong(_columnIndexOfArchivedInCloudAt)
          }
          val _tmpPurgeAfterAt: Long?
          if (_stmt.isNull(_columnIndexOfPurgeAfterAt)) {
            _tmpPurgeAfterAt = null
          } else {
            _tmpPurgeAfterAt = _stmt.getLong(_columnIndexOfPurgeAfterAt)
          }
          _item = DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getActiveJobs(): List<DownloadJobEntity> {
    val _sql: String = "SELECT * FROM download_jobs WHERE status IN ('queued', 'running')"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTrackId: Int = getColumnIndexOrThrow(_stmt, "track_id")
        val _columnIndexOfProviderName: Int = getColumnIndexOrThrow(_stmt, "provider_name")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfProgressPercent: Int = getColumnIndexOrThrow(_stmt, "progress_percent")
        val _columnIndexOfErrorCode: Int = getColumnIndexOrThrow(_stmt, "error_code")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "error_message")
        val _columnIndexOfAttemptCount: Int = getColumnIndexOrThrow(_stmt, "attempt_count")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfArchivedInCloudAt: Int = getColumnIndexOrThrow(_stmt, "archived_in_cloud_at")
        val _columnIndexOfPurgeAfterAt: Int = getColumnIndexOrThrow(_stmt, "purge_after_at")
        val _result: MutableList<DownloadJobEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: DownloadJobEntity
          val _tmpId: String
          _tmpId = _stmt.getText(_columnIndexOfId)
          val _tmpTrackId: String
          _tmpTrackId = _stmt.getText(_columnIndexOfTrackId)
          val _tmpProviderName: String
          _tmpProviderName = _stmt.getText(_columnIndexOfProviderName)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpProgressPercent: Float?
          if (_stmt.isNull(_columnIndexOfProgressPercent)) {
            _tmpProgressPercent = null
          } else {
            _tmpProgressPercent = _stmt.getDouble(_columnIndexOfProgressPercent).toFloat()
          }
          val _tmpErrorCode: String?
          if (_stmt.isNull(_columnIndexOfErrorCode)) {
            _tmpErrorCode = null
          } else {
            _tmpErrorCode = _stmt.getText(_columnIndexOfErrorCode)
          }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpAttemptCount: Int
          _tmpAttemptCount = _stmt.getLong(_columnIndexOfAttemptCount).toInt()
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpArchivedInCloudAt: Long?
          if (_stmt.isNull(_columnIndexOfArchivedInCloudAt)) {
            _tmpArchivedInCloudAt = null
          } else {
            _tmpArchivedInCloudAt = _stmt.getLong(_columnIndexOfArchivedInCloudAt)
          }
          val _tmpPurgeAfterAt: Long?
          if (_stmt.isNull(_columnIndexOfPurgeAfterAt)) {
            _tmpPurgeAfterAt = null
          } else {
            _tmpPurgeAfterAt = _stmt.getLong(_columnIndexOfPurgeAfterAt)
          }
          _item = DownloadJobEntity(_tmpId,_tmpTrackId,_tmpProviderName,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpAttemptCount,_tmpCreatedAt,_tmpUpdatedAt,_tmpArchivedInCloudAt,_tmpPurgeAfterAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
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
    return createFlow(__db, false, arrayOf("download_jobs", "tracks")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfJobId: Int = 0
        val _columnIndexOfTrackId: Int = 1
        val _columnIndexOfTitle: Int = 2
        val _columnIndexOfArtistName: Int = 3
        val _columnIndexOfCoverUri: Int = 4
        val _columnIndexOfStatus: Int = 5
        val _columnIndexOfProgressPercent: Int = 6
        val _columnIndexOfErrorCode: Int = 7
        val _columnIndexOfErrorMessage: Int = 8
        val _columnIndexOfCreatedAt: Int = 9
        val _result: MutableList<DownloadJobRowModel> = mutableListOf()
        while (_stmt.step()) {
          val _item: DownloadJobRowModel
          val _tmpJobId: String
          _tmpJobId = _stmt.getText(_columnIndexOfJobId)
          val _tmpTrackId: String
          _tmpTrackId = _stmt.getText(_columnIndexOfTrackId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpArtistName: String
          _tmpArtistName = _stmt.getText(_columnIndexOfArtistName)
          val _tmpCoverUri: String?
          if (_stmt.isNull(_columnIndexOfCoverUri)) {
            _tmpCoverUri = null
          } else {
            _tmpCoverUri = _stmt.getText(_columnIndexOfCoverUri)
          }
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpProgressPercent: Float?
          if (_stmt.isNull(_columnIndexOfProgressPercent)) {
            _tmpProgressPercent = null
          } else {
            _tmpProgressPercent = _stmt.getDouble(_columnIndexOfProgressPercent).toFloat()
          }
          val _tmpErrorCode: String?
          if (_stmt.isNull(_columnIndexOfErrorCode)) {
            _tmpErrorCode = null
          } else {
            _tmpErrorCode = _stmt.getText(_columnIndexOfErrorCode)
          }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          _item = DownloadJobRowModel(_tmpJobId,_tmpTrackId,_tmpTitle,_tmpArtistName,_tmpCoverUri,_tmpStatus,_tmpProgressPercent,_tmpErrorCode,_tmpErrorMessage,_tmpCreatedAt)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteJob(jobId: String): Int {
    val _sql: String = "DELETE FROM download_jobs WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, jobId)
        _stmt.step()
        getTotalChangedRows(_connection)
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clearCompletedJobs(): Int {
    val _sql: String = "DELETE FROM download_jobs WHERE status IN ('succeeded', 'failed', 'cancelled')"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
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
