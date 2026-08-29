package com.aura.music.domain.search

import com.aura.music.data.local.AlbumBrowseRow
import com.aura.music.data.local.ArtistBrowseRow
import com.aura.music.data.local.TrackListRow

/**
 * Représentation interne optimisée d'une piste précalculée pour la recherche.
 * Note : Ne contient aucun état mutable dynamique (isLiked/isDownloaded) pour éviter
 * d'invalider le cache textuel lors d'un simple like.
 */
class IndexedTrack(
    val id: String,
    val track: TrackListRow,
    val normalizedTitle: String,
    val titleTokens: Array<String>,
    val normalizedArtist: String,
    val artistTokens: Array<String>,
    val normalizedAlbum: String,
    val albumTokens: Array<String>,
    val allTokens: Array<String>
)

class IndexedArtist(
    val id: String,
    val artist: ArtistBrowseRow,
    val normalizedName: String,
    val nameTokens: Array<String>
)

class IndexedAlbum(
    val id: String,
    val album: AlbumBrowseRow,
    val normalizedTitle: String,
    val titleTokens: Array<String>,
    val normalizedArtistName: String,
    val artistTokens: Array<String>,
    val allTokens: Array<String>
)

/**
 * Index inversé en mémoire immuable et thread-safe pour la recherche Zero-Jank.
 */
class LocalSearchIndex(
    val tracks: Array<IndexedTrack>,
    val artists: Array<IndexedArtist>,
    val albums: Array<IndexedAlbum>,
    val trackPrefixIndex: Map<String, IntArray>,
    val trackVocabulary: Array<String>,
    val artistPrefixIndex: Map<String, IntArray>,
    val artistVocabulary: Array<String>,
    val albumPrefixIndex: Map<String, IntArray>,
    val albumVocabulary: Array<String>
) {
    companion object {
        private const val MAX_PREFIX_CANDIDATES = 300

        /**
         * Construit l'index inversé et précalcule les documents normalisés.
         */
        fun build(
            tracks: List<TrackListRow>,
            artists: List<ArtistBrowseRow>,
            albums: List<AlbumBrowseRow>
        ): LocalSearchIndex {
            // 1. Indexation des Titres
            val indexedTracks = tracks.mapIndexed { idx, track ->
                val normTitle = SearchNormalizer.normalize(track.title)
                val titleTokens = SearchNormalizer.tokenize(track.title).toTypedArray()
                val normArtist = SearchNormalizer.normalize(track.artistName)
                val artistTokens = SearchNormalizer.tokenize(track.artistName).toTypedArray()
                val normAlbum = SearchNormalizer.normalize(track.albumTitle)
                val albumTokens = SearchNormalizer.tokenize(track.albumTitle).toTypedArray()

                val allTokens = (titleTokens.toList() + artistTokens.toList() + albumTokens.toList())
                    .distinct()
                    .toTypedArray()

                IndexedTrack(
                    id = track.id,
                    track = track,
                    normalizedTitle = normTitle,
                    titleTokens = titleTokens,
                    normalizedArtist = normArtist,
                    artistTokens = artistTokens,
                    normalizedAlbum = normAlbum,
                    albumTokens = albumTokens,
                    allTokens = allTokens
                )
            }.toTypedArray()

            val trackPrefixMap = mutableMapOf<String, MutableList<Int>>()
            val trackVocabSet = mutableSetOf<String>()

            indexedTracks.forEachIndexed { docIdx, doc ->
                for (token in doc.allTokens) {
                    trackVocabSet.add(token)
                    // Indexation du token exact et de ses préfixes (de 2 à 4 caractères)
                    val prefixes = generatePrefixes(token)
                    for (prefix in prefixes) {
                        val list = trackPrefixMap.getOrPut(prefix) { mutableListOf() }
                        if (list.size < MAX_PREFIX_CANDIDATES && (list.isEmpty() || list.last() != docIdx)) {
                            list.add(docIdx)
                        }
                    }
                }
            }

            val finalTrackPrefixIndex = trackPrefixMap.mapValues { it.value.toIntArray() }

            // 2. Indexation des Artistes
            val indexedArtists = artists.map { artist ->
                val normName = SearchNormalizer.normalize(artist.name)
                val nameTokens = SearchNormalizer.tokenize(artist.name).toTypedArray()
                IndexedArtist(
                    id = artist.id,
                    artist = artist,
                    normalizedName = normName,
                    nameTokens = nameTokens
                )
            }.toTypedArray()

            val artistPrefixMap = mutableMapOf<String, MutableList<Int>>()
            val artistVocabSet = mutableSetOf<String>()

            indexedArtists.forEachIndexed { docIdx, doc ->
                for (token in doc.nameTokens) {
                    artistVocabSet.add(token)
                    val prefixes = generatePrefixes(token)
                    for (prefix in prefixes) {
                        val list = artistPrefixMap.getOrPut(prefix) { mutableListOf() }
                        if (list.size < MAX_PREFIX_CANDIDATES && (list.isEmpty() || list.last() != docIdx)) {
                            list.add(docIdx)
                        }
                    }
                }
            }

            val finalArtistPrefixIndex = artistPrefixMap.mapValues { it.value.toIntArray() }

            // 3. Indexation des Albums
            val indexedAlbums = albums.map { album ->
                val normTitle = SearchNormalizer.normalize(album.title)
                val titleTokens = SearchNormalizer.tokenize(album.title).toTypedArray()
                val normArtist = SearchNormalizer.normalize(album.artistName)
                val artistTokens = SearchNormalizer.tokenize(album.artistName).toTypedArray()
                val allTokens = (titleTokens.toList() + artistTokens.toList()).distinct().toTypedArray()

                IndexedAlbum(
                    id = album.id,
                    album = album,
                    normalizedTitle = normTitle,
                    titleTokens = titleTokens,
                    normalizedArtistName = normArtist,
                    artistTokens = artistTokens,
                    allTokens = allTokens
                )
            }.toTypedArray()

            val albumPrefixMap = mutableMapOf<String, MutableList<Int>>()
            val albumVocabSet = mutableSetOf<String>()

            indexedAlbums.forEachIndexed { docIdx, doc ->
                for (token in doc.allTokens) {
                    albumVocabSet.add(token)
                    val prefixes = generatePrefixes(token)
                    for (prefix in prefixes) {
                        val list = albumPrefixMap.getOrPut(prefix) { mutableListOf() }
                        if (list.size < MAX_PREFIX_CANDIDATES && (list.isEmpty() || list.last() != docIdx)) {
                            list.add(docIdx)
                        }
                    }
                }
            }

            val finalAlbumPrefixIndex = albumPrefixMap.mapValues { it.value.toIntArray() }

            return LocalSearchIndex(
                tracks = indexedTracks,
                artists = indexedArtists,
                albums = indexedAlbums,
                trackPrefixIndex = finalTrackPrefixIndex,
                trackVocabulary = trackVocabSet.toTypedArray(),
                artistPrefixIndex = finalArtistPrefixIndex,
                artistVocabulary = artistVocabSet.toTypedArray(),
                albumPrefixIndex = finalAlbumPrefixIndex,
                albumVocabulary = albumVocabSet.toTypedArray()
            )
        }

        private fun generatePrefixes(token: String): Set<String> {
            val prefixes = mutableSetOf<String>()
            prefixes.add(token) // Token exact

            // Préfixes de 2 à 4 caractères
            for (len in 2..minOf(4, token.length)) {
                prefixes.add(token.substring(0, len))
            }
            return prefixes
        }
    }
}
