package com.aura.music.core

import android.content.Context
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
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

    init {
        // No database, token access or network work in the startup window.
        applicationScope.launch {
            kotlinx.coroutines.delay(30_000)
            authSessionManager.sessionScope.collectLatest { owner ->
                if (owner != null) com.aura.music.data.metadata.MetadataTransitionCoordinator(
                    database, authSessionManager, auraApiService,
                    com.aura.music.data.playlist.PlaylistSourceReader(spotifyAuthManager::getValidAccessToken),
                    localLibraryRepository::invalidateSearchIndex,
                ).run(owner)
            }
        }
    }

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

    val localLibraryRepository: LocalLibraryRepository by lazy {
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
        authenticatedApi(authSessionManager)
    }

    val cloudFileRepository by lazy {
        com.aura.music.data.repository.CloudFileRepository(
            database = database,
            apiService = auraApiService,
            context = appContext,
            downloadRepositoryProvider = { downloadRepository },
            onLibraryChanged = { localLibraryRepository.invalidateSearchIndex() },
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

    val trackPlaybackResolver by lazy {
        com.aura.music.domain.player.TrackPlaybackResolver(
            context = appContext,
            database = database
        )
    }

    val playbackOrchestrator by lazy {
        PlaybackOrchestrator(
            context = appContext,
            queueManager = queueManager,
            stateStore = playbackStateStore,
            repository = localLibraryRepository,
            playbackResolver = trackPlaybackResolver,
            playlistPlaybackProvider = { playlistPlayback },
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

    val playlistSourceRepository by lazy {
        com.aura.music.data.playlist.PlaylistSourceRepository(
            database, auraApiService, authSessionManager, syncRepository,
            com.aura.music.data.playlist.PlaylistSourceReader(spotifyAuthManager::getValidAccessToken),
        )
    }

    val playlistPlayback by lazy {
        com.aura.music.domain.player.PlaylistPlayback(
            authSessionManager, playlistSourceRepository, applicationScope, cloudFileRepository::getCachedCloudTrackIds,
            resolver = { trackPlaybackResolver },
            mediaStore = mediaStoreAudioDataSource,
            refreshDownloadJobs = { owner ->
                val bearer = authSessionManager.validSession(scope = owner).bearer
                val jobsConfirmed = downloadRepository.syncActiveJobs(bearer)
                if (!authSessionManager.isCurrent(owner)) false else {
                    localLibraryRepository.invalidateSearchIndex()
                    val inventory = cloudFileRepository.getOrFetchCloudFiles(forceRefresh = true)
                    if (!authSessionManager.isCurrent(owner)) false else {
                        jobsConfirmed && inventory.isSuccess
                    }
                }
            },
        )
    }

    val playlistImportExportManager by lazy {
        com.aura.music.data.playlist.PlaylistImportExportManager(
            context = appContext,
            libraryRepository = localLibraryRepository,
            spotifyAuthManager = spotifyAuthManager,
            downloadRepository = downloadRepository,
            sourceRepository = playlistSourceRepository,
            sourceReader = com.aura.music.data.playlist.PlaylistSourceReader(spotifyAuthManager::getValidAccessToken),
        )
    }

    val appUpdateManager by lazy {
        AppUpdateManager(
            context = appContext,
            apiService = auraApiService,
            scope = applicationScope,
        )
    }

    val whatsNewViewModelFactory by lazy {
        com.aura.music.ui.version.WhatsNewViewModel.Factory(
            context = appContext,
            repository = localLibraryRepository,
        )
    }
}


