package com.aura.music.desktop.domain

import com.aura.music.data.network.AppVersionResponseData
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.AuraResponse
import com.aura.music.desktop.utils.DesktopBuildConfig
import com.aura.music.desktop.utils.DesktopEnvironment
import com.aura.music.desktop.utils.DesktopInstallMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class DesktopUpdateManagerTest {

    private lateinit var fakeApiService: FakeAuraApiService
    private lateinit var updateManager: DesktopUpdateManager
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    class FakeAuraApiService : AuraApiService {
        var versionResponse: AppVersionResponseData = AppVersionResponseData(
            versionCode = 2,
            versionName = "1.0.1",
            downloadUrl = "/app/updates/desktop/AURA-1.0.1.msi",
            sha256 = "auto",
            releaseNotes = "Correctifs de stabilité",
            minSupportedVersion = 1
        )

        override suspend fun getAppVersion(platform: String): AuraResponse<AppVersionResponseData> {
            return AuraResponse(data = versionResponse, error = null, meta = emptyMap())
        }

        // Autres méthodes mockées
        override suspend fun getOnlineSearch(query: String, limit: Int) = throw NotImplementedError()
        override suspend fun getRecommendations(limit: Int) = throw NotImplementedError()
        override suspend fun getArtistDetail(artistId: String) = throw NotImplementedError()
        override suspend fun getAlbumDetail(albumId: String) = throw NotImplementedError()
        override suspend fun getPlaybackSnapshot(token: String) = throw NotImplementedError()
        override suspend fun updatePlaybackSnapshot(token: String, request: com.aura.music.data.network.PlaybackSnapshotRequest) = throw NotImplementedError()
        override suspend fun getHistory(token: String, limit: Int) = throw NotImplementedError()
        override suspend fun addHistoryItem(token: String, trackId: String, playedAt: String) = throw NotImplementedError()
        override suspend fun getLikedTracks(token: String) = throw NotImplementedError()
        override suspend fun addLikedTrack(token: String, trackId: String) = throw NotImplementedError()
        override suspend fun removeLikedTrack(token: String, trackId: String) = throw NotImplementedError()
        override suspend fun getPlaylists(token: String) = throw NotImplementedError()
        override suspend fun createPlaylist(token: String, name: String) = throw NotImplementedError()
        override suspend fun getPlaylistDetail(token: String, playlistId: String) = throw NotImplementedError()
        override suspend fun updatePlaylist(token: String, playlistId: String, name: String) = throw NotImplementedError()
        override suspend fun deletePlaylist(token: String, playlistId: String) = throw NotImplementedError()
        override suspend fun addTrackToPlaylist(token: String, playlistId: String, trackId: String) = throw NotImplementedError()
        override suspend fun removeTrackFromPlaylist(token: String, playlistId: String, trackId: String) = throw NotImplementedError()
        override suspend fun pushSyncBatch(token: String, request: com.aura.music.data.network.SyncPushRequest) = throw NotImplementedError()
        override suspend fun pullSyncBatch(token: String, lastSyncToken: Long?) = throw NotImplementedError()
        override suspend fun requestTrackAudioDownload(token: String, trackId: String) = throw NotImplementedError()
        override suspend fun getDownloadJobStatus(token: String, jobId: String) = throw NotImplementedError()
        override suspend fun requestTrackAudioReassignment(token: String, trackId: String, desiredTitle: String, desiredArtist: String) = throw NotImplementedError()
        override suspend fun getSyncFiles(token: String) = throw NotImplementedError()
        override suspend fun uploadSyncFile(token: String, fileBytes: ByteArray, filename: String, trackId: String, title: String, artist: String, album: String?) = throw NotImplementedError()
        override suspend fun updateSyncFileMetadata(token: String, trackId: String, title: String?, artist: String?, album: String?) = throw NotImplementedError()
        override suspend fun deleteSyncFile(token: String, trackId: String) = throw NotImplementedError()
    }

    @Before
    fun setUp() {
        fakeApiService = FakeAuraApiService()
        updateManager = DesktopUpdateManager(fakeApiService, scope)

        // Nettoyer les marqueurs de test éventuels
        val updatesDir = DesktopEnvironment.getUpdatesDir()
        File(updatesDir, ".update_pending.json").delete()
        File(updatesDir, ".update_pending.json.err").delete()
    }

    @Test
    fun testDesktopEnvironmentDetection() {
        assertNotNull(DesktopEnvironment.getUpdatesDir())
        assertTrue(DesktopEnvironment.getUpdatesDir().exists())
        assertNotNull(DesktopEnvironment.installMode)
        assertTrue(DesktopBuildConfig.VERSION_CODE >= 1)
        assertTrue(DesktopBuildConfig.VERSION_NAME.isNotBlank())
    }

    @Test
    fun testCheckForUpdateDetectsNewVersion() = runBlocking {
        fakeApiService.versionResponse = AppVersionResponseData(
            versionCode = DesktopBuildConfig.VERSION_CODE + 1,
            versionName = "2.0.0",
            downloadUrl = "/app/updates/desktop/AURA-2.0.0.msi",
            sha256 = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            releaseNotes = "Majeure mise à jour",
            minSupportedVersion = 1
        )

        updateManager.checkForUpdate(isManual = true)

        val state = updateManager.state.first { it !is DesktopUpdateState.Checking && it !is DesktopUpdateState.Idle }
        assertTrue("Devrait détecter une mise à jour disponible", state is DesktopUpdateState.UpdateAvailable)
        val updateAvailable = state as DesktopUpdateState.UpdateAvailable
        assertEquals(DesktopBuildConfig.VERSION_CODE + 1, updateAvailable.updateInfo.versionCode)
        assertEquals("2.0.0", updateAvailable.updateInfo.versionName)
    }

    @Test
    fun testCheckForUpdateReportsUpToDateWhenCurrent() = runBlocking {
        fakeApiService.versionResponse = AppVersionResponseData(
            versionCode = DesktopBuildConfig.VERSION_CODE,
            versionName = DesktopBuildConfig.VERSION_NAME,
            downloadUrl = "/app/updates/desktop/latest.msi",
            sha256 = "auto",
            releaseNotes = "Déjà à jour",
            minSupportedVersion = 1
        )

        updateManager.checkForUpdate(isManual = true)

        val state = updateManager.state.first { it !is DesktopUpdateState.Checking && it !is DesktopUpdateState.Idle }
        assertTrue("Devrait être UpToDate", state is DesktopUpdateState.UpToDate)
    }

    @Test
    fun testPostUpdateFailureDetectionAndCleanup() {
        val updatesDir = DesktopEnvironment.getUpdatesDir()
        val markerFile = File(updatesDir, ".update_pending.json")
        val errorMarker = File(updatesDir, ".update_pending.json.err")

        markerFile.writeText("""
            {
                "target_version_code": 999,
                "target_version_name": "9.9.9",
                "timestamp": 123456789
            }
        """.trimIndent())
        errorMarker.writeText("""{"status": "error", "code": 1603}""")

        updateManager.checkPostUpdateStatus()

        val state = updateManager.state.value
        assertTrue("Devrait signaler un échec post-mortem", state is DesktopUpdateState.PostUpdateNotification)
        val notif = state as DesktopUpdateState.PostUpdateNotification
        assertFalse(notif.success)
        assertTrue(notif.message.contains("9.9.9"))

        // Marqueurs supprimés pour ne pas boucler au prochain démarrage
        assertFalse(markerFile.exists())
        assertFalse(errorMarker.exists())
    }

    @Test
    fun testPostUpdateSuccessDetectionAndCleanup() {
        val updatesDir = DesktopEnvironment.getUpdatesDir()
        val markerFile = File(updatesDir, ".update_pending.json")

        // La version cible est la version courante -> Succès !
        markerFile.writeText("""
            {
                "target_version_code": ${DesktopBuildConfig.VERSION_CODE},
                "target_version_name": "${DesktopBuildConfig.VERSION_NAME}",
                "timestamp": 123456789
            }
        """.trimIndent())

        updateManager.checkPostUpdateStatus()

        val state = updateManager.state.value
        assertTrue("Devrait signaler un succès post-mortem", state is DesktopUpdateState.PostUpdateNotification)
        val notif = state as DesktopUpdateState.PostUpdateNotification
        assertTrue(notif.success)
        assertTrue(notif.message.contains(DesktopBuildConfig.VERSION_NAME))

        assertFalse(markerFile.exists())
    }
}
