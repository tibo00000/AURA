package com.aura.music.core

import android.content.Context
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.AuraApiService
import com.aura.music.data.network.AuraHttpClientFactory
import com.aura.music.data.player.PlaybackStateStore
import com.aura.music.data.player.QueueManager
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.SearchRepository
import com.aura.music.domain.player.PlaybackOrchestrator
import com.aura.music.ui.player.PlayerViewModel

class AuraAppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database by lazy { AuraDatabase.getInstance(appContext) }
    private val mediaStoreAudioDataSource by lazy { MediaStoreAudioDataSource(appContext) }

    val localLibraryRepository by lazy {
        LocalLibraryRepository(
            database = database,
            mediaStoreAudioDataSource = mediaStoreAudioDataSource,
        )
    }

    val auraApiService by lazy {
        AuraHttpClientFactory.createAuraApiService()
    }

    val searchRepository by lazy {
        SearchRepository(
            localLibraryRepository = localLibraryRepository,
            auraApiService = auraApiService,
        )
    }

    val queueManager by lazy { QueueManager() }

    val playbackStateStore by lazy {
        PlaybackStateStore(database.playbackSnapshotDao())
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
}

