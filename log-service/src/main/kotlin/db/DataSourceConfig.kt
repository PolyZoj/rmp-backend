package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

class DataSourceConfig {
    val dataSource: HikariDataSource

    init {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:clickhouse:https//bwa8rh8b3t.eu-west-1.aws.clickhouse.cloud:8443?user=default&password=8ofIoTG_z7TSg&ssl=true"
            driverClassName = "com.clickhouse.jdbc.ClickHouseDriver"
            maximumPoolSize = 10
            isAutoCommit = true
        })
    }

    private fun env(name: String): String =
        System.getenv(name) ?: throw IllegalStateException("Missing env $name")
}
