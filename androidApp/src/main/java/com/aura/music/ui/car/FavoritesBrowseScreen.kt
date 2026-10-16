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
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.TrackSource
import com.aura.music.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ecran de navigation dans les titres favoris (likes) pour Android Auto.
 */
class FavoritesBrowseScreen(
    carContext: CarContext,
    private val repository: LocalLibraryRepository
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isLoaded = false
    private var tracksList: List<TrackListRow> = emptyList()

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
                tracksList = repository.getLikedTracks()
            } catch (e: Exception) {
                android.util.Log.e("FavoritesBrowseScreen", "Erreur lors du chargement des favoris", e)
            } finally {
                isLoaded = true
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (!isLoaded) {
            return ListTemplate.Builder()
                .setTitle("Titres Favoris")
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Aucun titre dans vos favoris.")

        tracksList.forEach { track ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(track.artistName ?: "Artiste inconnu")
                    .setOnClickListener {
                        playTrack(track.id)
                    }
                    .setImage(loadCoverIcon(track.coverUri))
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("Favoris")
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
                    android.util.Log.e("FavoritesBrowseScreen", "Error decoding cover bitmap", e)
                }
            }
        }
        return CarIcon.Builder(
            IconCompat.createWithResource(
                carContext,
                android.R.drawable.ic_media_play
            )
        ).build()
    }

    private fun playTrack(trackId: String) {
        val tracks = tracksList.map { track ->
            QueuedTrack(
                trackId = track.id,
                title = track.title,
                artistName = track.artistName ?: "Artiste inconnu",
                albumTitle = track.albumTitle,
                contentUri = track.contentUri,
                durationMs = track.durationMs,
                coverUri = track.coverUri,
                source = TrackSource.CONTEXT
            )
        }
        val startIndex = tracksList.indexOfFirst { it.id == trackId }.coerceAtLeast(0)

        val container = (carContext.applicationContext as com.aura.music.AuraApplication).container
        container.playbackOrchestrator.onEvent(
            PlayerEvent.PlayTrack(
                trackId = trackId,
                contextType = "favorites",
                contextId = "favorites",
                contextTracks = tracks,
                startIndex = startIndex
            )
        )
    }
}
