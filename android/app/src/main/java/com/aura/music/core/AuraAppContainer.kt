package com.aura.music.core

import android.content.Context
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.repository.LocalLibraryRepository

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
}
