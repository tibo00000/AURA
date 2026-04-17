package com.aura.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlbumEntity::class,
        ArtistEntity::class,
        PlaybackSnapshotEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        RecentSearchEntity::class,
        TrackEntity::class,
        TrackLikeEntity::class,
        TrackMediaLinkEntity::class,
        UserSettingsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
    abstract fun trackLikeDao(): TrackLikeDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playbackSnapshotDao(): PlaybackSnapshotDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        @Volatile
        private var instance: AuraDatabase? = null

        fun getInstance(context: Context): AuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context = context,
                    klass = AuraDatabase::class.java,
                    name = "aura.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
