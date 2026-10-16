package com.aura.music.data.local

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement

private class WasmInMemoryDriver : SQLiteDriver {
    override val hasConnectionPool: Boolean get() = false
    override suspend fun openAsync(fileName: String): SQLiteConnection = WasmInMemoryConnection()
}

private class WasmInMemoryConnection : SQLiteConnection {
    override fun inTransaction(): Boolean = false
    override suspend fun prepareAsync(sql: String): SQLiteStatement = WasmInMemoryStatement()
    override fun close() {}
}

private class WasmInMemoryStatement : SQLiteStatement {
    override fun bindBlob(index: Int, value: ByteArray) {}
    override fun bindDouble(index: Int, value: Double) {}
    override fun bindFloat(index: Int, value: Float) {}
    override fun bindLong(index: Int, value: Long) {}
    override fun bindText(index: Int, value: String) {}
    override fun bindNull(index: Int) {}
    override suspend fun stepAsync(): Boolean = false
    override fun getBlob(index: Int): ByteArray = ByteArray(0)
    override fun getDouble(index: Int): Double = 0.0
    override fun getFloat(index: Int): Float = 0.0f
    override fun getLong(index: Int): Long = 0L
    override fun getText(index: Int): String = ""
    override fun isNull(index: Int): Boolean = true
    override fun getColumnCount(): Int = 0
    override fun getColumnName(index: Int): String = ""
    override fun getColumnType(index: Int): Int = 0
    override fun reset() {}
    override fun clearBindings() {}
    override fun close() {}
}

private var INSTANCE: AuraDatabase? = null

actual fun getAuraDatabase(context: Any?): AuraDatabase {
    return INSTANCE ?: createAuraDatabase(context).also { INSTANCE = it }
}

actual fun createAuraDatabase(context: Any?): AuraDatabase {
    return Room.inMemoryDatabaseBuilder<AuraDatabase>(
        factory = { AuraDatabaseConstructor.initialize() }
    )
    .setDriver(WasmInMemoryDriver())
    .build()
}

