package com.aura.music.data.media

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackMediaLinkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded striped guards. Order: track guard -> Room writer. Never lock across network I/O. */
class TrackMediaCoordinator {
    private val guards = Array(64) { Mutex() }
    suspend fun <T> withTrack(id: String, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        guards[(id.hashCode() and Int.MAX_VALUE) % guards.size].withLock { action() }
    }

    /** Caller holds withTrack; the old file survives failed validation and failed transfers. */
    fun publish(temp: File, target: File) {
        check(AudioIntegrityValidator.isValidAudioFile(temp, checkMetadata = false)) { "Fichier audio invalide" }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    suspend fun repair(database: AuraDatabase, downloadsDir: File, id: String): Boolean = withTrack(id) {
        repairLocked(database, downloadsDir, id)
    }

    internal suspend fun repairLocked(database: AuraDatabase, downloadsDir: File, id: String): Boolean {
        val current = database.trackDao().getMediaLinkByTrackId(id)
        val linked = current?.contentUri?.let(UriPathParser::parseLocalFile)
        val fallback = File(downloadsDir, "${id.replace(':',';')}.mp3")
        val file = listOfNotNull(linked, fallback).firstOrNull {
            AudioIntegrityValidator.isValidAudioFile(it, checkMetadata = false)
        } ?: return false
        val raw = database.trackDao().getRawTrackById(id) ?: return false
        if (linked == file && current?.availabilityStatus == "present" &&
            raw.canonicalAudioSourceType == "downloaded" && raw.isDownloadedByAura) return true
        withContext(NonCancellable) {
            database.useWriterConnection { writer -> writer.immediateTransaction {
                val latest = database.trackDao().getRawTrackById(id) ?: return@immediateTransaction
                database.trackDao().upsertTrack(latest.copy(canonicalAudioSourceType = "downloaded", isDownloadedByAura = true))
                database.trackDao().upsertTrackMediaLinks(listOf(MediaLinkResolver.resolveOrCreateMediaLink(
                    database, id, file.toURI().toString(), file.length(), current?.mimeType ?: "audio/mpeg", System.currentTimeMillis())))
            } }
        }
        return true
    }

    /** No physical deletion. A newer URI/generation, valid file or unavailable directory wins. */
    suspend fun purgeObserved(database: AuraDatabase, downloadsDir: File, observed: TrackMediaLinkEntity,
                              mediaStoreMissing: Boolean = false): Boolean = withTrack(observed.trackId) {
        val current = database.trackDao().getMediaLinkByTrackId(observed.trackId)
        if (current != observed) return@withTrack false
        if (repairLocked(database, downloadsDir, observed.trackId)) return@withTrack false
        if (UriPathParser.isContentUri(observed.contentUri)) {
            if (!mediaStoreMissing) return@withTrack false
        } else {
            val file = UriPathParser.parseLocalFile(observed.contentUri) ?: return@withTrack false
            if (file.parentFile?.let { it.isDirectory && it.canRead() } != true) return@withTrack false
            if (file.exists() && file.length() > 0L) return@withTrack false
        }
        database.useWriterConnection { writer -> writer.immediateTransaction {
            val deleted = database.trackDao().deleteObservedMediaLink(observed.trackId, observed.contentUri, observed.lastScannedAt)
            if (deleted > 0) {
                val raw = database.trackDao().getRawTrackById(observed.trackId)
                if (raw?.canonicalAudioSourceType == "downloaded") {
                    database.trackDao().upsertTrack(raw.copy(canonicalAudioSourceType = "cloud", isDownloadedByAura = false))
                }
            }
            deleted > 0
        } }
    }

    companion object { val shared = TrackMediaCoordinator() }
}
