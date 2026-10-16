package com.aura.music.service

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.aura.music.AuraApplication

/**
2:  * Service d'entree pour l'interface Android Auto d'AURA.
3:  * Utilise les templates de la Car App Library.
4:  */
class AuraCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.Builder(applicationContext)
            .addAllowedHosts(com.aura.music.R.array.hosts_allowlist)
            .build()
    }

    override fun onCreateSession(): Session {
        val container = (application as AuraApplication).container
        return AuraCarSession(container.localLibraryRepository)
    }
}
