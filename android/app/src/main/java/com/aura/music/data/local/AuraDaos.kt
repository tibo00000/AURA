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
}

@Dao
interface AlbumDao {
    @Upsert
    suspend fun upsertAlbums(items: List<AlbumEntity>)
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
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms
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
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms
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
            tracks.title AS title,
            tracks.display_artist_name AS artist_name,
            tracks.display_album_title AS album_title,
            track_media_links.content_uri AS content_uri,
            tracks.duration_ms AS duration_ms
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
            tracks.duration_ms AS duration_ms
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
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 'default' LIMIT 1")
    suspend fun getSettings(): UserSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(entity: UserSettingsEntity)
}
