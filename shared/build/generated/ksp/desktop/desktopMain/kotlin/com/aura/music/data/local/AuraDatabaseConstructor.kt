package com.aura.music.`data`.local

import androidx.room3.RoomDatabaseConstructor

public actual object AuraDatabaseConstructor : RoomDatabaseConstructor<AuraDatabase> {
  override fun initialize(): AuraDatabase = com.aura.music.`data`.local.AuraDatabase_Impl()
}
