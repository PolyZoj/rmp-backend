package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

class DataSourceConfig {
    val dataSource: HikariDataSource

    init {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:clickhouse://${env("CLICKHOUSE_HOST")}:${env("CLICKHOUSE_PORT")}/default"
            driverClassName = "com.clickhouse.jdbc.ClickHouseDriver"
            maximumPoolSize = 10
            isAutoCommit = true
        })
    }

    private fun env(name: String): String =
        System.getenv(name) ?: throw IllegalStateException("Missing env $name")
}
