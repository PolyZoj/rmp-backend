package ru.polyZoj.db

import common.models.EnergySystem
import common.models.FriendshipStatus
import common.models.UnitSystem
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll

object DatabaseFactory {
    private lateinit var config: DataSourceConfig

    internal fun init(cfg: DataSourceConfig) {
        config = cfg

        write {
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

    fun <T> read(block: org.jetbrains.exposed.sql.Transaction.() -> T) = config.withRead(block)
    fun <T> write(block: org.jetbrains.exposed.sql.Transaction.() -> T) = config.withWrite(block)
}