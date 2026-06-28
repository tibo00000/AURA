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
import com.aura.music.data.local.PlaylistListRow
import com.aura.music.data.repository.LocalLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ecran de navigation dans les playlists locales pour Android Auto.
 */
class PlaylistsBrowseScreen(
    carContext: CarContext,
    private val repository: LocalLibraryRepository
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isLoaded = false
    private var playlistsList: List<PlaylistListRow> = emptyList()

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
                playlistsList = repository.getPlaylists()
            } catch (e: Exception) {
                android.util.Log.e("PlaylistsBrowseScreen", "Erreur lors du chargement des playlists", e)
            } finally {
                isLoaded = true
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (!isLoaded) {
            return ListTemplate.Builder()
                .setTitle("Playlists")
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Aucune playlist créée.")

        playlistsList.forEach { playlist ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(playlist.name)
                    .addText("Playlist locale")
                    .setOnClickListener {
                        screenManager.push(PlaylistTracksBrowseScreen(carContext, playlist.id, playlist.name, repository))
                    }
                    .setImage(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext,
                                android.R.drawable.ic_menu_slideshow
                            )
                        ).build()
                    )
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("Mes Playlists")
            .setSingleList(listBuilder.build())
            .setHeaderAction(Action.BACK)
            .build()
    }
}
