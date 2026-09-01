package com.aura.music.desktop.state

import androidx.compose.runtime.*
import com.aura.music.data.local.TrackListRow

/**
 * Gestionnaire d'état UI global pour le client Desktop AURA.
 * Centralise la pile de navigation, l'ouverture des panneaux et la gestion du focus clavier.
 */
@Stable
class DesktopAppState {
    // Navigation Stack
    var screenStack by mutableStateOf(listOf("home"))
        private set

    val currentScreen: String
        get() = screenStack.lastOrNull() ?: "home"

    val canNavigateBack: Boolean
        get() = screenStack.size > 1

    // Detail Targets
    var selectedPlaylistId by mutableStateOf<String?>(null)
    var selectedAlbumId by mutableStateOf<String?>(null)
    var selectedArtistId by mutableStateOf<String?>(null)

    // Panneau Queue
    var isQueueOpen by mutableStateOf(true)

    // État de focus global des champs texte (évite les collisions de touches Espace/Mute)
    var isInputFocused by mutableStateOf(false)

    // Recherche
    var searchQuery by mutableStateOf("")
    var selectedSearchTab by mutableStateOf(0) // 0 = Bibliothèque, 1 = En ligne
    var searchTab: Int
        get() = selectedSearchTab
        set(value) { selectedSearchTab = value }

    // Dialogues
    var showCreatePlaylistDialog by mutableStateOf(false)
    var showImportPlaylistDialog by mutableStateOf(false)
    var showAddToPlaylistDialog by mutableStateOf(false)
    var trackIdToAddToPlaylist by mutableStateOf<String?>(null)
    var trackForPlaylistPicker by mutableStateOf<TrackListRow?>(null)
    var playlistToRename by mutableStateOf<Pair<String, String>?>(null) // id, currentName
    var playlistToDelete by mutableStateOf<Pair<String, String>?>(null) // id, name

    // Navigation methods
    fun navigateTo(screen: String) {
        screenStack = screenStack + screen
    }

    fun navigateBack(): Boolean {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
            return true
        }
        return false
    }

    fun navigateToRoot(screen: String) {
        screenStack = listOf(screen)
    }

    fun openPlaylist(playlistId: String) {
        selectedPlaylistId = playlistId
        navigateTo("playlist_detail")
    }

    fun openAlbum(albumId: String) {
        selectedAlbumId = albumId
        navigateTo("album_detail")
    }

    fun openArtist(artistId: String) {
        selectedArtistId = artistId
        navigateTo("artist_detail")
    }

    fun toggleQueue() {
        isQueueOpen = !isQueueOpen
    }
}
