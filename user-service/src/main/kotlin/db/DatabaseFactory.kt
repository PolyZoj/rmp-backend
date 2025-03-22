package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {
    lateinit var masterDb: Database
    lateinit var replicaDb: Database

    fun init() {
        // Для разработки используем локальную базу данных
        val driverClassName = "org.postgresql.Driver"
        val jdbcUrl = "jdbc:postgresql://localhost:5432/user_service"
        val username = "postgres"
        val password = "postgres"

        val dataSource = createHikariDataSource(
            driverClassName = driverClassName,
            jdbcUrl = jdbcUrl,
            username = username,
            password = password
        )

        masterDb = Database.connect(dataSource)
        replicaDb = masterDb // Для разработки используем ту же базу
    }

    private fun createHikariDataSource(
        driverClassName: String,
        jdbcUrl: String,
        username: String,
        password: String
    ): HikariDataSource {
        val config = HikariConfig().apply {
            this.driverClassName = driverClassName
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password

            // Настройки пула
            maximumPoolSize = 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }
}
