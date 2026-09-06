package com.aura.music.core

import android.content.Context
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.player.PlaybackStateStore
import com.aura.music.data.player.QueueManager
import com.aura.music.data.repository.EnrichmentRepository
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.SearchRepository
import com.aura.music.data.repository.DownloadRepository
import com.aura.music.ui.downloads.DownloadsViewModel
import com.aura.music.domain.player.PlaybackOrchestrator
import com.aura.music.ui.player.PlayerViewModel

class AuraAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    private val database by lazy { AuraDatabase.getInstance(appContext) }
    private val mediaStoreAudioDataSource by lazy { MediaStoreAudioDataSource(appContext) }

    val authSessionManager by lazy {
        AuthSessionManager.getInstance(appContext)
    }

    val syncRepository by lazy {
        com.aura.music.data.repository.SyncRepository(
            database = database,
            apiService = auraApiService,
            context = appContext,
            authSessionManager = authSessionManager,
        )
    }

    val localLibraryRepository by lazy {
        LocalLibraryRepository(
            database = database,
            mediaStoreAudioDataSource = mediaStoreAudioDataSource,
            syncRepositoryProvider = { syncRepository },
            cloudFileRepositoryProvider = { cloudFileRepository },
            apiService = auraApiService,
            context = appContext
        )
    }

    val auraApiService: AuraApiService by lazy {
        com.aura.music.data.network.KtorAuraApiService.createDefault()
    }

    val cloudFileRepository by lazy {
        com.aura.music.data.repository.CloudFileRepository(
            database = database,
            apiService = auraApiService,
            context = appContext
        )
    }

    val enrichmentRepository by lazy {
        EnrichmentRepository(
            database = database,
            apiService = auraApiService,
            context = appContext,
        )
    }

    val searchRepository by lazy {
        SearchRepository(
            localLibraryRepository = localLibraryRepository,
            auraApiService = auraApiService,
            enrichmentRepository = enrichmentRepository,
        )
    }

    val downloadRepository by lazy {
        DownloadRepository(
            database = database,
            apiService = auraApiService,
            context = appContext
        )
    }

    val downloadsViewModelFactory by lazy {
        DownloadsViewModel.Factory(
            downloadRepository = downloadRepository,
            tokenProvider = { authSessionManager.getBearerHeader() }
        )
    }

    val queueManager by lazy { QueueManager() }

    val playbackStateStore by lazy {
        PlaybackStateStore(
            snapshotDao = database.playbackSnapshotDao(),
            database = database,
            apiService = auraApiService,
            context = appContext
        )
    }

    val playbackOrchestrator by lazy {
        PlaybackOrchestrator(
            context = appContext,
            queueManager = queueManager,
            stateStore = playbackStateStore,
            repository = localLibraryRepository,
        )
    }

    val playerViewModelFactory by lazy {
        PlayerViewModel.Factory(playbackOrchestrator, localLibraryRepository)
    }

    val connectivityObserver by lazy {
        ConnectivityObserver(appContext)
    }

    val spotifyAuthManager by lazy {
        com.aura.music.data.spotify.SpotifyAuthManager(appContext)
    }

    val playlistImportExportManager by lazy {
        com.aura.music.data.playlist.PlaylistImportExportManager(
            context = appContext,
            libraryRepository = localLibraryRepository,
            spotifyAuthManager = spotifyAuthManager,
            downloadRepository = downloadRepository
        )
    }
}

