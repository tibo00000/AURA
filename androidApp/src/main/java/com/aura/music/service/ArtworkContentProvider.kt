package com.aura.music.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * ContentProvider public/exporte permettant a Android Auto, au systeme et aux widgets constructeurs
 * d'acceder de maniere securisee mais ouverte aux pochettes d'albums locales telechargees.
 */
class ArtworkContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: throw FileNotFoundException("Context is null")
        
        // Format attendu: content://com.aura.music.artwork/covers/filename.jpg
        val pathSegments = uri.pathSegments
        if (pathSegments.size >= 2 && pathSegments[0] == "covers") {
            val fileName = pathSegments[1]
            val coversDir = File(context.filesDir, "covers")
            val file = File(coversDir, fileName)
            if (file.exists()) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        throw FileNotFoundException("Artwork file not found for URI: $uri")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
