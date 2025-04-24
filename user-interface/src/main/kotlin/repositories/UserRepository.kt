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
    fun findByUsername(username: String): Int? {
        log.info("Entering findByUsername with username='{}'", username)
        val result = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { UserCredentialsTable.username eq username }
                .map { it[UserCredentialsTable.userId] }
                .singleOrNull()
        }
        if (result != null) {
            log.info("User found for username='{}', userId={} ", username, result)
        } else {
            log.info("No user found for username='{}'", username)
        }
        return result
    }

    /** Return userId if credentials match **/
    fun login(username: String, password: String): Int? {
        log.info("Attempting login for username='{}'", username)
        val userId = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { (UserCredentialsTable.username eq username) and (UserCredentialsTable.password eq password) }
                .map { it[UserCredentialsTable.userId] }
                .singleOrNull()
        }
        if (userId != null) {
            log.info("Login successful for username='{}', userId={}", username, userId)
        } else {
            log.info("Login failed for username='{}'", username)
        }
        return userId
    }

    /** Create a brand‐new user (all tables) and return their new user_id */
    @OptIn(ExperimentalTime::class)
    fun registerUser(reg: UserRegistration): Int {
        log.info("Registering new user: username='{}', email='{}'", reg.username, reg.email)
        try {
            val newUserId = DatabaseFactory.write {
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
                log.debug("Inserted into UsersTable, userId={}", userId)

                // 2) credentials
                UserCredentialsTable.insert {
                    it[UserCredentialsTable.userId] = userId
                    it[username] = reg.username
                    it[password] = reg.password
                }
                log.debug("Inserted into UserCredentialsTable for userId={}", userId)

                // 3) parameters
                UserParametersTable.insert {
                    it[UserParametersTable.userId] = userId
                    it[weight] = reg.weight
                    it[height] = reg.height
                    it[birthDate] = reg.birthDate
                    it[unitSystemId] = reg.unitSystemId
                }
                log.debug("Inserted into UserParametersTable for userId={}", userId)

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
                log.debug("Inserted into UserPreferencesTable for userId={}", userId)
                userId
            }
            log.info("User registered successfully with userId={}", newUserId)
            return newUserId
        } catch (e: ExposedSQLException) {
            log.error("Error registering user: username='{}'\nerror={}", reg.username, e.message)
            if (e.cause is PSQLException && (e.cause as PSQLException).sqlState == "23505") {
                val msg = e.cause!!.message ?: ""
                when {
                    "users_email_key" in msg -> {
                        log.warn("Duplicate email during registration: {}", reg.email)
                        throw DuplicateFieldException("email")
                    }
                    "users_credentials_user_name_key" in msg -> {
                        log.warn("Duplicate username during registration: {}", reg.username)
                        throw DuplicateFieldException("username")
                    }
                }
            }
            throw e
        }
    }

    @OptIn(ExperimentalTime::class)
    fun getUserDTO(userId: Int): UserDTO? {
        log.info("Fetching UserDTO for userId={}", userId)
        val userDTO: UserDTO? = DatabaseFactory.read {
            log.debug("Joining tables to fetch UserDTO for userId={}", userId)
            UsersTable
                .join(UserCredentialsTable, onColumn = UserCredentialsTable.userId, joinType = JoinType.INNER)
                .join(UserParametersTable, onColumn = UserCredentialsTable.userId, joinType = JoinType.INNER)
                .join(UserPreferencesTable, onColumn = UserCredentialsTable.userId, joinType = JoinType.INNER)
                .selectAll()
                .where(UsersTable.id eq userId).map { row ->
                    UserDTO(
                        userId            = row[UsersTable.id].value,
                        firstName         = row[UsersTable.firstName],
                        lastName          = row[UsersTable.lastName],
                        email             = row[UsersTable.email],
                        avatarUrl         = row[UsersTable.avatarUrl],
                        isAdmin           = row[UsersTable.isAdmin],
                        createdAt         = row[UsersTable.createdAt].toKotlinInstant(),
                        username          = row[UserCredentialsTable.username],
                        password          = row[UserCredentialsTable.password],
                        weight            = row[UserParametersTable.weight],
                        height            = row[UserParametersTable.height],
                        dateOfBirth       = row[UserParametersTable.birthDate],
                        unitSystemId      = row[UserParametersTable.unitSystemId],
                        energySystemId    = row[UserPreferencesTable.energySystemId],
                        primaryHealthGoalId = row[UserPreferencesTable.healthGoalId],
                        dailyStepGoal     = row[UserPreferencesTable.dailyStepGoal],
                        waterIntakeGoal   = row[UserPreferencesTable.waterIntakeGoal],
                        calorieGoal       = row[UserPreferencesTable.calorieGoal],
                        sleepGoal         = row[UserPreferencesTable.sleepGoal],
                        workoutsCount     = row[UserPreferencesTable.workoutsCount]
                    )
                }
                .singleOrNull()
        }.also {
            if (it != null) log.info("UserDTO fetched successfully for userId={}", userId)
            else            log.info("No UserDTO found for userId={}", userId)
        }
        return userDTO
    }

    fun deleteUser(userId: Int): Boolean {
        log.info("Deleting user and related data for userId={}", userId)
        val deleted = DatabaseFactory.write {
            val usersDeleted = UsersTable.deleteWhere { UsersTable.id eq userId }
            val credentialsDeleted = UserCredentialsTable.deleteWhere { UserCredentialsTable.userId eq userId }
            val parametersDeleted = UserParametersTable.deleteWhere { UserParametersTable.userId eq userId }
            val preferencesDeleted = UserPreferencesTable.deleteWhere { UserPreferencesTable.userId eq userId }
            usersDeleted > 0 || credentialsDeleted > 0 || parametersDeleted > 0 || preferencesDeleted > 0
        }
        if (deleted) log.info("User deletion succeeded for userId={}", userId) else log.warn("No records deleted for userId={}", userId)
        return deleted
    }

    private fun toUserCredentials(row: ResultRow): UserCredentials {
        log.debug("Mapping ResultRow to UserCredentials for row={} ", row)
        return UserCredentials(
            userId   = row[UserCredentialsTable.userId],
            username = row[UserCredentialsTable.username],
            password = row[UserCredentialsTable.password]
        )
    }
}
