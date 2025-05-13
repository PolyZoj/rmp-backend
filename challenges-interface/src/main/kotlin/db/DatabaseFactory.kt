package ru.polyZoj.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object DatabaseFactory {
    private lateinit var config: DataSourceConfig

    internal fun init(cfg: DataSourceConfig) {
        config = cfg

        writeBlocking {
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)

            SchemaUtils.create(
                AchievementsTable
            )
        }
    }

    suspend fun <T> read(block: org.jetbrains.exposed.sql.Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, config.replicaDb) {
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)
            block()
        }

    suspend fun <T> write(block: org.jetbrains.exposed.sql.Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, config.masterDb) {
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)
            block()
        }

    private fun <T> writeBlocking(block: org.jetbrains.exposed.sql.Transaction.() -> T): T {
        return config.withWrite(block)
    }

    fun <T> readBlocking(block: org.jetbrains.exposed.sql.Transaction.() -> T): T {
        return config.withRead(block)
    }
}