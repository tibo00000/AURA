package com.aura.music.service

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.ui.car.AuraCarHubScreen
import com.aura.music.ui.car.LibraryBrowseScreen

/**
 * Session de cycle de vie pour l'application AURA dans le vehicule.
 * Instancie l'arborescence initiale d'ecrans.
 */
class AuraCarSession(
    private val repository: LocalLibraryRepository
) : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return AuraCarHubScreen(carContext, repository)
    }
}
