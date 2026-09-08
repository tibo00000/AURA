package com.aura.music.ui.version

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.music.BuildConfig
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.version.AppReleaseNotes
import com.aura.music.data.version.VersionReleaseNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel orchestrant la détection et la présentation des nouveautés de version (What's New).
 * Conforme à la règle MVVM stricte du projet :
 * - Zéro opération bloquante d'E/S (I/O) sur le Main Thread (UI).
 * - Exécution sécurisée sur Dispatchers.IO pour les SharedPreferences et le comptage Room.
 * - Résolution du paradoxe de première installation via l'existence d'anciennes préférences ou pistes locales.
 */
class WhatsNewViewModel(
    context: Context,
    private val repository: LocalLibraryRepository,
) : ViewModel() {

    private val prefs = context.applicationContext.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)

    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog.asStateFlow()

    private val _releaseNotes = MutableStateFlow<VersionReleaseNotes?>(null)
    val releaseNotes: StateFlow<VersionReleaseNotes?> = _releaseNotes.asStateFlow()

    init {
        checkVersionOnStartup()
    }

    private fun checkVersionOnStartup() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentCode = BuildConfig.VERSION_CODE
            val lastSeenCode = prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, -1)

            val shouldShow = when {
                lastSeenCode != -1 && lastSeenCode < currentCode -> {
                    // Saut de version avéré après mise à jour
                    true
                }
                lastSeenCode == -1 -> {
                    // Première rencontre avec la clé de versioning :
                    // Vérifier si l'utilisateur possède déjà une configuration existante ou des données Room
                    val hasExistingPrefs = prefs.all.keys.any { it != KEY_LAST_SEEN_VERSION_CODE }
                    val hasExistingData = repository.getTrackCount() > 0

                    if (hasExistingPrefs || hasExistingData) {
                        // Utilisateur existant migrant vers cette version
                        true
                    } else {
                        // Installation 100% vierge : enregistrement silencieux sans polluer la première ouverture
                        prefs.edit().putInt(KEY_LAST_SEEN_VERSION_CODE, currentCode).apply()
                        false
                    }
                }
                else -> false
            }

            if (shouldShow) {
                _releaseNotes.value = AppReleaseNotes.getNotesForVersion(currentCode)
                    ?: AppReleaseNotes.getLatestNotes()
                _showDialog.value = true
            }
        }
    }

    /**
     * Ferme le dialogue et persiste la version vue de manière asynchrone sur Dispatchers.IO.
     * Partagé entre onDismissRequest et le bouton principal pour garantir la non-réapparition.
     */
    fun dismiss() {
        _showDialog.value = false
        viewModelScope.launch(Dispatchers.IO) {
            prefs.edit().putInt(KEY_LAST_SEEN_VERSION_CODE, BuildConfig.VERSION_CODE).apply()
        }
    }

    /**
     * Ouvre manuellement le dialogue (ex: depuis les Paramètres) sans altérer la version vue.
     */
    fun openManually() {
        viewModelScope.launch(Dispatchers.IO) {
            _releaseNotes.value = AppReleaseNotes.getNotesForVersion(BuildConfig.VERSION_CODE)
                ?: AppReleaseNotes.getLatestNotes()
            _showDialog.value = true
        }
    }

    companion object {
        const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
    }

    class Factory(
        private val context: Context,
        private val repository: LocalLibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return WhatsNewViewModel(context, repository) as T
        }
    }
}
