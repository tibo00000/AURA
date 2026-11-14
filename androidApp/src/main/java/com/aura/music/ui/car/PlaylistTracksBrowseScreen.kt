package com.aura.music.ui.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.domain.player.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ecran de navigation dans les titres d'une playlist locale specifique pour Android Auto.
 */
class PlaylistTracksBrowseScreen(
    carContext: CarContext,
    private val playlistId: String,
    private val playlistName: String,
    private val repository: LocalLibraryRepository
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isLoaded = false
    private var tracksList: List<PlaylistTrackRow> = emptyList()
    private var coverIcons: Map<String, CarIcon> = emptyMap()
    private var playRequest: kotlinx.coroutines.Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                screenScope.cancel()
            }
        })
        loadData()
    }

    private fun loadData() {
        screenScope.launch {
            try {
                tracksList = repository.getPlaylistDetail(playlistId)?.tracks.orEmpty()
                val firstCovers = tracksList.asSequence().mapNotNull { it.coverUri }.distinct().take(32).toList()
                coverIcons = withContext(Dispatchers.IO) { firstCovers.associateWith(::loadCoverIcon) }
                val container = (carContext.applicationContext as com.aura.music.AuraApplication).container
                container.playlistPlayback.verifyLater(playlistId)
            } catch (e: Exception) {
                android.util.Log.e("PlaylistTracksScreen", "Erreur lors du chargement des morceaux de la playlist", e)
            } finally {
                isLoaded = true
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (!isLoaded) {
            return ListTemplate.Builder()
                .setTitle(playlistName)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Aucun titre dans cette playlist.")

        tracksList.forEach { track ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(track.artistName ?: "Artiste inconnu")
                    .setOnClickListener {
                        playTrack(track.playlistItemId)
                    }
                    .setImage(coverIcons[track.coverUri] ?: defaultCoverIcon())
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(playlistName)
            .setSingleList(listBuilder.build())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun loadCoverIcon(coverUriStr: String?): CarIcon {
        if (coverUriStr != null) {
            val filePath = coverUriStr.replace("file://", "")
            val file = java.io.File(filePath)
            if (file.exists()) {
                try {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = 4
                    }
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    if (bitmap != null) {
                        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PlaylistTracksScreen", "Error decoding cover bitmap", e)
                }
            }
        }
        return defaultCoverIcon()
    }

    private fun defaultCoverIcon(): CarIcon = CarIcon.Builder(
            IconCompat.createWithResource(
                carContext,
                android.R.drawable.ic_media_play
            )
        ).build()

    private fun playTrack(occurrenceId: String) {
        playRequest?.cancel()
        playRequest = screenScope.launch { playOccurrence(occurrenceId) }
    }

    private suspend fun playOccurrence(occurrenceId: String) {
        val container = (carContext.applicationContext as com.aura.music.AuraApplication).container
        val tracks = container.playlistPlayback.prepare(playlistId, tracksList)
        val startIndex = tracks.indexOfFirst { it.internalId == occurrenceId }
        val selected = tracks.getOrNull(startIndex)
        if (selected == null) {
            container.playlistPlayback.verifyLater(playlistId)
            androidx.car.app.CarToast.makeText(carContext, "Aucune source audio confirmée", androidx.car.app.CarToast.LENGTH_SHORT).show()
            return
        }
        container.playbackOrchestrator.onEvent(
            PlayerEvent.PlayTrack(
                trackId = selected.trackId,
                contextType = "playlist",
                contextId = playlistId,
                contextTracks = tracks,
                startIndex = startIndex
            )
        )
    }
}
