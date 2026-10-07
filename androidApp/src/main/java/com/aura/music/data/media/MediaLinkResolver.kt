package com.aura.music.data.media

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackMediaLinkEntity
import java.nio.ByteBuffer
import java.security.MessageDigest

object MediaLinkResolver {

    /**
     * Computes a deterministic strictly negative 64-bit ID for non-MediaStore tracks.
     * Prevents collisions with real MediaStore IDs (which are strictly positive) and
     * is immune to the kotlin.math.abs(Long.MIN_VALUE) overflow bug.
     */
    fun syntheticMediaStoreId(trackId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray(Charsets.UTF_8))
        val rawLong = ByteBuffer.wrap(digest, 0, 8).long
        // (rawLong and Long.MAX_VALUE) strips the sign bit, coerceAtLeast(1L) guarantees it is >= 1L.
        val positive = (rawLong and Long.MAX_VALUE).coerceAtLeast(1L)
        return -positive
    }

    /**
     * Resolves an existing media link entity for trackId or constructs a new deterministic entity.
     * Reusing existing entities preserves the original primary key ID and mediaStoreId,
     * avoiding SQLite UNIQUE constraint collisions when updating via Room @Upsert.
     */
    suspend fun resolveOrCreateMediaLink(
        database: AuraDatabase,
        trackId: String,
        contentUri: String,
        fileSizeBytes: Long?,
        mimeType: String?,
        now: Long
    ): TrackMediaLinkEntity {
        val existing = database.trackDao().getMediaLinkByTrackId(trackId)
        if (existing != null) {
            return existing.copy(
                contentUri = contentUri,
                fileSizeBytes = fileSizeBytes ?: existing.fileSizeBytes,
                mimeType = mimeType ?: existing.mimeType,
                availabilityStatus = "present",
                lastScannedAt = now
            )
        }

        var candidateId = syntheticMediaStoreId(trackId)
        while (database.trackDao().isMediaStoreIdTaken(candidateId) != null) {
            candidateId--
        }

        return TrackMediaLinkEntity(
            id = "media-link:$trackId",
            trackId = trackId,
            mediaStoreId = candidateId,
            contentUri = contentUri,
            fileSizeBytes = fileSizeBytes,
            mimeType = mimeType,
            dateModifiedEpochMs = now,
            availabilityStatus = "present",
            lastScannedAt = now
        )
    }
}
