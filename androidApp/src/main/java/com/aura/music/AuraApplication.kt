package com.aura.music

import android.app.Application
import com.aura.music.core.AuraAppContainer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuraApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AuraAppContainer
        private set

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        container = AuraAppContainer(this)
        container.playbackOrchestrator.connect()

        CoroutineScope(Dispatchers.Main).launch {
            val settings = container.localLibraryRepository.getSettings()
            if (settings != null && settings.syncEnabled) {
                container.syncRepository.schedulePeriodicSync()
            }
        }
    }

    override fun onTerminate() {
        container.playbackOrchestrator.disconnect()
        super.onTerminate()
    }
}
