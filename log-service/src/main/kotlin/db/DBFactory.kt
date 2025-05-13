package ru.polyZoj.db

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

    fun insertLog(serviceName: String, level: String, message: String, context: String?) {
        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO logs (timestamp, service_name, level, message, context)
                VALUES (now(), ?, ?, ?, ?)
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, serviceName)
                stmt.setString(2, level)
                stmt.setString(3, message)
                stmt.setString(4, context ?: "")
                stmt.executeUpdate()
            }
        }
    }
}
