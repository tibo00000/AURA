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

    open class FakeAuraApiService : AuraApiService {
        var versionResponse: AppVersionResponseData = AppVersionResponseData(
            versionCode = 2,
            versionName = "1.0.1",
            downloadUrl = "/app/updates/desktop/AURA-1.0.1.msi",
            sha256 = "auto",
            releaseNotes = "Correctifs de stabilité",
            minSupportedVersion = 1
        )

        override suspend fun getAppVersion(platform: String): AuraResponse<AppVersionResponseData> {
            return AuraResponse(data = versionResponse, error = null, meta = null)
        }

        override suspend fun search(query: String, limitTracks: Int, limitArtists: Int, limitAlbums: Int) = throw NotImplementedError()
        override suspend fun getArtist(id: String) = throw NotImplementedError()
        override suspend fun getAlbum(id: String) = throw NotImplementedError()
        override suspend fun resolveArtist(name: String, trackTitle: String?, albumTitle: String?) = throw NotImplementedError()
        override suspend fun resolveAlbum(title: String, artistName: String?, trackTitle: String?) = throw NotImplementedError()
        override suspend fun createDownload(token: String, request: com.aura.music.data.network.DownloadRequestDto) = throw NotImplementedError()
        override suspend fun listDownloads(token: String, status: String?, limit: Int, cursor: String?) = throw NotImplementedError()
        override suspend fun retryDownload(token: String, jobId: String) = throw NotImplementedError()
        override suspend fun resolveDownload(token: String, jobId: String, request: com.aura.music.data.network.ResolveDownloadRequestDto) = throw NotImplementedError()
        override suspend fun searchCandidates(token: String, artist: String?, title: String?, query: String?, limit: Int) = throw NotImplementedError()
        override suspend fun reassignAudio(token: String, request: com.aura.music.data.network.ReassignAudioRequestDto) = throw NotImplementedError()
        override suspend fun getJobStatus(token: String, jobId: String) = throw NotImplementedError()
        override suspend fun uploadCookies(token: String, request: com.aura.music.data.network.CookieUploadRequestDto) = throw NotImplementedError()
        override suspend fun downloadFile(token: String, jobId: String) = throw NotImplementedError()
        override suspend fun deleteDownloadJob(token: String, jobId: String) = throw NotImplementedError()
        override suspend fun bootstrap(token: String, request: com.aura.music.data.network.BootstrapRequestDto) = throw NotImplementedError()
        override suspend fun pushBatch(token: String, request: com.aura.music.data.network.PushBatchRequestDto) = throw NotImplementedError()
        override suspend fun pullBatch(token: String, request: com.aura.music.data.network.PullBatchRequestDto) = throw NotImplementedError()
        override suspend fun getPlaylists(token: String) = throw NotImplementedError()
        override suspend fun getLikes(token: String) = throw NotImplementedError()
        override suspend fun likeTrack(token: String, trackId: String, sourceContextType: String?, sourceContextId: String?) = throw NotImplementedError()
        override suspend fun unlikeTrack(token: String, trackId: String) = throw NotImplementedError()
        override suspend fun getPlaybackSnapshot(token: String) = throw NotImplementedError()
        override suspend fun updatePlaybackSnapshot(token: String, snapshot: com.aura.music.data.network.PlaybackSnapshotResponse) = throw NotImplementedError()
        override suspend fun getHistory(token: String) = throw NotImplementedError()
        override suspend fun createPlaylist(token: String, request: com.aura.music.data.network.PlaylistCreate) = throw NotImplementedError()
        override suspend fun updatePlaylist(token: String, id: String, request: com.aura.music.data.network.PlaylistUpdate) = throw NotImplementedError()
        override suspend fun deletePlaylist(token: String, id: String) = throw NotImplementedError()
        override suspend fun appendTrackToPlaylist(token: String, id: String, request: com.aura.music.data.network.PlaylistItemCreate) = throw NotImplementedError()
        override suspend fun removeTrackFromPlaylist(token: String, id: String, trackId: String) = throw NotImplementedError()
        override suspend fun uploadSyncFile(
            token: String,
            trackId: String,
            fileBytes: ByteArray,
            mimeType: String,
            title: String?,
            artistName: String?,
            albumTitle: String?,
            durationMs: Long?,
            artistId: String?,
            albumId: String?,
            coverUri: String?
        ) = throw NotImplementedError()
        override suspend fun downloadSyncFile(token: String, trackId: String) = throw NotImplementedError()
        override suspend fun listSyncFiles(token: String) = throw NotImplementedError()
        override suspend fun updateSyncFileMetadata(token: String, trackId: String, request: com.aura.music.data.network.SyncedFileMetadataUpdateRequest) = throw NotImplementedError()
        override suspend fun batchUpdateSyncFilesMetadata(token: String, items: List<com.aura.music.data.network.SyncedFileMetadataUpdateRequest>) = throw NotImplementedError()
        override suspend fun deleteSyncFile(token: String, trackId: String) = throw NotImplementedError()
    }

    private lateinit var tempUpdatesDir: File

    @Before
    fun setUp() {
        tempUpdatesDir = java.nio.file.Files.createTempDirectory("aura_test_updates_").toFile()
        fakeApiService = FakeAuraApiService()
        updateManager = DesktopUpdateManager(fakeApiService, scope, updatesDir = tempUpdatesDir)
    }

    @org.junit.After
    fun tearDown() {
        if (::tempUpdatesDir.isInitialized && tempUpdatesDir.exists()) {
            tempUpdatesDir.deleteRecursively()
        }
    }

    @Test
    fun testDesktopEnvironmentDetection() {
        assertNotNull(DesktopEnvironment.getUpdatesDir())
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
        val markerFile = File(tempUpdatesDir, ".update_pending.json")
        val errorMarker = File(tempUpdatesDir, ".update_pending.json.err")

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
        val markerFile = File(tempUpdatesDir, ".update_pending.json")

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
