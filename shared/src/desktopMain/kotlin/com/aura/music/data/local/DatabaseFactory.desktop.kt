package com.aura.music.data.local

import androidx.room3.Room
import androidx.sqlite.SQLiteDriver
import java.io.File

/**
 * Crée le driver SQLite adapté à l'architecture courante de la JVM Desktop.
 * Sur Windows ARM64 (aarch64 / Snapdragon), BundledSQLiteDriver échoue car Google ne fournit pas
 * de binaire natives/windows_arm64/sqliteJni.dll. On utilise alors JdbcSQLiteDriver (xerial).
 */
internal fun createDesktopSQLiteDriver(): SQLiteDriver {
    return JdbcSQLiteDriver()
}

actual fun createAuraDatabase(context: Any?): AuraDatabase {
    val dbFile = File(System.getProperty("user.home"), ".aura/aura.db")
    if (!dbFile.parentFile.exists()) {
        dbFile.parentFile.mkdirs()
    }
    return Room.databaseBuilder<AuraDatabase>(
        name = dbFile.absolutePath,
        factory = { AuraDatabaseConstructor.initialize() }
    )
    .setDriver(createDesktopSQLiteDriver())
    .addMigrations(
        AuraDatabase.MIGRATION_1_2,
        AuraDatabase.MIGRATION_2_3,
        AuraDatabase.MIGRATION_3_4,
        AuraDatabase.MIGRATION_4_5
    )
    .build()
}

@Volatile
private var INSTANCE: AuraDatabase? = null

actual fun getAuraDatabase(context: Any?): AuraDatabase {
    return INSTANCE ?: synchronized(AuraDatabase::class.java) {
        INSTANCE ?: createAuraDatabase(context).also { INSTANCE = it }
    }
}
