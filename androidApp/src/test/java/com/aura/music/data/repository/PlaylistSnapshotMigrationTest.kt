package com.aura.music.data.repository

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.AuraDatabaseConstructor
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.SyncOutboxEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.network.PlaylistSnapshotResponse
import com.aura.music.data.playlist.PlaylistSnapshotReconciler
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistSnapshotMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun open(file: File): AuraDatabase = Room.databaseBuilder<AuraDatabase>(
        context = RuntimeEnvironment.getApplication(),
        name = file.absolutePath,
        factory = { AuraDatabaseConstructor.initialize() },
    ).setDriver(AndroidSQLiteDriver()).addMigrations(AuraDatabase.MIGRATION_5_6, AuraDatabase.MIGRATION_6_7).build()

    @Test
    fun migratedLegacyPlaylistAndPendingOccurrenceSurviveAnEmptyInventory() = runBlocking {
        val file = temp.newFile("legacy.db")
        val playlist = PlaylistEntity("legacy", "Répétitions", isPinned = false, createdAt = 1, updatedAt = 2)
        val occurrence = PlaylistItemEntity("occurrence", playlist.id, "recording", 0, 2)
        val operation = SyncOutboxEntity(
            id = "legacy-op",
            entityType = "playlist_item",
            entityId = occurrence.id,
            operationType = "create",
            payloadJson = """{"playlist_id":"legacy","track_id":"recording","position":0}""",
            status = "pending",
            createdAt = 2,
            updatedAt = 2,
        )
        val before = open(file)
        try {
            before.playlistDao().insertPlaylist(playlist)
            before.trackDao().upsertTrack(TrackEntity(
                id = occurrence.trackId,
                primaryArtistId = null,
                albumId = null,
                title = "Original",
                normalizedTitle = "original",
                displayArtistName = "Artist",
                canonicalAudioSourceType = "online",
                isLiked = false,
                isDownloadedByAura = false,
                createdAt = 1,
                updatedAt = 1,
            ))
            before.playlistDao().insertPlaylistItem(occurrence)
            before.syncOutboxDao().insert(operation)
        } finally {
            before.close()
        }
        AndroidSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("DROP TABLE playlist_snapshot_state")
            connection.execSQL("DROP TABLE playlist_sync_state")
            connection.execSQL("ALTER TABLE sync_outbox RENAME TO newer_sync_outbox")
            connection.execSQL("""
                CREATE TABLE sync_outbox (
                    id TEXT NOT NULL PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
                    operation_type TEXT NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL,
                    attempt_count INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO sync_outbox SELECT id, entity_type, entity_id, operation_type, payload_json,
                    status, attempt_count, created_at, updated_at FROM newer_sync_outbox
            """.trimIndent())
            connection.execSQL("DROP TABLE newer_sync_outbox")
            connection.execSQL("CREATE INDEX index_sync_outbox_status ON sync_outbox(status)")
            connection.execSQL("CREATE INDEX index_sync_outbox_created_at ON sync_outbox(created_at)")
            connection.execSQL("DROP TABLE user_settings")
            connection.execSQL("""
                CREATE TABLE user_settings (
                    id TEXT NOT NULL PRIMARY KEY, sync_enabled INTEGER NOT NULL,
                    online_search_enabled INTEGER NOT NULL, online_search_network_policy TEXT NOT NULL,
                    stats_sync_network_policy TEXT NOT NULL, last_sync_at INTEGER, sync_token TEXT
                )
            """.trimIndent())
            connection.execSQL("PRAGMA user_version = 5")
        }
        val after = open(file)
        try {
            assertNull(after.playlistDao().getPlaylistSyncState(playlist.id))
            PlaylistSnapshotReconciler(after) { Instant.parse(it).toEpochMilli() }.apply(
                PlaylistSnapshotResponse("account", 0, 0, emptyList(), 1, 1),
                "account",
                { true },
            )
            assertEquals(playlist, after.playlistDao().getPlaylistEntity(playlist.id))
            assertEquals(listOf(occurrence), after.playlistDao().getPlaylistItems(playlist.id))
            assertEquals(listOf(operation), after.syncOutboxDao().getPendingOperations())
            assertEquals("Original", after.trackDao().getRawTrackById(occurrence.trackId)?.title)
        } finally {
            after.close()
        }
    }
}
