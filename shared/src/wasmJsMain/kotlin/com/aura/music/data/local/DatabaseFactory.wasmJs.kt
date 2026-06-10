package com.aura.music.data.local

import androidx.room3.Room
import androidx.room3.RoomDatabase

actual fun createDatabaseBuilder(context: Any?): RoomDatabase.Builder<AuraDatabase> {
    return Room.databaseBuilder<AuraDatabase>(
        name = "aura.db",
        factory = { AuraDatabaseConstructor.initialize() }
    )
}
