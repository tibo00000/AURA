package com.aura.music.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ArtistDao {
    @Upsert
    suspend fun upsertArtists(items: List<ArtistEntity>)

    @Query(
        """
        SELECT
            artists.id AS id,
            artists.name AS name,
            artists.picture_uri AS picture_uri,
            COUNT(DISTINCT tracks.id) AS track_count,
            COUNT(DISTINCT albums.id) AS album_count
        FROM artists
        LEFT JOIN tracks ON tracks.primary_artist_id = artists.id
        LEFT JOIN albums ON albums.primary_artist_id = artists.id
        GROUP BY artists.id
        ORDER BY COUNT(DISTINCT tracks.id) DESC, artists.name ASC
        LIMIT :limit
        """,
    )
    suspend fun getBrowseArtists(limit: Int): List<ArtistBrowseRow>

    @Query(
        """
        SELECT
            artists.id AS id,
            artists.name AS name,
            artists.picture_uri AS picture_uri,
            COUNT(DISTINCT tracks.id) AS track_count,
            COUNT(DISTINCT albums.id) AS album_count
        FROM artists
        LEFT JOIN tracks ON tracks.primary_artist_id = artists.id
        LEFT JOIN albums ON albums.primary_artist_id = artists.id
        WHERE lower(artists.name) LIKE '%' || lower(:query) || '%'
        GROUP BY artists.id
        ORDER BY COUNT(DISTINCT tracks.id) DESC, artists.name ASC
        LIMIT :limit
        """,
    )
    suspend fun searchArtists(query: String, limit: Int): List<ArtistBrowseRow>

    @Query(
        """
        SELECT
            artists.id AS id,
            artists.name AS name,
            artists.picture_uri AS picture_uri,
            artists.summary AS summary
        FROM artists
        WHERE artists.id = :artistId
        LIMIT 1
        """,
    )
    suspend fun getArtistDetail(artistId: String): ArtistDetailRow?
}

@Dao
interface AlbumDao {
    @Upsert
    suspend fun upsertAlbums(items: List<AlbumEntity>)

    @Query(
        """
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.primary_artist_id AS artist_id,
            artists.name AS artist_name,
            albums.cover_uri AS cover_uri,
            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        FROM albums
        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        LEFT JOIN tracks ON tracks.album_id = albums.id
        GROUP BY albums.id
        ORDER BY albums.updated_at DESC, albums.title ASC
        LIMIT :limit
        """,
    )
    suspend fun getBrowseAlbums(limit: Int): List<AlbumBrowseRow>

    @Query(
        """
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.primary_artist_id AS artist_id,
            artists.name AS artist_name,
            albums.cover_uri AS cover_uri,
            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        FROM albums
        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        LEFT JOIN tracks ON tracks.album_id = albums.id
        WHERE lower(albums.title) LIKE '%' || lower(:query) || '%'
           OR lower(artists.name) LIKE '%' || lower(:query) || '%'
        GROUP BY albums.id
        ORDER BY albums.updated_at DESC, albums.title ASC
        LIMIT :limit
        """,
    )
    suspend fun searchAlbums(query: String, limit: Int): List<AlbumBrowseRow>

    @Query(
        """
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.primary_artist_id AS artist_id,
            artists.name AS artist_name,
            albums.cover_uri AS cover_uri,
            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        FROM albums
        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        LEFT JOIN tracks ON tracks.album_id = albums.id
        WHERE albums.primary_artist_id = :artistId
        GROUP BY albums.id
        ORDER BY albums.updated_at DESC, albums.title ASC
        LIMIT :limit
        """,
    )
    suspend fun getAlbumsForArtist(artistId: String, limit: Int): List<AlbumBrowseRow>

