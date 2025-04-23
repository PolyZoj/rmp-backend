package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.SQLException

class DataSourceConfig {
    private val masterDs: HikariDataSource
    private val replicaDs: HikariDataSource

    init {
        fun cfg(host: String, port: String) = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/${env("DB_NAME")}"
            username = env("DB_USER")
            password = env("DB_PASSWORD")
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            // simple health-check
            healthCheckProperties["connectTimeout"] = "1000"
        }

        masterDs  = HikariDataSource(cfg(env("DB_HOST_MASTER"), env("DB_PORT_MASTER")))
        replicaDs = HikariDataSource(cfg(env("DB_HOST_REPLICA"), env("DB_PORT_REPLICA")))
    }

    private fun env(name: String): String =
        System.getenv(name) ?: throw IllegalStateException("Missing env $name")

    private fun isAvailable(ds: HikariDataSource): Boolean =
        try {
            ds.connection.use { it.isValid(1) }
        } catch (_: SQLException) { false }

    /** true if this DB is primary (not in recovery) */
    private fun isPrimary(ds: HikariDataSource): Boolean =
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT NOT pg_is_in_recovery()").use { rs ->
                        rs.next() && rs.getBoolean(1)
                    }
                }
            }
        } catch (_: Exception) { false }

    /**
     * Wraps a write transaction: always targets whichever node is currently primary.
     */
    fun <T> withWrite(block: org.jetbrains.exposed.sql.Transaction.() -> T): T {
        val ds = if (isPrimary(masterDs)) masterDs else replicaDs
        val db = org.jetbrains.exposed.sql.Database.connect(ds)
        return org.jetbrains.exposed.sql.transactions.transaction(db) { block() }
    }

    /**
     * Wraps a read‑only transaction: prefers the replica if available & standby,
     * otherwise reads from primary.
     */
    fun <T> withRead(block: org.jetbrains.exposed.sql.Transaction.() -> T): T {
        val ds = if (isAvailable(replicaDs) && !isPrimary(replicaDs)) replicaDs else masterDs
        val db = org.jetbrains.exposed.sql.Database.connect(ds)
        return org.jetbrains.exposed.sql.transactions.transaction(db) { block() }
    }
}