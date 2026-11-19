package com.aura.music.data.metadata

import android.util.Log
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.aura.music.core.AuthSessionManager
import com.aura.music.data.local.*
import com.aura.music.data.network.*
import com.aura.music.data.playlist.PlaylistSourceReader
import com.aura.music.data.repository.hasDownloadPlaceholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One durable inventory per account/version, scheduled after startup, never an audio acquisition. */
internal class MetadataTransitionCoordinator(
    private val database: AuraDatabase,
    private val auth: AuthSessionManager,
    private val api: AuraApiService,
    private val reader: PlaylistSourceReader,
    private val onChanged: () -> Unit,
) {
    suspend fun run(owner: AuthSessionManager.Scope) = withContext(Dispatchers.IO) {
        val version = 1
        val dao = database.metadataTransitionDao()
        fun current() = auth.isCurrent(owner)
        if (!current()) return@withContext
        if (dao.hasRun(owner.userId, version) == 0) {
            val inventory = database.trackArtistDao().getTracksForTransition()
            database.useWriterConnection { writer -> writer.immediateTransaction {
                if (!current()) return@immediateTransaction
                dao.seed(inventory.map { MetadataTransitionStateEntity(owner.userId, it.id, version, "pending") })
                dao.beginRun(MetadataTransitionRunEntity(owner.userId, version))
            } }
        }
        var confirmed = 0
        var dirty = false
        while (current()) {
            val pending = dao.pending(owner.userId, version)
            if (pending.isEmpty()) break
            try {
                val bearer = auth.validSession(scope = owner).bearer
                val inventory = api.listSyncFiles(bearer)
                if (!current()) return@withContext
                val clouds = inventory.data?.items?.takeIf { inventory.error == null && inventory.meta?.partialFailure != true }
                    ?.associateBy { it.trackId } ?: error("Unconfirmed cloud inventory")
                for (state in pending) {
                    if (!current()) return@withContext
                    val expected = database.trackDao().getRawTrackById(state.trackId) ?: continue
                    try {
                        val cloud = clouds[state.trackId]
                        val seen = cloud?.takeIf { it.metadataTransitionVersion >= version }
                        val already = seen?.takeIf { it.metadataTransitionStatus == "resolved" && it.artists.isNotEmpty() }
                        val canImproveFromSpotify = com.aura.music.data.playlist.PlaylistRecordingIdentity.parse(state.trackId)?.provider == "spotify"
                        val direct = if (already == null && (seen == null || canImproveFromSpotify)) reader.readTrackMetadata(state.trackId) else null
                        val result = when {
                            already != null -> MetadataResolveResultDto(already.metadataTransitionStatus ?: if (already.artists.isEmpty()) "unidentified" else "resolved", artists = already.artists,
                                title = already.title, albumTitle = already.albumTitle, albumId = already.albumId, durationMs = already.durationMs, coverUri = already.coverUri)
                            direct != null -> MetadataResolveResultDto("resolved", artists = direct.artists, title = direct.title,
                                albumTitle = direct.album, durationMs = direct.durationMs, coverUri = direct.coverUri)
                            seen != null -> MetadataResolveResultDto(seen.metadataTransitionStatus ?: "unidentified", artists = seen.artists,
                                title = seen.title, albumTitle = seen.albumTitle, albumId = seen.albumId, durationMs = seen.durationMs, coverUri = seen.coverUri)
                            else -> {
                                val response = api.resolveMissingMetadata(bearer, MetadataResolveRequestDto(
                                    expected.id, expected.title, expected.displayArtistName, expected.displayAlbumTitle, expected.durationMs))
                                if (response.error != null || response.meta?.partialFailure == true) error("Unconfirmed metadata")
                                response.data ?: error("Missing metadata result")
                            }
                        }
                        if (!current()) return@withContext
                        val credits = result.artists
                        if (cloud != null && (seen == null || direct != null)) {
                            val response = api.updateSyncFileMetadata(bearer, state.trackId, SyncedFileMetadataUpdateRequest(
                                trackId = state.trackId, title = result.title, albumTitle = result.albumTitle, albumId = result.albumId,
                                durationMs = result.durationMs, coverUri = result.coverUri, artists = credits.takeIf { it.isNotEmpty() },
                                fillMissingOnly = true, expectedUpdatedAt = cloud.updatedAt, metadataTransitionVersion = version,
                            ))
                            if (response.error != null || response.data == null) error("Unconfirmed metadata persistence")
                        }
                        if (!current()) return@withContext
                        val plan = prepareArtistCredits(database, mapOf(expected.id to expected), mapOf(expected.id to credits), System.currentTimeMillis())
                        var changed = false
                        database.useWriterConnection { writer -> writer.immediateTransaction {
                            if (!current()) return@immediateTransaction
                            val live = database.trackDao().getRawTrackById(expected.id) ?: return@immediateTransaction
                            if (live != expected) {
                                dao.save(state.copy(status = "skipped_changed")); return@immediateTransaction
                            }
                            plan.apply(database, ::current)
                            val enriched = database.trackDao().getRawTrackById(live.id) ?: return@immediateTransaction
                            val title = if (enriched.hasDownloadPlaceholder() || enriched.title.isBlank()) result.title ?: enriched.title else enriched.title
                            val albumId = enriched.albumId ?: result.albumId?.takeIf {
                                !result.albumTitle.isNullOrBlank() && (enriched.displayAlbumTitle.isNullOrBlank() ||
                                    enriched.displayAlbumTitle.equals(result.albumTitle, ignoreCase = true))
                            }
                            if (enriched.albumId == null && albumId != null) {
                                val now = System.currentTimeMillis()
                                database.albumDao().insertAlbumsIgnore(listOf(AlbumEntity(albumId, enriched.primaryArtistId,
                                    result.albumTitle!!, com.aura.music.domain.search.SearchNormalizer.normalize(result.albumTitle),
                                    coverUri = result.coverUri, createdAt = now, updatedAt = now)))
                            }
                            val updated = enriched.copy(
                                albumId = albumId,
                                title = title, normalizedTitle = if (title == enriched.title) enriched.normalizedTitle else com.aura.music.domain.search.SearchNormalizer.normalize(title),
                                displayAlbumTitle = enriched.displayAlbumTitle?.takeIf { it.isNotBlank() } ?: result.albumTitle,
                                durationMs = enriched.durationMs?.takeIf { it > 0 } ?: result.durationMs,
                                coverUri = enriched.coverUri?.takeIf { it.isNotBlank() } ?: result.coverUri,
                            )
                            if (updated != enriched) database.trackDao().upsertTrack(updated.copy(updatedAt = System.currentTimeMillis()))
                            dao.save(state.copy(status = result.status, attempts = state.attempts + 1))
                            changed = updated != expected || plan.hasChanges
                        } }
                        if (changed) dirty = true
                        confirmed++
                        if (confirmed % 25 == 0) {
                            Log.i("MetadataTransition", "version=$version processed=$confirmed")
                            if (dirty && current()) { onChanged(); dirty = false }
                        }
                        delay(if (already == null) 500 else 0)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (!current()) return@withContext
                        database.useWriterConnection { writer -> writer.immediateTransaction {
                            if (current()) dao.save(state.copy(attempts = state.attempts + 1))
                        } }
                        throw failure // Do not send hundreds of requests to an unavailable provider.
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { delay(60_000) }
        }
        if (dirty && current()) onChanged()
        Log.i("MetadataTransition", "version=$version completed=$confirmed")
    }
}
