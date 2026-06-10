package com.aura.music.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

actual fun createDatabaseBuilder(context: Any?): RoomDatabase.Builder<AuraDatabase> {
    val appContext = context as? Context ?: throw IllegalArgumentException("Context must be provided on Android")
    val dbFile = appContext.getDatabasePath("aura.db")
    return Room.databaseBuilder<AuraDatabase>(
        context = appContext,
        name = dbFile.absolutePath,
        factory = { AuraDatabaseConstructor.initialize() }
    )
}
