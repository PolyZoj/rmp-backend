package ru.polyZoj.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import ru.polyZoj.logger
import java.sql.SQLException

class DataSourceConfig {
    private val log = logger<DataSourceConfig>()
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

    private fun checkReplicaOnce(): Boolean {
        val replicaAvailable = isAvailable(replicaDs)
        val primaryAvailable = isAvailable(masterDs)
        if (!replicaAvailable) return false
        if (!primaryAvailable) return true
        return isPrimary(replicaDs)
    }

    @Volatile private var useReplica = checkReplicaOnce()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                delay(1000)
                useReplica = checkReplicaOnce()
                log.debug("Checking replica on: $useReplica")
            }
        }
    }

    val masterDb  = Database.connect(masterDs)
    val replicaDb = Database.connect(replicaDs)

    /**
     * Wraps a write transaction: always targets whichever node is currently primary.
     */
    fun <T> withWrite(block: Transaction.() -> T): T {
        val db = if (isPrimary(masterDs)) masterDb else replicaDb
        return transaction(db) {
            log.debug("Writing database {}", db)
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
        val db = if (useReplica) replicaDb else masterDb
        return transaction(db) {
            log.debug("Reading database {}", db)
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)
            block()
        }
    }
}