package com.aura.music.data.metadata

import com.aura.music.data.local.AlbumEntity
import com.aura.music.data.local.ArtistEntity
import com.aura.music.data.local.AuraDatabase
import com.aura.music.domain.search.SearchNormalizer
import kotlinx.coroutines.CancellationException

/** Counts new parent rows; tracks, audio links, favorites, occurrences and outbox stay unchanged. */
data class MissingTrackParentsRepair(val artistsCreated: Int, val albumsCreated: Int) {
    val changed: Boolean get() = artistsCreated != 0 || albumsCreated != 0
}

/** Must run inside the caller's immediate transaction, before updating legacy tracks. */
suspend fun repairMissingTrackParents(
    database: AuraDatabase, now: Long, isCurrentSession: () -> Boolean,
): MissingTrackParentsRepair {
    fun requireCurrent() {
        if (!isCurrentSession()) throw CancellationException("Library session changed")
    }
    requireCurrent()
    val artistRows = database.trackDao().getTracksMissingArtistParent().groupBy { requireNotNull(it.primaryArtistId) }
        .map { (id, tracks) ->
            val name = tracks.firstOrNull { it.displayArtistName.isNotBlank() }?.displayArtistName ?: "Artiste inconnu"
            ArtistEntity(id, name, SearchNormalizer.normalize(name), createdAt = tracks.minOf { it.createdAt },
                updatedAt = maxOf(now, tracks.maxOf { it.updatedAt }))
        }
    requireCurrent()
    if (artistRows.isNotEmpty()) database.artistDao().insertArtistsIgnore(artistRows)
    // All non-null track artist references now exist, so a reconstructed album can reference them safely.
    val albumRows = database.trackDao().getTracksMissingAlbumParent().groupBy { requireNotNull(it.albumId) }
        .map { (id, tracks) ->
            val title = tracks.firstNotNullOfOrNull { it.displayAlbumTitle?.takeIf(String::isNotBlank) } ?: "Album inconnu"
            val artistId = tracks.mapNotNull { it.primaryArtistId }.distinct().singleOrNull()
            AlbumEntity(id, artistId, title, SearchNormalizer.normalize(title),
                coverUri = tracks.firstNotNullOfOrNull { it.coverUri?.takeIf(String::isNotBlank) },
                createdAt = tracks.minOf { it.createdAt }, updatedAt = maxOf(now, tracks.maxOf { it.updatedAt }))
        }
    requireCurrent()
    if (albumRows.isNotEmpty()) database.albumDao().insertAlbumsIgnore(albumRows)
    requireCurrent()
    return MissingTrackParentsRepair(artistRows.size, albumRows.size)
}
