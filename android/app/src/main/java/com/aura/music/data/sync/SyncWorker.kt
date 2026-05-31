package com.aura.music.data.sync

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.music.AuraApplication

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i("SyncWorker", "Sync background job started by WorkManager")
        val app = applicationContext as AuraApplication
        val syncRepository = app.container.syncRepository

        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "android_pixel_device"

        return try {
            val success = syncRepository.performSync(deviceId)
            if (success) {
                Log.i("SyncWorker", "Sync background job completed successfully")
                Result.success()
            } else {
                Log.w("SyncWorker", "Sync background job skipped or failed")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync background job crashed: ${e.message}", e)
            Result.failure()
        }
    }
}
