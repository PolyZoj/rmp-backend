package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File

class DataSourceConfig {
    val dataSource: HikariDataSource

    init {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = File(env("CLICKHOUSE_URL")).readText().trim()
            driverClassName = "com.clickhouse.jdbc.ClickHouseDriver"
            maximumPoolSize = 10
            isAutoCommit = true
        })
    }

    private fun env(name: String): String =
        System.getenv(name) ?: throw IllegalStateException("Missing env $name")
}
