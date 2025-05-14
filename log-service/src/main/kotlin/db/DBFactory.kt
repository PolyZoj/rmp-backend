package ru.polyZoj.db

import java.sql.Timestamp
import javax.sql.DataSource

object DBFactory {
    private lateinit var dataSource: DataSource

    fun init(cfg: DataSourceConfig) {
        dataSource = cfg.dataSource

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS logs
                    (
                        timestamp     DateTime64(3),
                        service_name  String,
                        level         String,
                        message       String,
                        context       String
                    )
                    ENGINE = MergeTree
                    ORDER BY (timestamp, service_name)
                    """.trimIndent()
                )
            }
        }
    }

    fun insertLog(timestamp: Timestamp, serviceName: String, level: String, message: String, context: String?) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO logs (timestamp, service_name, level, message, context)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, timestamp)
                stmt.setString(2, serviceName)
                stmt.setString(3, level)
                stmt.setString(4, message)
                stmt.setString(5, context ?: "")
                stmt.executeUpdate()
            }
        }
    }
}
