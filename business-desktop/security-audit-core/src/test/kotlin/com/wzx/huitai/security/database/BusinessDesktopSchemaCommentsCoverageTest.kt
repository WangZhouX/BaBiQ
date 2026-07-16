package com.wzx.huitai.security.database

import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BusinessDesktopSchemaCommentsCoverageTest {
    @Test
    fun `every business table and column has one Chinese comment without forbidden storage`() {
        BusinessDesktopDatabase(Files.createTempDirectory("business-comments").resolve("business.db")).use { database ->
            database.read { connection ->
                val expected = expectedObjects(connection)
                val comments = connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT object_type,object_name,column_name,comment_text FROM bd_schema_comments").use { result ->
                        buildMap {
                            while (result.next()) {
                                val key = Triple(result.getString(1), result.getString(2), result.getString(3))
                                val text = result.getString(4)
                                assertTrue(CHINESE.containsMatchIn(text), "comment must contain Chinese: $key")
                                put(key, text)
                            }
                        }
                    }
                }
                assertEquals(expected, comments.keys)
                val names = expected.flatMap { listOf(it.second, it.third) }.joinToString(" ").lowercase()
                listOf("token", "password", "full_file", "reasoning").forEach { assertFalse(it in names) }
            }
        }
    }

    private fun expectedObjects(connection: Connection): Set<Triple<String, String, String>> {
        val tables = connection.createStatement().use { statement -> statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'bd_%'").use { result -> buildList { while (result.next()) add(result.getString(1)) } } }
        return buildSet {
            tables.forEach { table ->
                add(Triple("TABLE", table, ""))
                connection.createStatement().use { statement -> statement.executeQuery("PRAGMA table_info($table)").use { result -> while (result.next()) add(Triple("COLUMN", table, result.getString("name"))) } }
            }
        }
    }

    companion object { private val CHINESE = Regex("[\\u4e00-\\u9fff]") }
}
