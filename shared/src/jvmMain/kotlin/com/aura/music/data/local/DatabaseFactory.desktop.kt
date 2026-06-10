package com.aura.music.data.local

import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

actual fun createDatabaseBuilder(context: Any?): RoomDatabase.Builder<AuraDatabase> {
    val dbFile = File(System.getProperty("user.home"), ".aura/aura.db")
    if (!dbFile.parentFile.exists()) {
        dbFile.parentFile.mkdirs()
    }
    return Room.databaseBuilder<AuraDatabase>(
        name = dbFile.absolutePath,
        factory = { AuraDatabaseConstructor.initialize() }
    )
}
