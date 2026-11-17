package com.aura.music.service

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.PlaylistAcquisitionStatus
import com.aura.music.data.player.PersistedPlaybackSnapshot
import com.aura.music.data.player.QueueManager
import com.aura.music.domain.player.RepeatMode
import com.aura.music.domain.player.TrackPlaybackResolver
import com.aura.music.domain.player.buildPlaylistQueue
import com.aura.music.domain.player.matchesQueueTrack
import com.aura.music.domain.player.occurrenceId
import com.aura.music.domain.player.resolvePlaylistLocalSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PlaylistMedia3Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val playlistId = "playlist:mix"
    private val content = "content://media/external/audio/media/42"

    private fun row(id: String, track: String = "recording", uri: String? = content) = PlaylistTrackRow(
        playlistItemId = id, playlistId = playlistId, trackId = track, position = 0, addedAt = 0,
        title = track, artistName = "Artist", albumTitle = null, contentUri = uri, durationMs = null,
        coverUri = null, artistId = null, albumId = null, isLiked = false,
    )

    @Test
    fun media3RepeatAllTransitionsBetweenOccurrencesOfTheSameRecording() {
        val queue = QueueManager()
        val eligible = buildPlaylistQueue(
            listOf(row("occ:first"), row("missing", "different", uri = null), row("occ:second")), emptySet(),
        )
        val resumed = restorePlaylistMediaItems(context, queue, playlistId, eligible, snapshot("occ:second"))
        val player = ExoPlayer.Builder(context).build()
        try {
            player.setMediaItems(resumed.mediaItems, resumed.startIndex, resumed.startPositionMs)
            assertEquals("occ:second", player.currentMediaItem?.occurrenceId())
            assertEquals(4200L, player.currentPosition)
            assertTrue(player.hasNextMediaItem())
            assertEquals("occ:second", queue.state.value.currentTrack?.internalId)
            val next = requireNotNull(queue.peekNext())
            player.seekToNextMediaItem()
            val item = requireNotNull(player.currentMediaItem)
            assertEquals("occ:first", item.occurrenceId())
            assertTrue(item.matchesQueueTrack(next))
            assertFalse(item.matchesQueueTrack(eligible.last()))
            assertEquals("occ:first", queue.next()?.internalId)
        } finally {
            player.release()
        }
    }

    @Test
    fun media3CopiesBrowseIdentityWithoutExposingTheAudioUri() {
        val eligible = buildPlaylistQueue(listOf(row("occ:first"), row("occ:second / été")), emptySet())
        val browse = eligible.map { it.toPlaylistMediaItem(context, playlistId) }
            .map { MediaItem.fromBundle(it.toBundleIncludeLocalConfiguration()) }
        assertEquals(2, browse.map { it.mediaId }.distinct().size)
        assertEquals(playlistId to "occ:second / été", decodePlaylistBrowseId(browse.last().mediaId))
        assertNull(browse.last().localConfiguration)
        assertTrue(browse.last().mediaMetadata.isPlayable == true)
        assertFalse(MediaItem.fromUri(Uri.parse(content)).buildUpon().setMediaId("recording").build()
            .matchesQueueTrack(eligible.last()))
    }

    @Test
    fun plainTrackRequestsStayInTheLibraryEvenWithAPlaylistContext() = runBlocking {
        val eligible = buildPlaylistQueue(listOf(row("occ:first"), row("occ:second")), emptySet())
        val queue = QueueManager().apply { setContext("playlist", playlistId, eligible, 1) }
        val before = queue.state.value

        for (trackId in listOf("recording", "different-track")) {
            val request = MediaItem.Builder().setMediaId(trackId).build()
            assertNull(resolvePlaylistBrowseItems(request, context, queue,
                prepare = { error("A library request must not prepare a playlist") }, isCurrent = { true }))
        }
        assertEquals(before, queue.state.value)
    }

    @Test
    fun browseRequestSelectsItsExplicitOccurrenceAndRefusesUnavailableOrStaleRequests() = runBlocking {
        val eligible = buildPlaylistQueue(listOf(row("occ:first"), row("occ:second")), emptySet())
        val queue = QueueManager()
        val request = eligible.last().toPlaylistMediaItem(context, playlistId)
        val resolved = resolvePlaylistBrowseItems(request, context, queue, prepare = { id ->
            assertEquals(playlistId, id)
            eligible
        }, isCurrent = { true })
        assertEquals("occ:second", resolved?.first()?.occurrenceId())
        assertEquals("occ:second", queue.state.value.currentTrack?.internalId)
        val before = queue.state.value

        val unavailable = runCatching {
            resolvePlaylistBrowseItems(request, context, queue, prepare = { eligible.take(1) }, isCurrent = { true })
        }.exceptionOrNull()
        assertTrue(unavailable is IllegalStateException)
        var sessionIsCurrent = true
        val stale = runCatching {
            resolvePlaylistBrowseItems(
                eligible.first().toPlaylistMediaItem(context, playlistId), context, queue,
                prepare = {
                    sessionIsCurrent = false
                    eligible
                },
                isCurrent = { sessionIsCurrent },
            )
        }.exceptionOrNull()
        assertTrue(stale is CancellationException)
        val malformed = MediaItem.Builder().setMediaId("playlist-entry:missing-occurrence").build()
        val invalid = runCatching {
            resolvePlaylistBrowseItems(malformed, context, queue,
                prepare = { error("Malformed IDs must not reach Room") }, isCurrent = { true })
        }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        assertEquals(before, queue.state.value)
    }

    @Test
    fun linkedLocalOccurrenceStreamsTheConfirmedCatalogIdAndRetainsItsIdentity() {
        val trackId = "track:local:42"
        val streamId = "trk_confirmed"
        val occurrence = "original-occurrence"
        val ready = buildPlaylistQueue(listOf(row(occurrence, trackId, uri = null)), setOf(trackId), mapOf(trackId to streamId))
        val restored = restorePlaylistMediaItems(context, QueueManager(), playlistId, ready,
            snapshot(occurrence).copy(currentTrackId = trackId))
        val media = restored.mediaItems.single()

        assertEquals(trackId, media.mediaId)
        assertEquals(occurrence, media.occurrenceId())
        assertEquals(streamId, media.localConfiguration?.uri?.lastPathSegment)
        assertEquals(4200L, restored.startPositionMs)
        assertTrue(buildPlaylistQueue(listOf(row(occurrence, trackId, uri = null)), setOf(trackId),
            mapOf(trackId to "../invalid")).isEmpty())
    }

    @Test
    fun restorationUsesOnlyEligibleOccurrencesAndResetsOffsetAfterLoss() {
        val queue = QueueManager()
        val eligible = buildPlaylistQueue(listOf(row("occ:first"), row("occ:second", uri = null)), emptySet())
        val fallback = restorePlaylistMediaItems(context, queue, playlistId, eligible, snapshot("occ:second"))
        assertEquals(C.TIME_UNSET, fallback.startPositionMs)
        assertEquals(listOf("occ:first"), fallback.mediaItems.map { it.occurrenceId() })

        val empty = restorePlaylistMediaItems(context, QueueManager(), playlistId, emptyList(), snapshot("occ:second"))
        assertEquals(C.INDEX_UNSET, empty.startIndex)
        assertTrue(empty.mediaItems.isEmpty())
    }

    @Test
    fun missingMediaStoreRowFallsBackToConfirmedCloudOrStaysOutOfTheRestoredQueue() {
        val absent = "content://media/external/audio/media/43"
        val requestedIds = mutableListOf<Long>()
        registerMediaStore { ids ->
            requestedIds += ids
            cursorFor(listOf(42L))
        }
        val rows = listOf(
            row("lost", uri = absent), row("kept", "local"), row("second-copy", "local"),
            row("cloud-fallback", "cloud", absent),
        )
        val eligible = buildPlaylistQueue(rows, setOf("cloud"))
        val missing = MediaStoreAudioDataSource(context).missingPlaylistAudioUris(eligible.mapNotNull { it.contentUri })
        val resolver = TrackPlaybackResolver(context.cacheDir.resolve("missing-downloads"))
        val ready = resolvePlaylistLocalSources(eligible, missing, resolver::resolve)

        assertEquals(listOf(43L, 42L), requestedIds)
        assertEquals(listOf("kept", "second-copy", "cloud-fallback"), ready.map { it.internalId })
        assertNull(ready.last().contentUri)
        assertEquals(true, ready.last().playlistCloudAvailable)
        val resumed = restorePlaylistMediaItems(context, QueueManager(), playlistId, ready, snapshot("lost"))
        assertEquals(C.TIME_UNSET, resumed.startPositionMs)
        assertEquals("kept", resumed.mediaItems.first().occurrenceId())
    }

    @Test
    fun mediaStoreLookupBatchesUniqueIdsAndDoesNotTreatAFailedBatchAsMissing() {
        val requestedSizes = mutableListOf<Int>()
        registerMediaStore { ids ->
            requestedSizes += ids.size
            if (501L in ids) throw IllegalStateException("MediaStore temporarily unavailable")
            cursorFor(ids.filterNot { it == 23L })
        }
        val indexed = (1..501).map { "content://media/external/audio/media/$it" }
        val uris = indexed + listOf(indexed.first(), "content://other-provider/audio/12", "file:///local.mp3")
        val missing = MediaStoreAudioDataSource(context).missingPlaylistAudioUris(uris)

        assertEquals(listOf(500, 1), requestedSizes)
        assertEquals(setOf("content://media/external/audio/media/23"), missing)
    }

    @Test
    fun browserRefreshesOnAvailabilityChangesAndAccountResetButIgnoresProgressAndEviction() {
        val pending = PlaylistAcquisitionStatus("track", "pending", "missing")
        val verifying = mapOf(playlistId to listOf(pending))
        val working = mapOf(playlistId to listOf(pending.copy(status = "admitted", jobStatus = "running")))
        val ready = mapOf(playlistId to listOf(pending.copy(status = "available", availability = "available")))

        assertEquals(emptyList<String>(), changedPlaylistBrowseFolders(verifying, working))
        assertEquals(listOf(playlistId), changedPlaylistBrowseFolders(working, ready))
        assertEquals(listOf(playlistId), changedPlaylistBrowseFolders(ready, emptyMap()))
        assertEquals(emptyList<String>(), changedPlaylistBrowseFolders(ready + ("evicted" to listOf(pending)), ready))
    }

    @Test
    fun browserPagesPreserveOrderWithLargeLibraryLimits() {
        val rows = (0 until 350).map { row("occ:$it") }
        val eligible = buildPlaylistQueue(rows, emptySet())
        assertEquals((100 until 200).map { "occ:$it" }, eligible.paginate(1, 100).map { it.internalId })
        assertEquals(eligible, eligible.paginate(0, Int.MAX_VALUE))
        assertTrue(eligible.paginate(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    }

    private fun snapshot(id: String) = PersistedPlaybackSnapshot(
        currentTrackId = "recording", contextType = "playlist", contextId = playlistId, contextIndex = 1,
        positionMs = 4200L, shuffleEnabled = false, repeatMode = RepeatMode.All, playlistOccurrenceId = id,
    )

    private fun cursorFor(ids: List<Long>) = MatrixCursor(arrayOf(MediaStore.Audio.Media._ID)).apply {
        for (id in ids) addRow(arrayOf(id))
    }

    private fun registerMediaStore(onQuery: (List<Long>) -> Cursor?) {
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        ShadowContentResolver.registerProviderInternal("media", object : ContentProvider() {
            override fun onCreate() = true

            override fun query(
                uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?,
            ): Cursor? {
                assertEquals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, uri)
                assertEquals(listOf(MediaStore.Audio.Media._ID), projection?.toList())
                return onQuery(requireNotNull(selectionArgs).map(String::toLong))
            }

            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Read only")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Read only")
        })
    }
}
