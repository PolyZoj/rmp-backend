package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

class DataSourceConfig {
    private val allHosts = "${env("DB_HOST_MASTER")}:${env("DB_PORT_MASTER")}," +
            "${env("DB_HOST_REPLICA")}:${env("DB_PORT_REPLICA")}"
    private val masterDs: HikariDataSource
    private val replicaDs: HikariDataSource
    val maxPoolSize = 100

    init {
        fun cfg(hosts: String, target: String) = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$hosts/${env("DB_NAME")}?" +
                    "targetServerType=$target" + "&loadBalanceHosts=true" + "&reWriteBatchedInserts=true"
            username                                   = env("DB_USER")
            password                                   = env("DB_PASSWORD")
            driverClassName                            = "org.postgresql.Driver"
            maximumPoolSize                            = maxPoolSize
            minimumIdle                                = 20
            connectionTimeout                          = 10_000 // ms
//            transactionIsolation                       = "TRANSACTION_REPEATABLE_READ"
            connectionTestQuery                        = "SELECT 1"
            initializationFailTimeout                  = 10_000
            healthCheckProperties["connectTimeout"]    = "5000"
        }

        masterDs  = HikariDataSource(cfg(allHosts, "primary"))
        replicaDs = HikariDataSource(cfg(allHosts, "preferSecondary"))
    }

    private fun env(name: String): String =
        System.getenv(name) ?: throw IllegalStateException("Missing env $name")

    val masterDb  = Database.connect(masterDs)
    val replicaDb = Database.connect(replicaDs)

    /**
     * Wraps a write transaction: always targets whichever node is currently primary.
     */
    fun <T> withWrite(block: Transaction.() -> T): T {
        return transaction(masterDb) {
            block()
        }
    }

    /**
     * Wraps a read‑only transaction: prefers the replica if available & standby,
     * otherwise reads from primary.
     */
    fun <T> withRead(block: Transaction.() -> T): T {
        return transaction(replicaDb) {
            block()
        }
    }
}