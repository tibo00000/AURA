package com.aura.music

import android.app.Application
import com.aura.music.core.AuraAppContainer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AuraApplication : Application() {
    lateinit var container: AuraAppContainer
        private set

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
