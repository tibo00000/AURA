package com.aura.music.desktop.domain

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.local.createAuraDatabase
import com.aura.music.data.network.PlaylistItemResponse
import com.aura.music.data.network.PlaylistSnapshotEntry
import com.aura.music.data.network.PlaylistSnapshotResponse
import com.aura.music.data.playlist.PlaylistSnapshotReconciler
import java.time.Instant
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistSnapshotReconcilerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val user = "account-a"
    private val original = "2026-11-14T10:00:00.000001Z"
    private val updated = "2026-11-14T10:00:00.000002Z"

    private suspend fun AuraDatabase.usingDatabase(block: suspend (AuraDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun snapshot(vararg ids: String, stamp: String = original, revision: Long = 1) = PlaylistSnapshotResponse(
        user,
        1,
        ids.size,
        listOf(
            PlaylistSnapshotEntry(
                id = "playlist",
                userId = user,
                name = "Mix",
                isPinned = false,
                createdAt = original,
                updatedAt = stamp,
                orderToken = "order:$stamp",
                itemCount = ids.size,
                items = ids.mapIndexed { position, id ->
                    PlaylistItemResponse(id, "playlist", "recording", position, original)
                },
            ),
        ),
        revision = revision,
        contractVersion = 1,
    )

    @Test
    fun twoDevicesConvergeAndPendingOccurrenceSurvivesUntilAcknowledged() = runBlocking {
        createAuraDatabase(temporary.newFile("first.db")).usingDatabase { first ->
            createAuraDatabase(temporary.newFile("second.db")).usingDatabase { second ->
                val firstSync = PlaylistSnapshotReconciler(first) { Instant.parse(it).toEpochMilli() }
                val secondSync = PlaylistSnapshotReconciler(second) { Instant.parse(it).toEpochMilli() }
                val initial = snapshot("a", "b")
                firstSync.apply(initial, user, { true })
                secondSync.apply(initial, user, { true })
                val localMedia = TrackMediaLinkEntity(
                    id = "local-link",
                    trackId = "recording",
                    mediaStoreId = 12,
                    contentUri = "content://media/external/audio/media/12",
                    availabilityStatus = "available",
                    lastScannedAt = 1,
                )
                second.trackDao().upsertTrackMediaLinks(listOf(localMedia))
                DesktopPlaylistManager(first).addTrackToPlaylist("playlist", "recording")
                val pending = first.syncOutboxDao().getPendingOperations().single()
                val newOrder = snapshot("b", pending.entityId, "a", stamp = updated, revision = 2)

                firstSync.apply(initial, user, { true }, mapOf(pending.id to 2))
                assertEquals(listOf("a", "b", pending.entityId), first.playlistDao().getPlaylistItems("playlist").map { it.id })
                assertEquals(listOf(pending.copy(userId = user)), first.syncOutboxDao().getPendingOperations())
                secondSync.apply(newOrder, user, { true })
                firstSync.apply(newOrder, user, { true }, mapOf(pending.id to 2))
                firstSync.apply(initial, user, { true })
                firstSync.apply(PlaylistSnapshotResponse(user, 0, 0, emptyList(), 1, 1), user, { true })
                assertEquals(second.playlistDao().getPlaylistItems("playlist"), first.playlistDao().getPlaylistItems("playlist"))
                assertEquals(emptyList<String>(), first.syncOutboxDao().getPendingOperations().map { it.id })

                val deletion = PlaylistSnapshotResponse(user, 0, 0, emptyList(), 3, 1)
                val unconfirmed = DesktopPlaylistManager(second).createPlaylist("Only on the second device")
                firstSync.apply(deletion, user, { true })
                secondSync.apply(deletion, user, { true })
                assertNull(first.playlistDao().getPlaylistEntity("playlist"))
                assertNull(second.playlistDao().getPlaylistEntity("playlist"))
                assertNotNull(second.playlistDao().getPlaylistEntity(unconfirmed))
                assertNotNull(second.trackDao().getRawTrackById("recording"))
                assertEquals(localMedia, second.trackDao().getMediaLinkByTrackId("recording"))
            }
        }
    }

    @Test
    fun truncatedSnapshotCannotDeleteAnOccurrenceOrAcknowledgeOutbox() = runBlocking {
        createAuraDatabase(temporary.newFile("truncated.db")).usingDatabase { db ->
            val sync = PlaylistSnapshotReconciler(db) { Instant.parse(it).toEpochMilli() }
            sync.apply(snapshot("a", "b"), user, { true })
            DesktopPlaylistManager(db).removeTrackFromPlaylist("playlist", "b")
            val op = db.syncOutboxDao().getPendingOperations().single()
            val failure = runCatching {
                sync.apply(snapshot(stamp = updated, revision = 2).copy(itemCount = 2), user, { true }, mapOf(op.id to 2))
            }
            assertEquals(true, failure.isFailure)
            assertEquals(listOf("a"), db.playlistDao().getPlaylistItems("playlist").map { it.id })
            assertEquals(listOf(op), db.syncOutboxDao().getPendingOperations())
        }
    }

    @Test
    fun migrationOpensVersionFiveAndPreservesExistingOccurrencesAndOutbox() = runBlocking {
        val file = temporary.newFile("legacy.db")
        val playlistId = createAuraDatabase(file).let { db ->
            try {
                DesktopPlaylistManager(db).createPlaylist("Legacy")
            } finally {
                db.close()
            }
        }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE playlist_snapshot_state")
                statement.execute("DROP TABLE playlist_sync_state")
                statement.execute("ALTER TABLE sync_outbox DROP COLUMN user_id")
                statement.execute("ALTER TABLE user_settings DROP COLUMN sync_token_user_id")
                statement.execute("PRAGMA user_version = 5")
            }
        }
        createAuraDatabase(file).usingDatabase { db ->
            assertEquals("Legacy", db.playlistDao().getPlaylistEntity(playlistId)?.name)
            val pending = db.syncOutboxDao().getPendingOperations().single()
            assertEquals(playlistId, pending.entityId)
            assertNull(pending.userId)
            assertNull(db.playlistDao().getSnapshotRevision(user))
        }
    }

    @Test
    fun interruptedApplyRollsBackAndReplaysAfterRestart() = runBlocking {
        val file = temporary.newFile("restart.db")
        val operationId = createAuraDatabase(file).let { db ->
            try {
                val sync = PlaylistSnapshotReconciler(db) { Instant.parse(it).toEpochMilli() }
                sync.apply(snapshot("a", "b"), user, { true })
                DesktopPlaylistManager(db).removeTrackFromPlaylist("playlist", "a")
                val op = db.syncOutboxDao().getPendingOperations().single()
                var sessionChecks = 0
                val interrupted = runCatching {
                    sync.apply(snapshot("b", "c", revision = 2), user, { sessionChecks++ == 0 }, mapOf(op.id to 2))
                }
                assertEquals(true, interrupted.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                op.id
            } finally {
                db.close()
            }
        }
        createAuraDatabase(file).usingDatabase { db ->
            assertEquals(1L, db.playlistDao().getSnapshotRevision(user))
            assertEquals(listOf("b"), db.playlistDao().getPlaylistItems("playlist").map { it.id })
            assertEquals(operationId, db.syncOutboxDao().getPendingOperations().single().id)
            PlaylistSnapshotReconciler(db) { Instant.parse(it).toEpochMilli() }.apply(
                snapshot("b", "c", revision = 2), user, { true }, mapOf(operationId to 2),
            )
            assertEquals(listOf("b", "c"), db.playlistDao().getPlaylistItems("playlist").map { it.id })
            assertEquals(emptyList<String>(), db.syncOutboxDao().getPendingOperations().map { it.id })
        }
    }

    @Test
    fun associationRestoresOnAnotherDeviceWithoutChangingEntries() = runBlocking {
        val sourceId = "0123456789ABCDEFGHIJKL"
        val original = snapshot("a", "b")
        val associated = original.copy(revision = 2, playlists = original.playlists.map {
            it.copy(sourceProvider = "spotify", sourceExternalId = sourceId, sourceRevision = 1)
        })
        createAuraDatabase(temporary.newFile("association-first.db")).usingDatabase { first ->
            val sync = PlaylistSnapshotReconciler(first) { Instant.parse(it).toEpochMilli() }
            sync.apply(original, user, { true })
            val entries = first.playlistDao().getPlaylistItems("playlist")
            sync.apply(associated, user, { true })
            assertEquals(entries, first.playlistDao().getPlaylistItems("playlist"))

            createAuraDatabase(temporary.newFile("association-reinstalled.db")).usingDatabase { other ->
                val otherSync = PlaylistSnapshotReconciler(other) { Instant.parse(it).toEpochMilli() }
                otherSync.apply(associated, user, { true })
                assertEquals(entries, other.playlistDao().getPlaylistItems("playlist"))
                assertEquals(sourceId, other.playlistDao().getPlaylistSyncState("playlist")?.sourceExternalId)

                // A direct acknowledgement can arrive before an older snapshot already in flight.
                val state = requireNotNull(other.playlistDao().getPlaylistSyncState("playlist"))
                other.playlistDao().upsertPlaylistSyncState(
                    state.copy(sourceProvider = null, sourceExternalId = null, sourceRevision = 2),
                )
                otherSync.apply(associated, user, { true })
                val detached = other.playlistDao().getPlaylistSyncState("playlist")
                assertNull(detached?.sourceProvider)
                assertEquals(2L, detached?.sourceRevision)
                assertEquals(entries, other.playlistDao().getPlaylistItems("playlist"))
            }
        }
    }
}
