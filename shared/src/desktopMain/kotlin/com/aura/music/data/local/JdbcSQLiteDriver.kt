package com.aura.music.data.local

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.SQLiteException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * Implémentation universelle du driver SQLite pour JVM Desktop, basée sur org.xerial:sqlite-jdbc.
 *
 * Résout le problème critique de l'absence de binaire natif dans androidx.sqlite:sqlite-bundled
 * pour l'architecture Windows ARM64 (aarch64 / Snapdragon / Surface Copilot+), tout en garantissant
 * une compatibilité native et éprouvée sur x86, x64, ARM64 (Linux, macOS, Windows).
 *
 * Toutes les SQLException levées par le driver JDBC sont encapsulées dans androidx.sqlite.SQLiteException
 * afin que Room 3 (notamment EntityUpsertAdapter) intercepte correctement les violations de contraintes
 * d'unicité et bascule en UPDATE de manière transparente.
 */
class JdbcSQLiteDriver(private val urlPrefix: String = "jdbc:sqlite:") : SQLiteDriver {

    init {
        // Enregistrement explicite du driver JDBC SQLite
        Class.forName("org.sqlite.JDBC")
    }

    override fun open(fileName: String): SQLiteConnection {
        val url = if (fileName.isEmpty() || fileName == ":memory:") {
            "jdbc:sqlite::memory:"
        } else {
            "$urlPrefix$fileName"
        }
        return try {
            val connection = DriverManager.getConnection(url)
            JdbcSQLiteConnection(connection)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "Failed to open SQLite database at $url")
        }
    }
}

class JdbcSQLiteConnection(private val connection: Connection) : SQLiteConnection {
    private var inTx = false

    override fun inTransaction(): Boolean = inTx

    override fun prepare(sql: String): SQLiteStatement {
        return try {
            val statement = connection.prepareStatement(sql)
            JdbcSQLiteStatement(this, statement, sql)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "Failed to prepare statement: $sql")
        }
    }

    override fun close() {
        try {
            connection.close()
        } catch (_: SQLException) {}
    }

    internal fun updateTxState(sql: String) {
        val trimmed = sql.trim().uppercase()
        if (trimmed.startsWith("BEGIN") || trimmed.startsWith("SAVEPOINT")) {
            inTx = true
        } else if (trimmed.startsWith("COMMIT") || trimmed.startsWith("ROLLBACK") || trimmed.startsWith("END") || trimmed.startsWith("RELEASE")) {
            inTx = false
        }
    }
}

