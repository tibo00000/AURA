package com.aura.music.ui.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.aura.music.data.repository.LocalLibraryRepository

/**
 * Ecran d'accueil (Hub) principal d'AURA sur Android Auto.
 * Offre une navigation en grille (Option A) vers Favoris, Téléchargements, Playlists et Albums.
 */
class AuraCarHubScreen(
    carContext: CarContext,
    private val repository: LocalLibraryRepository
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // 1. Favoris Grid Item
        listBuilder.addItem(
            GridItem.Builder()
                .setTitle("Favoris")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(
                            carContext,
                            android.R.drawable.btn_star_big_on
                        )
                    ).build(),
                    GridItem.IMAGE_TYPE_ICON
                )
                .setOnClickListener {
                    screenManager.push(FavoritesBrowseScreen(carContext, repository))
                }
                .build()
        )

        // 2. Téléchargements (Offline) Grid Item
        listBuilder.addItem(
            GridItem.Builder()
                .setTitle("Téléchargements")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(
                            carContext,
                            android.R.drawable.ic_menu_save
                        )
                    ).build(),
                    GridItem.IMAGE_TYPE_ICON
                )
                .setOnClickListener {
                    screenManager.push(LibraryBrowseScreen(carContext, repository))
                }
                .build()
        )

        // 3. Playlists Grid Item
        listBuilder.addItem(
            GridItem.Builder()
                .setTitle("Playlists")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(
                            carContext,
                            android.R.drawable.ic_menu_recent_history
                        )
                    ).build(),
                    GridItem.IMAGE_TYPE_ICON
                )
                .setOnClickListener {
                    screenManager.push(PlaylistsBrowseScreen(carContext, repository))
                }
                .build()
        )

        // 4. Albums Grid Item
        listBuilder.addItem(
            GridItem.Builder()
                .setTitle("Albums")
                .setImage(
                    CarIcon.Builder(
                        IconCompat.createWithResource(
                            carContext,
                            android.R.drawable.ic_menu_gallery
                        )
                    ).build(),
                    GridItem.IMAGE_TYPE_ICON
                )
                .setOnClickListener {
                    screenManager.push(AlbumsBrowseScreen(carContext, repository))
                }
                .build()
        )

        return GridTemplate.Builder()
            .setTitle("AURA Music")
            .setSingleList(listBuilder.build())
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
