package ru.polyZoj.db

import common.models.EnergySystem
import common.models.FriendshipStatus
import common.models.UnitSystem
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import ru.polyZoj.logger

object DatabaseFactory {
    private lateinit var config: DataSourceConfig
    private val log = logger<DatabaseFactory>()

    internal fun init(cfg: DataSourceConfig) {
        config = cfg

        writeBlocking {
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)

            SchemaUtils.create(
                UsersTable,
                UserCredentialsTable,
                UnitSystemsTable,
                EnergySystemsTable,
                PrimaryHealthGoalsTable,
                FriendshipStatusesTable,
                UserParametersTable,
                UserPreferencesTable,
                FriendshipsTable
            )


            if (UnitSystemsTable.selectAll().empty()) {
                UnitSystemsTable.batchInsert(UnitSystem.entries) { enumVal ->
                    this[UnitSystemsTable.systemName] = enumVal
                }
            }

            if (EnergySystemsTable.selectAll().empty()) {
                EnergySystemsTable.batchInsert(EnergySystem.entries) { enumVal ->
                    this[EnergySystemsTable.systemName] = enumVal
                }
            }

            if (FriendshipStatusesTable.selectAll().empty()) {
                FriendshipStatusesTable.batchInsert(FriendshipStatus.entries) { enumVal ->
                    this[FriendshipStatusesTable.friendshipStatus] = enumVal
                }
            }
        }
    }

    suspend fun <T> read(block: org.jetbrains.exposed.sql.Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, config.replicaDb) {
            log.debug("Reading database {}", config.replicaDb)
            addLogger(StdOutSqlLogger)
            addLogger(Slf4jSqlDebugLogger)
            block()
        }

    suspend fun <T> write(block: org.jetbrains.exposed.sql.Transaction.() -> T): T =
        newSuspendedTransaction(Dispatchers.IO, config.masterDb) {
            log.debug("Writing database {}", config.masterDb)
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