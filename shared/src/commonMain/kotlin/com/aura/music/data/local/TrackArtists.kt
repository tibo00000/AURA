package com.aura.music.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Entity(
    tableName = "track_artists",
    primaryKeys = ["track_id", "artist_id"],
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artist_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["artist_id"]), Index(value = ["track_id", "position"], unique = true)],
)
data class TrackArtistEntity(
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "artist_id") val artistId: String,
    val position: Int,
    val role: String,
    val origin: String,
)

@Dao
interface TrackArtistDao {
    @Query("SELECT * FROM tracks")
    suspend fun getTracksForTransition(): List<TrackEntity>
    @Query("SELECT * FROM track_artists ORDER BY track_id, position")
    suspend fun getAllCredits(): List<TrackArtistEntity>

    @Query("SELECT * FROM track_artists WHERE track_id = :trackId ORDER BY position")
    suspend fun getCredits(trackId: String): List<TrackArtistEntity>

    @Query("SELECT artists.* FROM artists JOIN track_artists ON track_artists.artist_id=artists.id WHERE track_artists.track_id=:trackId ORDER BY track_artists.position")
    suspend fun getArtists(trackId: String): List<ArtistEntity>

    @Query("DELETE FROM track_artists WHERE track_id = :trackId")
    suspend fun deleteCredits(trackId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCredits(credits: List<TrackArtistEntity>)

    @Query("SELECT * FROM artists")
    suspend fun getArtistIdentities(): List<ArtistEntity>
}

@Entity(tableName = "metadata_transition_state", primaryKeys = ["user_id", "track_id", "version"],
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["track_id"])])
data class MetadataTransitionStateEntity(
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val version: Int,
    val status: String,
    val attempts: Int = 0,
)

@Dao
interface MetadataTransitionDao {
    @Query("SELECT COUNT(*) FROM metadata_transition_runs WHERE user_id=:userId AND version=:version")
    suspend fun hasRun(userId: String, version: Int): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun beginRun(run: MetadataTransitionRunEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seed(rows: List<MetadataTransitionStateEntity>)
    @androidx.room3.Upsert
    suspend fun save(row: MetadataTransitionStateEntity)
    @Query("SELECT * FROM metadata_transition_state WHERE user_id=:userId AND version=:version AND status='pending'")
    suspend fun pending(userId: String, version: Int): List<MetadataTransitionStateEntity>
}

@Entity(tableName = "metadata_transition_runs", primaryKeys = ["user_id", "version"])
data class MetadataTransitionRunEntity(@ColumnInfo(name = "user_id") val userId: String, val version: Int)
