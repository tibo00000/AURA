package com.aura.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlbumEntity::class,
        AlbumSourceLinkEntity::class,
        ArtistEntity::class,
        ArtistSourceLinkEntity::class,
        PlaybackSnapshotEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        RecentSearchEntity::class,
        TrackEntity::class,
        TrackLikeEntity::class,
        TrackMediaLinkEntity::class,
        UserSettingsEntity::class,
    ],
    version = 2,
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
    abstract fun artistSourceLinkDao(): ArtistSourceLinkDao
    abstract fun albumSourceLinkDao(): AlbumSourceLinkDao

    companion object {
        @Volatile
        private var instance: AuraDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add new columns
                database.execSQL("ALTER TABLE artists ADD COLUMN artwork_origin TEXT")
                database.execSQL("ALTER TABLE artists ADD COLUMN artwork_last_resolved_at INTEGER")
                database.execSQL("ALTER TABLE albums ADD COLUMN artwork_origin TEXT")
                database.execSQL("ALTER TABLE albums ADD COLUMN artwork_last_resolved_at INTEGER")

                // Create new tables
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `artist_source_links` (
                        `id` TEXT NOT NULL,
                        `artist_id` TEXT NOT NULL,
                        `usage_type` TEXT NOT NULL,
                        `provider_name` TEXT NOT NULL,
                        `provider_artist_id` TEXT NOT NULL,
                        `match_score` REAL,
                        `is_active_for_usage` INTEGER NOT NULL,
                        `metadata_json` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`artist_id`) REFERENCES `artists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_artist_id` ON `artist_source_links` (`artist_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_usage_type` ON `artist_source_links` (`usage_type`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_source_links_provider_name` ON `artist_source_links` (`provider_name`)")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_source_links` (
                        `id` TEXT NOT NULL,
                        `album_id` TEXT NOT NULL,
                        `usage_type` TEXT NOT NULL,
                        `provider_name` TEXT NOT NULL,
                        `provider_album_id` TEXT NOT NULL,
                        `provider_artist_id` TEXT,
                        `match_score` REAL,
                        `is_active_for_usage` INTEGER NOT NULL,
                        `metadata_json` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`album_id`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_album_id` ON `album_source_links` (`album_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_usage_type` ON `album_source_links` (`usage_type`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_album_source_links_provider_name` ON `album_source_links` (`provider_name`)")
            }
        }

        fun getInstance(context: Context): AuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context = context,
                    klass = AuraDatabase::class.java,
                    name = "aura.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
