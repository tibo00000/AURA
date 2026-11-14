package com.aura.music.desktop.domain

import com.aura.music.data.local.AuraDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DesktopPlaylistManagerTest {

    private lateinit var database: AuraDatabase
    private lateinit var playlistManager: DesktopPlaylistManager
    private lateinit var tempDbFile: File

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("aura_test_db_", ".db")
        database = AuraDatabase.getInstance(tempDbFile.absolutePath)
        playlistManager = DesktopPlaylistManager(database, cloudSyncManager = null)
    }

    @After
    fun tearDown() {
        try {
            if (::database.isInitialized) {
                database.close()
            }
        } catch (_: Throwable) {}
        if (::tempDbFile.isInitialized && tempDbFile.exists()) {
            tempDbFile.delete()
            File("${tempDbFile.absolutePath}-wal").delete()
            File("${tempDbFile.absolutePath}-shm").delete()
        }
    }

    @Test
    fun testCreatePlaylistInsertsOutboxAtomically() = runBlocking {
        val playlistId = playlistManager.createPlaylist("Rock Classics")
        
        // 1. Vérifie la création dans la table playlists
        val playlist = database.playlistDao().getPlaylistDetail(playlistId)
        assertNotNull(playlist)
        assertEquals("Rock Classics", playlist?.name)

        // 2. Vérifie la création synchrone dans sync_outbox
        val pendingOps = database.syncOutboxDao().getPendingOperations()
        val createOp = pendingOps.find { it.entityId == playlistId && it.entityType == "playlist" && it.operationType == "create" }
        assertNotNull(createOp)
        assertEquals("Rock Classics", createOp?.payloadJson)
        assertEquals("pending", createOp?.status)
    }

    @Test
    fun testRenamePlaylistInsertsOutboxAtomically() = runBlocking {
        val playlistId = playlistManager.createPlaylist("Initial Name")
        database.syncOutboxDao().clearAll()

        playlistManager.renamePlaylist(playlistId, "Updated Name")

        val playlist = database.playlistDao().getPlaylistDetail(playlistId)
        assertEquals("Updated Name", playlist?.name)

        val pendingOps = database.syncOutboxDao().getPendingOperations()
        val updateOp = pendingOps.find { it.entityId == playlistId && it.entityType == "playlist" && it.operationType == "update" }
        assertNotNull(updateOp)
        assertTrue(updateOp?.payloadJson?.contains("Updated Name") == true)
    }

    @Test
    fun testAddAndRemoveTrackInsertsOutboxAtomically() = runBlocking {
        val playlistId = playlistManager.createPlaylist("My Mix")
        database.syncOutboxDao().clearAll()

        playlistManager.addTrackToPlaylist(playlistId, "track_123")

        val tracks = database.playlistDao().getPlaylistTracks(playlistId)
        val pendingOpsAfterAdd = database.syncOutboxDao().getPendingOperations()
        val occurrence = tracks.single()
        val addOp = pendingOpsAfterAdd.find { it.entityId == occurrence.playlistItemId && it.entityType == "playlist_item" && it.operationType == "create" }
        assertNotNull(addOp)
        val payload = Json.parseToJsonElement(checkNotNull(addOp).payloadJson).jsonObject
        assertEquals("track_123", payload["track_id"]?.jsonPrimitive?.content)
        assertEquals(playlistId, payload["playlist_id"]?.jsonPrimitive?.content)

        database.syncOutboxDao().clearAll()
        playlistManager.addTrackToPlaylist(playlistId, "track_123")
        playlistManager.removeTrackFromPlaylist(playlistId, occurrence.playlistItemId)

        val pendingOpsAfterRemove = database.syncOutboxDao().getPendingOperations()
        val removeOp = pendingOpsAfterRemove.find { it.entityId == occurrence.playlistItemId && it.entityType == "playlist_item" && it.operationType == "delete" }
        assertNotNull(removeOp)
        assertEquals(1, database.playlistDao().getPlaylistTracks(playlistId).size)
    }

    @Test
    fun applyingPlaylistMetadataPreservesEveryOccurrence() = runBlocking {
        val playlistId = playlistManager.createPlaylist("Before")
        repeat(2) { playlistManager.addTrackToPlaylist(playlistId, "track_123") }
        val dao = database.playlistDao()
        val before = dao.getPlaylistTracks(playlistId)
        assertEquals(2, before.size)

        dao.upsertPlaylist(com.aura.music.data.local.PlaylistEntity(
            id = playlistId, name = "After", isPinned = false, createdAt = 1, updatedAt = 2,
        ))

        assertEquals("After", dao.getPlaylistDetail(playlistId)?.name)
        assertEquals(before, dao.getPlaylistTracks(playlistId))
    }

    @Test
    fun remotePositionCollisionDoesNotReplaceAnotherOccurrence() = runBlocking {
        val playlistId = playlistManager.createPlaylist("My Mix")
        repeat(2) { playlistManager.addTrackToPlaylist(playlistId, "track_123") }
        val dao = database.playlistDao()
        val before = dao.getPlaylistTracks(playlistId)
        val first = checkNotNull(dao.getPlaylistItem(before.first().playlistItemId))

        val failure = try {
            dao.upsertPlaylistItem(first.copy(position = before.last().position))
            null
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            exception
        }

        assertNotNull(failure)
        assertEquals(before, dao.getPlaylistTracks(playlistId))
    }
}
