package com.aura.music.data.local

import android.content.Context
import androidx.room3.Room

actual fun createAuraDatabase(context: Any?): AuraDatabase {
    val appContext = context as? Context ?: throw IllegalArgumentException("Context must be provided on Android")
    val dbFile = appContext.getDatabasePath("aura.db")
    return Room.databaseBuilder<AuraDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
        factory = { AuraDatabaseConstructor.initialize() }
    )
    .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver())
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
