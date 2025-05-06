package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import ru.polyZoj.logger

class DataSourceConfig {
    private val log = logger<DataSourceConfig>()
    private val allHosts = "${env("DB_HOST_MASTER")}:${env("DB_PORT_MASTER")}," +
            "${env("DB_HOST_REPLICA")}:${env("DB_PORT_REPLICA")}"
    private val masterDs: HikariDataSource
    private val replicaDs: HikariDataSource

    init {
        fun cfg(hosts: String, target: String) = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$hosts/${env("DB_NAME")}?" +
                    "targetServerType=$target&loadBalanceHosts=true"
            username            = env("DB_USER")
            password            = env("DB_PASSWORD")
            driverClassName     = "org.postgresql.Driver"
            maximumPoolSize     = 10
            transactionIsolation= "TRANSACTION_REPEATABLE_READ"
            connectionTestQuery = "SELECT 1"
            initializationFailTimeout = 0
            healthCheckProperties["connectTimeout"] = "5000"
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
            log.debug("Writing database {}", masterDb)
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)
            block()
        }
    }

    /**
     * Wraps a read‑only transaction: prefers the replica if available & standby,
     * otherwise reads from primary.
     */
    fun <T> withRead(block: Transaction.() -> T): T {
        return transaction(replicaDb) {
            log.debug("Reading database {}", replicaDb)
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)
            block()
        }
    }
}