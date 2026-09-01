package com.aura.music.desktop.domain

import com.aura.music.data.local.AuraDatabase
import kotlinx.coroutines.runBlocking
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
        assertEquals("Updated Name", updateOp?.payloadJson)
    }

    @Test
    fun testAddAndRemoveTrackInsertsOutboxAtomically() = runBlocking {
        val playlistId = playlistManager.createPlaylist("My Mix")
        database.syncOutboxDao().clearAll()

        playlistManager.addTrackToPlaylist(playlistId, "track_123")

        val tracks = database.playlistDao().getPlaylistTracks(playlistId)
        val pendingOpsAfterAdd = database.syncOutboxDao().getPendingOperations()
        val addOp = pendingOpsAfterAdd.find { it.entityId == playlistId && it.entityType == "playlist_item" && it.operationType == "create" }
        assertNotNull(addOp)
        assertEquals("track_123", addOp?.payloadJson)

        database.syncOutboxDao().clearAll()
        playlistManager.removeTrackFromPlaylist(playlistId, "track_123")

        val pendingOpsAfterRemove = database.syncOutboxDao().getPendingOperations()
        val removeOp = pendingOpsAfterRemove.find { it.entityId == playlistId && it.entityType == "playlist_item" && it.operationType == "delete" }
        assertNotNull(removeOp)
        assertEquals("track_123", removeOp?.payloadJson)
    }
}