    @Query(
        """
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.primary_artist_id AS artist_id,
            artists.name AS artist_name,
            albums.cover_uri AS cover_uri,
            albums.release_date AS release_date,
            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        FROM albums
        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        LEFT JOIN tracks ON tracks.album_id = albums.id
        WHERE albums.id = :albumId
        GROUP BY albums.id
        LIMIT 1
        """,
    )
    suspend fun getAlbumDetail(albumId: String): AlbumDetailRow?
    @Query(
        """
        SELECT
            albums.id AS id,
            albums.title AS title,
            albums.primary_artist_id AS artist_id,
            artists.name AS artist_name,
            albums.cover_uri AS cover_uri,
            COALESCE(albums.track_count, COUNT(tracks.id)) AS track_count
        FROM albums
        LEFT JOIN artists ON artists.id = albums.primary_artist_id
        LEFT JOIN tracks ON tracks.album_id = albums.id
        WHERE lower(albums.title) = lower(:title) AND lower(artists.name) = lower(:artistName)
        GROUP BY albums.id
        LIMIT 1
        """
    )
    suspend fun getAlbumByTitleAndArtist(title: String, artistName: String): AlbumBrowseRow?
}

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertTracks(items: List<TrackEntity>)

    @Upsert
    suspend fun upsertTrackMediaLinks(items: List<TrackMediaLinkEntity>)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        ORDER BY tracks.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentTracks(limit: Int): List<TrackListRow>

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        WHERE tracks.id = :trackId
        LIMIT 1
        """,
    )
    suspend fun getTrackById(trackId: String): TrackListRow?

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        WHERE lower(tracks.title) LIKE '%' || lower(:query) || '%'
           OR lower(tracks.display_artist_name) LIKE '%' || lower(:query) || '%'
           OR lower(COALESCE(tracks.display_album_title, '')) LIKE '%' || lower(:query) || '%'
        ORDER BY tracks.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchTracks(query: String, limit: Int): List<TrackListRow>

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        ORDER BY lower(tracks.display_artist_name) ASC, lower(tracks.title) ASC
        """,
    )
    suspend fun getAllTracks(): List<TrackListRow>

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        WHERE tracks.primary_artist_id = :artistId
        ORDER BY tracks.updated_at DESC, tracks.title ASC
        LIMIT :limit
        """,
    )
    suspend fun getTracksForArtist(artistId: String, limit: Int): List<TrackListRow>

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        WHERE tracks.album_id = :albumId
        ORDER BY tracks.title ASC, tracks.updated_at DESC
        """,
    )
    suspend fun getTracksForAlbum(albumId: String): List<TrackListRow>

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        WHERE lower(tracks.display_album_title) = lower(:albumTitle)
          AND lower(tracks.display_artist_name) = lower(:artistName)
        ORDER BY tracks.title ASC, tracks.updated_at DESC
        """
    )
    suspend fun getTracksForAlbumByText(albumTitle: String, artistName: String): List<TrackListRow>

    @Query(
        """
        SELECT
            tracks.id AS id,
            tracks.primary_artist_id AS artist_id,
            tracks.album_id AS album_id,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri,
            tracks.is_liked AS is_liked
        FROM tracks
        INNER JOIN track_likes ON track_likes.track_id = tracks.id
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        ORDER BY track_likes.liked_at DESC
        """,
    )
    suspend fun getLikedTracks(): List<TrackListRow>
}

@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(entity: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylistItem(entity: PlaylistItemEntity)

    @Query("UPDATE playlists SET name = :name, updated_at = :updatedAt WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: String, name: String, updatedAt: Long): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String): Int

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCount(): Int

    @Query(
        """
        SELECT
            playlists.id AS id,
            playlists.name AS name,
            playlists.is_pinned AS is_pinned,
            COUNT(playlist_items.id) AS item_count,
            playlists.updated_at AS updated_at
        FROM playlists
        LEFT JOIN playlist_items ON playlist_items.playlist_id = playlists.id
        GROUP BY playlists.id
        ORDER BY is_pinned DESC, updated_at DESC
        """,
    )
    suspend fun getPlaylists(): List<PlaylistListRow>

    @Query(
        """
        SELECT
            playlists.id AS id,
            playlists.name AS name,
            playlists.cover_uri AS cover_uri,
            playlists.is_pinned AS is_pinned,
            COUNT(playlist_items.id) AS item_count,
            playlists.updated_at AS updated_at
        FROM playlists
        LEFT JOIN playlist_items ON playlist_items.playlist_id = playlists.id
        WHERE playlists.id = :playlistId
        GROUP BY playlists.id
        LIMIT 1
        """,
    )
    suspend fun getPlaylistDetail(playlistId: String): PlaylistDetailRow?

    @Query(
        """
        SELECT
            playlist_items.id AS playlist_item_id,
            playlist_items.playlist_id AS playlist_id,
            playlist_items.track_id AS track_id,
            playlist_items.position AS position,
            playlist_items.added_at AS added_at,
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms,
            tracks.cover_uri AS cover_uri
        FROM playlist_items
        INNER JOIN tracks ON tracks.id = playlist_items.track_id
        LEFT JOIN track_media_links ON track_media_links.track_id = tracks.id
        WHERE playlist_items.playlist_id = :playlistId
        ORDER BY playlist_items.position ASC
        """,
    )
    suspend fun getPlaylistTracks(playlistId: String): List<PlaylistTrackRow>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun getNextPlaylistPosition(playlistId: String): Int

    @Query("DELETE FROM playlist_items WHERE id = :playlistItemId")
    suspend fun deletePlaylistItem(playlistItemId: String): Int

    @Query("UPDATE playlist_items SET position = :position WHERE id = :playlistItemId")
    suspend fun updatePlaylistItemPosition(playlistItemId: String, position: Int): Int

    @Query("UPDATE playlists SET updated_at = :updatedAt WHERE id = :playlistId")
    suspend fun touchPlaylist(playlistId: String, updatedAt: Long): Int
}

@Dao
interface PlaybackSnapshotDao {
    @Query("SELECT * FROM playback_snapshots WHERE id = 'active' LIMIT 1")
    suspend fun getActiveSnapshot(): PlaybackSnapshotEntity?

    @Upsert
    suspend fun upsert(snapshot: PlaybackSnapshotEntity)
}

@Dao
interface RecentSearchDao {
    @Upsert
    suspend fun upsert(entity: RecentSearchEntity)

    @Query("SELECT query FROM recent_searches ORDER BY searched_at DESC LIMIT :limit")
    suspend fun getRecentQueries(limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM recent_searches")
    suspend fun getRecentSearchCount(): Int

    @Query(
        """
        DELETE FROM recent_searches
        WHERE id NOT IN (
            SELECT id FROM recent_searches
            ORDER BY searched_at DESC
            LIMIT :limit
        )
        """,
    )
    suspend fun trimTo(limit: Int)
}

@Dao
interface TrackLikeDao {
    /**
     * Insere ou remplace une ligne dans track_likes.
     * Gouverne par : docs/android/room-schema.md — table track_likes.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLike(entity: TrackLikeEntity)

    /**
     * Supprime la ligne de like pour la piste donnee.
     */
    @Query("DELETE FROM track_likes WHERE track_id = :trackId")
    suspend fun deleteLike(trackId: String)

    /**
     * Met a jour le booleen denormalise dans tracks.
     * Gouverne par : docs/android/room-schema.md — regle : tracks.is_liked doit refleter
     * l'existence ou non d'une ligne dans track_likes.
     */
    @Query("UPDATE tracks SET is_liked = :liked, updated_at = :updatedAt WHERE id = :trackId")
    suspend fun setTrackIsLiked(trackId: String, liked: Boolean, updatedAt: Long)
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 'default' LIMIT 1")
    suspend fun getSettings(): UserSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: UserSettingsEntity)

    @Query("UPDATE user_settings SET sync_enabled = :enabled WHERE id = 'default'")
    suspend fun updateSyncEnabled(enabled: Boolean): Int

    @Query("UPDATE user_settings SET online_search_enabled = :enabled WHERE id = 'default'")
    suspend fun updateOnlineSearchEnabled(enabled: Boolean): Int

    @Query("UPDATE user_settings SET online_search_network_policy = :policy WHERE id = 'default'")
    suspend fun updateOnlineSearchNetworkPolicy(policy: String): Int

    @Query("UPDATE user_settings SET stats_sync_network_policy = :policy WHERE id = 'default'")
    suspend fun updateStatsSyncNetworkPolicy(policy: String): Int
}
