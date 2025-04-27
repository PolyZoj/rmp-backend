package ru.polyZoj.db

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Slf4jSqlDebugLogger
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger

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
        }
    }

    fun <T> read(block: org.jetbrains.exposed.sql.Transaction.() -> T) = config.withRead(block)
    fun <T> write(block: org.jetbrains.exposed.sql.Transaction.() -> T) = config.withWrite(block)
}