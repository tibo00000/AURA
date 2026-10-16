package com.aura.music.data.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalAudioFile(
    val mediaStoreId: Long,
    val contentUri: String,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val albumId: Long?,
    val coverUri: String?,
    val durationMs: Long?,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val dateModifiedEpochMs: Long?,
)

data class MediaScanResult(
    val tracks: List<LocalAudioFile>,
    val isComplete: Boolean
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

    companion object {
        private const val MIN_DURATION_MS = 30_000L
    }

    /**
     * Charge une miniature de pochette haute performance directement depuis l'URI audio (Android 10+ / API 29+).
     */
    fun loadTrackThumbnail(audioUri: Uri, width: Int = 300, height: Int = 300): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                context.contentResolver.loadThumbnail(audioUri, Size(width, height), null)
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    suspend fun getLocalAudioFilesResult(): MediaScanResult = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) {
            return@withContext MediaScanResult(emptyList(), isComplete = false)
        }

        try {
            val files = getLocalAudioFiles()
            MediaScanResult(files, isComplete = true)
        } catch (e: SecurityException) {
            android.util.Log.e("MediaStoreAudioDataSource", "SecurityException during MediaStore scan", e)
            MediaScanResult(emptyList(), isComplete = false)
        } catch (e: Exception) {
            android.util.Log.e("MediaStoreAudioDataSource", "Unexpected exception during MediaStore scan", e)
            MediaScanResult(emptyList(), isComplete = false)
        }
    }

    suspend fun getLocalAudioFiles(): List<LocalAudioFile> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) {
            return@withContext emptyList()
        }

        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            )
        } else {
            @Suppress("DEPRECATION")
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DATA,
            )
        }

        buildList {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= $MIN_DURATION_MS",
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

                val displayNameColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                } else -1

                val relativePathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1

                @Suppress("DEPRECATION")
                val dataColumn = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                } else -1

                while (cursor.moveToNext()) {
                    val albumTitle = cursor.getString(albumColumn).orEmpty()
                    val mimeType = cursor.getString(mimeTypeColumn).orEmpty()

                    val displayName = if (displayNameColumn != -1) cursor.getString(displayNameColumn).orEmpty() else ""
                    val relativePath = if (relativePathColumn != -1) cursor.getString(relativePathColumn).orEmpty() else ""
                    @Suppress("DEPRECATION")
                    val filePath = if (dataColumn != -1) cursor.getString(dataColumn).orEmpty() else ""

                    // Exclude all .opus files and WhatsApp audios/voice notes/media
                    if (mimeType.contains("opus", ignoreCase = true) ||
                        displayName.endsWith(".opus", ignoreCase = true) ||
                        filePath.endsWith(".opus", ignoreCase = true) ||
                        albumTitle.equals("WhatsApp Audio", ignoreCase = true) ||
                        relativePath.contains("WhatsApp", ignoreCase = true) ||
                        filePath.contains("WhatsApp", ignoreCase = true) || 
                        filePath.contains("com.whatsapp", ignoreCase = true)) {
                        continue
                    }

                    val mediaStoreId = cursor.getLong(idColumn)
                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        mediaStoreId,
                    )
                    val albumId = cursor.getLong(albumIdColumn).takeIf { it > 0L }
                    val coverUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentUri.toString()
                    } else {
                        albumId?.let {
                            ContentUris.withAppendedId(
                                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                                it,
                            ).toString()
                        }
                    }

                    add(
                        LocalAudioFile(
                            mediaStoreId = mediaStoreId,
                            contentUri = contentUri.toString(),
                            title = cursor.getString(titleColumn).orEmpty().ifBlank { "Unknown title" },
                            artistName = cursor.getString(artistColumn).orEmpty().ifBlank { "Unknown artist" },
                            albumTitle = cursor.getString(albumColumn)?.ifBlank { null },
                            albumId = albumId,
                            coverUri = coverUri,
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
