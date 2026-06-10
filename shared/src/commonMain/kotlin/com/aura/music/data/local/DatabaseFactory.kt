package com.aura.music.data.local

import androidx.room.RoomDatabase

expect fun createDatabaseBuilder(context: Any? = null): RoomDatabase.Builder<AuraDatabase>
