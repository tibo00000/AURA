package com.aura.music.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Separate from evictable cache files and Android backup; all access is on a loader/IO thread. */
internal class PrivateCacheGenerationStore(context: Context) {
    private val context = context.applicationContext

    private fun record(cacheUid: Long, resource: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(resource.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val root = File(context.noBackupFilesDir, "private_stream_generations")
        return File(File(root, cacheUid.toString()), hash)
    }

    fun read(cacheUid: Long, resource: String): String {
        val file = record(cacheUid, resource)
        // File.exists() also returns false on denied access; only genuine absence is legacy generation 0.
        val value = try { Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8) }
        catch (missing: NoSuchFileException) { return "0" }
        if (!value.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
            throw IOException("Génération audio invalide")
        return value
    }

    fun advance(cacheUid: Long, resource: String): String {
        val file = record(cacheUid, resource)
        val parent = checkNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Persistance audio indisponible")
        val value = UUID.randomUUID().toString()
        val pending = File(parent, file.name + "." + value + ".pending")
        try {
            FileOutputStream(pending).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Unlike File.renameTo, REPLACE_EXISTING also replaces the destination on Windows.
            // No non-atomic fallback: if unsupported/unwritable, eviction never starts.
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
        if (read(cacheUid, resource) != value) throw IOException("Persistance audio non confirmée")
        return value
    }
}
