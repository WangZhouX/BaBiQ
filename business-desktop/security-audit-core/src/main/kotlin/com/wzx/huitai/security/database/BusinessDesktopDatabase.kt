package com.wzx.huitai.security.database

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.flywaydb.core.Flyway

class BusinessDesktopDatabase(
    path: Path,
    private val connectionFactory: (String) -> Connection = DriverManager::getConnection,
    private val connectionInitializer: (Connection) -> Unit = ::configureConnection,
) : AutoCloseable {
    private val databasePath = path.toAbsolutePath().normalize()
    private val jdbcUrl = "jdbc:sqlite:$databasePath"

    init {
        require(databasePath.fileName != null) { "database path must identify a file" }
        Files.createDirectories(databasePath.parent)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA journal_mode = WAL").use { result ->
                    check(result.next() && result.getString(1).equals("wal", ignoreCase = true)) {
                        "SQLite WAL mode is required"
                    }
                }
            }
        }
        Flyway.configure()
            .dataSource(jdbcUrl, null, null)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun <T> read(block: (Connection) -> T): T = connection().use(block)

    fun <T> write(block: (Connection) -> T): T = connection().use { connection ->
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            block(connection).also {
                connection.createStatement().use { statement -> statement.execute("COMMIT") }
            }
        } catch (failure: Throwable) {
            runCatching { connection.createStatement().use { statement -> statement.execute("ROLLBACK") } }
            throw failure
        }
    }

    internal fun connection(): Connection {
        val connection = connectionFactory(jdbcUrl)
        try {
            connectionInitializer(connection)
            return connection
        } catch (failure: Throwable) {
            try {
                connection.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override fun close() = Unit

    companion object {
        private fun configureConnection(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
        }
    }
}
