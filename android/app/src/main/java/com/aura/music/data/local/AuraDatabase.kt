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
        DownloadJobEntity::class,
        PlaybackSnapshotEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        RecentSearchEntity::class,
        SyncOutboxEntity::class,
        TrackEntity::class,
        TrackLikeEntity::class,
        TrackMediaLinkEntity::class,
        UserSettingsEntity::class,
    ],
    version = 4,
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
    abstract fun downloadJobDao(): DownloadJobDao
    abstract fun syncOutboxDao(): SyncOutboxDao

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

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_jobs` (
                        `id` TEXT NOT NULL,
                        `track_id` TEXT NOT NULL,
                        `provider_name` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `progress_percent` REAL,
                        `error_code` TEXT,
                        `error_message` TEXT,
                        `attempt_count` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `archived_in_cloud_at` INTEGER,
                        `purge_after_at` INTEGER,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`track_id`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_download_jobs_track_id` ON `download_jobs` (`track_id`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_download_jobs_status` ON `download_jobs` (`status`)")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create sync_outbox table
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_outbox` (
                        `id` TEXT NOT NULL,
                        `entity_type` TEXT NOT NULL,
                        `entity_id` TEXT NOT NULL,
                        `operation_type` TEXT NOT NULL,
                        `payload_json` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `attempt_count` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_status` ON `sync_outbox` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_created_at` ON `sync_outbox` (`created_at`)")

                // 2. Add sync_token column to user_settings table
                database.execSQL("ALTER TABLE `user_settings` ADD COLUMN `sync_token` TEXT")
            }
        }

        fun getInstance(context: Context): AuraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context = context,
                    klass = AuraDatabase::class.java,
                    name = "aura.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
