package ru.polyZoj.repositories

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.postgresql.util.PSQLException
import ru.polyZoj.db.*
import ru.polyZoj.exceptions.DuplicateFieldException
import ru.polyZoj.logger
import ru.polyZoj.models.UserCredentials
import ru.polyZoj.models.UserDTO
import ru.polyZoj.models.UserRegistration
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

class UserRepository {
    private val log = logger<UserRepository>()
    /** Look up a user’s id by username */
    fun findByUsername(username: String): Int? =
        DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { UserCredentialsTable.username eq username }
                .map { it[UserCredentialsTable.userId] }
                .singleOrNull()
        }

    /** Return userId if credentials match **/
    fun login(username: String, password: String): Int? =
        DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { (UserCredentialsTable.username eq username) and (UserCredentialsTable.password eq password) }
                .map { it[UserCredentialsTable.userId] }
                .singleOrNull()
        }

    /** Create a brand‐new user (all tables) and return their new user_id */
    @OptIn(ExperimentalTime::class)
    fun registerUser(reg: UserRegistration): Int {
        try {
            return DatabaseFactory.write {
                // 1) users
                val userId = UsersTable
                    .insertAndGetId {
                        it[firstName] = reg.firstName
                        it[lastName] = reg.lastName
                        it[email] = reg.email
                        it[createdAt] = Clock.System.now().toJavaInstant()
                        it[avatarUrl] = reg.avatarUrl
                        it[isAdmin] = false
                    }.value

                // 2) credentials
                UserCredentialsTable.insert {
                    it[UserCredentialsTable.userId] = userId
                    it[username] = reg.username
                    it[password] = reg.password
                }

                // 3) parameters
                UserParametersTable.insert {
                    it[UserParametersTable.userId] = userId
                    it[weight] = reg.weight
                    it[height] = reg.height
                    it[birthDate] = reg.birthDate
                    it[unitSystemId] = reg.unitSystemId
                }

                // 4) preferences
                UserPreferencesTable.insert {
                    it[UserPreferencesTable.userId] = userId
                    it[unitSystemId] = reg.unitSystemId
                    it[energySystemId] = reg.energySystemId
                    it[healthGoalId] = reg.healthGoalId
                    it[dailyStepGoal] = reg.dailyStepGoal
                    it[waterIntakeGoal] = reg.waterIntakeGoal
                    it[calorieGoal] = reg.calorieGoal
                    it[sleepGoal] = reg.sleepGoal
                    it[workoutsCount] = reg.workoutsCount
                }
                userId
            }
        } catch (e: ExposedSQLException) {
            if (e.cause is PSQLException && (e.cause as PSQLException).sqlState == "23505") {
                val msg = e.cause!!.message ?: ""
                when {
                    "users_email_key" in msg -> throw DuplicateFieldException("email") as Throwable
                    "users_credentials_user_name_key" in msg -> throw DuplicateFieldException("username") as Throwable
                }
            }
            throw e
        }
    }

    @OptIn(ExperimentalTime::class)
    fun getUserDTO(userId: Int): UserDTO? {
        var userDTO: UserDTO? = null
        DatabaseFactory.read {
            UsersTable
                .join(UserCredentialsTable, onColumn = UserCredentialsTable.userId, joinType = JoinType.INNER)
                .join(UserParametersTable, onColumn = UserCredentialsTable.userId, joinType = JoinType.INNER)
                .join(UserPreferencesTable, onColumn = UserPreferencesTable.userId, joinType = JoinType.INNER)
                .selectAll()
                .where(UsersTable.id eq userId)
        }
            .forEach { row ->
                userDTO = UserDTO(
                    userId = row[UsersTable.id].value,
                    firstName = row[UsersTable.firstName],
                    lastName = row[UsersTable.lastName],
                    email = row[UsersTable.email],
                    avatarUrl = row[UsersTable.avatarUrl],
                    isAdmin = row[UsersTable.isAdmin],
                    createdAt = row[UsersTable.createdAt].toKotlinInstant(),
                    username = row[UserCredentialsTable.username],
                    password = row[UserCredentialsTable.password],
                    weight = row[UserParametersTable.weight],
                    height = row[UserParametersTable.height],
                    dateOfBirth = row[UserParametersTable.birthDate],
                    unitSystemId = row[UserParametersTable.unitSystemId],
                    energySystemId = row[UserPreferencesTable.energySystemId],
                    primaryHealthGoalId = row[UserPreferencesTable.healthGoalId],
                    dailyStepGoal = row[UserPreferencesTable.dailyStepGoal],
                    waterIntakeGoal = row[UserPreferencesTable.waterIntakeGoal],
                    calorieGoal = row[UserPreferencesTable.calorieGoal],
                    sleepGoal = row[UserPreferencesTable.sleepGoal],
                    workoutsCount = row[UserPreferencesTable.workoutsCount],
                )
            }
        return userDTO
    }

    fun deleteUser(userId: Int): Boolean {
        return DatabaseFactory.write {
            val usersDeleted = UsersTable.deleteWhere { UsersTable.id eq userId }
            val credentialsDeleted = UserCredentialsTable.deleteWhere { UserCredentialsTable.userId eq userId }
            val parametersDeleted = UserParametersTable.deleteWhere { UserParametersTable.userId eq userId }
            val preferencesDeleted = UserPreferencesTable.deleteWhere { UserPreferencesTable.userId eq userId }
            usersDeleted > 0 || credentialsDeleted > 0 || parametersDeleted > 0 || preferencesDeleted > 0
        }
    }

    private fun toUserCredentials(row: ResultRow) = UserCredentials(
        userId   = row[UserCredentialsTable.userId],
        username = row[UserCredentialsTable.username],
        password = row[UserCredentialsTable.password]
    )
}
