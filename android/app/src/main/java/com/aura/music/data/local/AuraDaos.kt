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
    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun getPlaylistCount(): Int

    @Query(
        """
        SELECT id, name, is_pinned, updated_at
        FROM playlists
        ORDER BY is_pinned DESC, updated_at DESC
        """,
    )
    suspend fun getPlaylists(): List<PlaylistListRow>
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
