package com.aura.music

import android.app.Application
import com.aura.music.core.AuraAppContainer

class AuraApplication : Application() {
    lateinit var container: AuraAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AuraAppContainer(this)
        container.playbackOrchestrator.connect()
    }

    override fun onTerminate() {
        container.playbackOrchestrator.disconnect()
        super.onTerminate()
    }
}
