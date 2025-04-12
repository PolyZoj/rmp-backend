package ru.polyZoj

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import ru.polyZoj.repositories.ChallengesTable
import ru.polyZoj.repositories.UserChallengesTable
import ru.polyZoj.repositories.ChallengeRewardsTable

object DatabaseFactory {
    fun init() {
        val host = System.getenv("DB_HOST") ?: "localhost"
        val port = System.getenv("DB_PORT") ?: "5432"
        val dbName = System.getenv("DB_NAME") ?: "challenges_db"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"

        val dbUrl = "jdbc:postgresql://$host:$port/$dbName"

        Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = user,
            password = password
        )

        // Create tables if they do not exist
        transaction {
            SchemaUtils.create(ChallengesTable, UserChallengesTable, ChallengeRewardsTable)
        }
    }
}

