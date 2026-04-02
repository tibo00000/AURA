package com.aura.music.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalAudioFile(
    val mediaStoreId: Long,
    val contentUri: String,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val durationMs: Long?,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val dateModifiedEpochMs: Long?,
)

class MediaStoreAudioDataSource(
    private val context: Context,
) {
    fun hasReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getLocalAudioFiles(limit: Int = 40): List<LocalAudioFile> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) {
            return@withContext emptyList()
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )

        buildList {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext() && size < limit) {
                    val mediaStoreId = cursor.getLong(idColumn)
                    val contentUri: Uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mediaStoreId.toString(),
                    )
                    add(
                        LocalAudioFile(
                            mediaStoreId = mediaStoreId,
                            contentUri = contentUri.toString(),
                            title = cursor.getString(titleColumn).orEmpty().ifBlank { "Unknown title" },
                            artistName = cursor.getString(artistColumn).orEmpty().ifBlank { "Unknown artist" },
                            albumTitle = cursor.getString(albumColumn)?.ifBlank { null },
                            durationMs = cursor.getLong(durationColumn).takeIf { it > 0L },
                            mimeType = cursor.getString(mimeTypeColumn),
                            fileSizeBytes = cursor.getLong(sizeColumn).takeIf { it > 0L },
                            dateModifiedEpochMs = cursor.getLong(dateModifiedColumn)
                                .takeIf { it > 0L }
                                ?.times(1000L),
                        ),
                    )
                }
            }
        }
    }
}
