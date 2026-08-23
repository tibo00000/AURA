package com.aura.music.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Utilitaires pour traiter, redimensionner et compresser les pochettes
 * afin de garantir des images légères (500x500 max, ~50-80 Ko) sans surcharger la mémoire.
 */
object ImageCompressionUtils {

    private const val MAX_DIMENSION = 500
    private const val JPEG_QUALITY = 80

    /**
     * Télécharge une image depuis une URL distante (Deezer/Aura), la redimensionne
     * et l'enregistre compressée dans le fichier cible.
     */
    suspend fun downloadAndCompressImage(
        imageUrl: String,
        targetFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(imageUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.doInput = true
            connection.connect()

            val inputStream: InputStream = connection.inputStream
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            connection.disconnect()

            if (originalBitmap != null) {
                val resizedBitmap = resizeBitmap(originalBitmap, MAX_DIMENSION)
                saveBitmapToJpeg(resizedBitmap, targetFile, JPEG_QUALITY)
                if (resizedBitmap != originalBitmap) {
                    resizedBitmap.recycle()
                }
                originalBitmap.recycle()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("ImageCompressionUtils", "Failed to download cover from $imageUrl: ${e.message}")
            false
        }
    }

    /**
     * Lit une image locale depuis une URI (MediaStore / PhotoPicker / Fichier),
     * la redimensionne et la sauvegarde compressée dans le fichier cible.
     */
    suspend fun compressAndSaveUri(
        context: Context,
        sourceUri: Uri,
        targetFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext false
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (originalBitmap != null) {
                val resizedBitmap = resizeBitmap(originalBitmap, MAX_DIMENSION)
                saveBitmapToJpeg(resizedBitmap, targetFile, JPEG_QUALITY)
                if (resizedBitmap != originalBitmap) {
                    resizedBitmap.recycle()
                }
                originalBitmap.recycle()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("ImageCompressionUtils", "Failed to compress URI $sourceUri: ${e.message}")
            false
        }
    }

    /**
     * Compresse un tableau d'octets d'image (ex: embeddedPicture) et le sauvegarde.
     */
    suspend fun compressAndSaveBytes(
        bytes: ByteArray,
        targetFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (originalBitmap != null) {
                val resizedBitmap = resizeBitmap(originalBitmap, MAX_DIMENSION)
                saveBitmapToJpeg(resizedBitmap, targetFile, JPEG_QUALITY)
                if (resizedBitmap != originalBitmap) {
                    resizedBitmap.recycle()
                }
                originalBitmap.recycle()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("ImageCompressionUtils", "Failed to compress bytes: ${e.message}")
            false
        }
    }

    /**
     * Lit une image et renvoie les octets JPEG compressés (pour l'écriture de frame APIC ID3).
     */
    suspend fun getCompressedJpegBytes(imageFile: File): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!imageFile.exists() || imageFile.length() == 0L) return@withContext null
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext null
            val resized = resizeBitmap(bitmap, MAX_DIMENSION)
            val stream = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            if (resized != bitmap) resized.recycle()
            bitmap.recycle()
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }
        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int
        if (width > height) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt().coerceAtLeast(1)
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt().coerceAtLeast(1)
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun saveBitmapToJpeg(bitmap: Bitmap, targetFile: File, quality: Int) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        FileOutputStream(targetFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            fos.flush()
        }
    }
}
