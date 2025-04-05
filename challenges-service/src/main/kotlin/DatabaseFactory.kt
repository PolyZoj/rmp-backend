package ru.polyZoj

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import ru.polyZoj.repositories.ChallengesTable
import ru.polyZoj.repositories.UserChallengesTable
import ru.polyZoj.repositories.ChallengeRewardsTable

object DatabaseFactory {
    fun init() {
        // Connect to DB (adjust the URL, user, password)
        Database.connect(
            url = "jdbc:postgresql://localhost:5432/challengesdb",
            driver = "org.postgresql.Driver",
            user = "postgres",
            password = "postgres"
        )

        // Create tables if they do not exist (for demo)
        transaction {
            SchemaUtils.create(ChallengesTable, UserChallengesTable, ChallengeRewardsTable)
        }
    }
}