class JdbcSQLiteStatement(
    private val connection: JdbcSQLiteConnection,
    private val preparedStatement: PreparedStatement,
    private val sql: String
) : SQLiteStatement {

    private var hasExecuted = false
    private var resultSet: ResultSet? = null
    private var isClosed = false
    private var columnCountCached = -1
    private val columnNamesCache = mutableMapOf<Int, String>()
    private val columnTypesCache = mutableMapOf<Int, Int>()

    private fun cacheMetadata(rs: ResultSet) {
        try {
            val meta = rs.metaData ?: return
            columnCountCached = meta.columnCount
            for (i in 1..columnCountCached) {
                val idx = i - 1
                columnNamesCache[idx] = meta.getColumnLabel(i) ?: ""
                columnTypesCache[idx] = when (meta.getColumnType(i)) {
                    Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT, Types.BIT -> SQLITE_DATA_INTEGER
                    Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> SQLITE_DATA_FLOAT
                    Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> SQLITE_DATA_BLOB
                    Types.NULL -> SQLITE_DATA_NULL
                    else -> SQLITE_DATA_TEXT
                }
            }
        } catch (_: SQLException) {}
    }

    // Indexation des bindings : 1-based dans l'API SQLite et JDBC
    override fun bindBlob(index: Int, value: ByteArray) {
        try {
            preparedStatement.setBytes(index, value)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindBlob failed")
        }
    }

    override fun bindDouble(index: Int, value: Double) {
        try {
            preparedStatement.setDouble(index, value)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindDouble failed")
        }
    }

    override fun bindFloat(index: Int, value: Float) {
        try {
            preparedStatement.setFloat(index, value)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindFloat failed")
        }
    }

    override fun bindLong(index: Int, value: Long) {
        try {
            preparedStatement.setLong(index, value)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindLong failed")
        }
    }

    override fun bindInt(index: Int, value: Int) {
        try {
            preparedStatement.setInt(index, value)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindInt failed")
        }
    }

    override fun bindBoolean(index: Int, value: Boolean) {
        try {
            preparedStatement.setBoolean(index, value)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindBoolean failed")
        }
    }

    override fun bindText(index: Int, value: String) {
        try {
            preparedStatement.setString(index, value)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindText failed")
        }
    }

    override fun bindNull(index: Int) {
        try {
            preparedStatement.setNull(index, Types.NULL)
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "bindNull failed")
        }
    }

    override fun step(): Boolean {
        try {
            if (!hasExecuted) {
                hasExecuted = true
                connection.updateTxState(sql)
                val hasResultSet = preparedStatement.execute()
                if (hasResultSet) {
                    val rs = preparedStatement.resultSet
                    resultSet = rs
                    if (rs != null) {
                        cacheMetadata(rs)
                    }
                } else {
                    return false
                }
            }
            return resultSet?.next() ?: false
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "SQLite step error")
        }
    }

    // Indexation des colonnes : 0-based dans l'API SQLite, 1-based dans JDBC
    override fun getBlob(index: Int): ByteArray {
        val rs = resultSet ?: return ByteArray(0)
        return rs.getBytes(index + 1) ?: ByteArray(0)
    }

    override fun getDouble(index: Int): Double {
        val rs = resultSet ?: return 0.0
        return rs.getDouble(index + 1)
    }

    override fun getFloat(index: Int): Float {
        val rs = resultSet ?: return 0.0f
        return rs.getFloat(index + 1)
    }

    override fun getLong(index: Int): Long {
        val rs = resultSet ?: return 0L
        return rs.getLong(index + 1)
    }

    override fun getInt(index: Int): Int {
        val rs = resultSet ?: return 0
        return rs.getInt(index + 1)
    }

    override fun getBoolean(index: Int): Boolean {
        val rs = resultSet ?: return false
        return rs.getBoolean(index + 1)
    }

    override fun getText(index: Int): String {
        val rs = resultSet ?: return ""
        return rs.getString(index + 1) ?: ""
    }

    override fun isNull(index: Int): Boolean {
        val rs = resultSet ?: return true
        return try {
            rs.getObject(index + 1)
            rs.wasNull()
        } catch (_: SQLException) {
            true
        }
    }

    override fun getColumnCount(): Int {
        if (columnCountCached >= 0) return columnCountCached
        return try {
            preparedStatement.metaData?.columnCount ?: 0
        } catch (_: SQLException) {
            0
        }
    }

    override fun getColumnName(index: Int): String {
        columnNamesCache[index]?.let { return it }
        return try {
            preparedStatement.metaData?.getColumnLabel(index + 1) ?: ""
        } catch (_: SQLException) {
            ""
        }
    }

    override fun getColumnType(index: Int): Int {
        val rs = resultSet
        if (rs != null) {
            try {
                rs.getObject(index + 1)
                if (rs.wasNull()) {
                    return SQLITE_DATA_NULL
                }
            } catch (_: SQLException) {}
        }
        return columnTypesCache[index] ?: SQLITE_DATA_TEXT
    }

    override fun reset() {
        try {
            resultSet?.close()
        } catch (_: SQLException) {}
        resultSet = null
        hasExecuted = false
    }

    override fun clearBindings() {
        try {
            preparedStatement.clearParameters()
        } catch (_: SQLException) {}
    }

    override fun close() {
        if (!isClosed) {
            isClosed = true
            try {
                resultSet?.close()
            } catch (_: SQLException) {}
            resultSet = null
            try {
                preparedStatement.close()
            } catch (_: SQLException) {}
        }
    }
}
