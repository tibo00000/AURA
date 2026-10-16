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
import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.repository.LocalLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Ecran de navigation dans les albums locaux pour Android Auto.
 */
class AlbumsBrowseScreen(
    carContext: CarContext,
    private val repository: LocalLibraryRepository
) : Screen(carContext) {

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isLoaded = false
    private var albumsList: List<AlbumBrowseRow> = emptyList()

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
                albumsList = repository.getAllBrowseAlbums()
            } catch (e: Exception) {
                android.util.Log.e("AlbumsBrowseScreen", "Erreur lors du chargement des albums", e)
            } finally {
                isLoaded = true
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (!isLoaded) {
            return ListTemplate.Builder()
                .setTitle("Albums")
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
            .setNoItemsMessage("Aucun album disponible.")

        albumsList.forEach { album ->
            val trackCountText = album.trackCount?.let { " • $it titres" } ?: ""
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(album.title)
                    .addText((album.artistName ?: "Artiste inconnu") + trackCountText)
                    .setOnClickListener {
                        screenManager.push(
                            AlbumTracksBrowseScreen(
                                carContext,
                                album.id,
                                album.title,
                                repository
                            )
                        )
                    }
                    .setImage(loadCoverIcon(album.coverUri))
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle("Mes Albums")
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
                    android.util.Log.e("AlbumsBrowseScreen", "Error decoding cover bitmap", e)
                }
            }
        }
        return CarIcon.Builder(
            IconCompat.createWithResource(
                carContext,
                android.R.drawable.ic_menu_gallery
            )
        ).build()
    }
}
