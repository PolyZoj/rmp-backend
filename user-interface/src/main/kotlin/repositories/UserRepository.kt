package ru.polyZoj.repositories

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.postgresql.util.PSQLException
import ru.polyZoj.db.*
import common.exceptions.DuplicateFieldException
import common.models.FriendshipStatus
import common.models.FriendshipStatusFrontEnd
import ru.polyZoj.logger
import common.models.User
import common.models.UserBasicInfo
import common.models.UserDTO
import common.models.UserRegistration
import common.models.UserUpdatable
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.update
import ru.polyZoj.cache.RedisFactory
import ru.polyZoj.cache.getJson
import ru.polyZoj.cache.setJson
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

class UserRepository {
    private val log = logger<UserRepository>()

    /**
     * Cache keys:
     * - userIdOf:username:<username>
     * - usernameOf:userId:<userId>
     * - passwordOf:userId:<userId>
     * - userDTOOf:userId:<userId>
     * - friendshipStatusFrontEndOf:<selfId>:<friendId>
     * - friendshipRequestsOf:userId:<selfId>
     * - friendsOf:userId:<userId>
     * - allIds
     */
    private val redis = RedisFactory.sync


    /** Look up a user’s id by username */
    suspend fun findByUsername(username: String): Int? {
        log.debug("Entering findByUsername with username='{}'", username)

        val cachedId = redis.getJson<Int>("userIdOf:username:$username")
        if (cachedId != null) {
            log.info("User ID found in cache for username='{}'", username)
            return cachedId
        } else {
            log.debug("No user ID found in cache for username='{}'", username)
        }

        val result: Int? = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { UserCredentialsTable.username eq username }
                .limit(1)
                .map { it[UserCredentialsTable.userId].value }
                .singleOrNull()
        }
        if (result != null) {
            log.info("User found for username='{}', userId={} ", username, result)
            log.debug("Setting cache for userIdOf:username='{}'", username)
            redis.setJson("userIdOf:username:$username", result, 600)
        } else {
            log.info("No user found for username='{}'", username)
        }
        return result
    }

    suspend fun findUsernameById(userId: Int): String? {
        log.debug("Entering findUsernameById with userId={}", userId)

        val cachedUsername = redis.getJson<String>("usernameOf:userId:$userId")
        if (cachedUsername != null) {
            log.info("Username found in cache for userId={}", userId)
            return cachedUsername
        } else {
            log.debug("No username found in cache for userId={}", userId)
        }

        val result = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.username)
                .where { UserCredentialsTable.userId eq userId }
                .limit(1)
                .map { it[UserCredentialsTable.username] }
                .singleOrNull()
        }
        if (result != null) {
            log.info("Username found for userId={}, username='{}'", userId, result)
            log.debug("Setting cache for usernameOf:userId={}", userId)
            redis.setJson("usernameOf:userId:$userId", result, 600)
        } else {
            log.info("No username found for userId={}", userId)
        }
        return result
    }

    /** Return userId if credentials match **/
    suspend fun login(username: String, password: String): Int? {
        log.debug("Attempting login for username='{}'", username)

        val cachedUsername = redis.getJson<String>("usernameOf:username:$username")
        val cachedPassword = redis.getJson<String>("passwordOf:password")
        val cachedId = redis.getJson<Int>("userIdOf:username:$username")
        if (cachedUsername != null && cachedPassword != null && cachedId != null) {
            log.info("Credentials found in cache for username='{}'", username)
            if (cachedUsername == username && cachedPassword == password) {
                log.info("Login successful for username='{}'", username)
                return cachedId
            } else {
                log.warn("Invalid credentials found in cache for username='{}'", username)
            }
        } else {
            log.debug("No credentials found in cache for username='{}'", username)
        }

        val userId: Int? = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { (UserCredentialsTable.username eq username) and (UserCredentialsTable.password eq password) }
                .limit(1)
                .map { it[UserCredentialsTable.userId].value }
                .singleOrNull()
        }
        if (userId != null) {
            log.info("Login successful for username='{}', userId={}", username, userId)
            log.debug("Setting caches for userId={}", userId)
            redis.setJson("userIdOf:username:$username", userId, 600)
            redis.setJson("usernameOf:userId:$userId", username, 600)
            redis.setJson("passwordOf:userId:$userId", password, 600)
        } else {
            log.info("Login failed for username='{}'", username)
        }
        return userId
    }

    /** Create a brand‐new user (all tables) and return their new user_id */
    @OptIn(ExperimentalTime::class)
    suspend fun createUser(reg: UserRegistration): Int {
        log.debug("Registering new user: username='{}'", reg.username)
        try {
            val newUserId = DatabaseFactory.write {
                // 1) users
                val userId = UsersTable
                    .insertAndGetId {
                        it[UsersTable.firstName] = reg.firstName
                        it[UsersTable.lastName] = reg.lastName
                        it[UsersTable.email] = reg.email
                        it[UsersTable.avatarUrl] = reg.avatarUrl
                        it[UsersTable.isAdmin] = false
                        it[UsersTable.createdAt] = Clock.System.now().toJavaInstant()
                    }.value
                log.debug("Inserted into UsersTable, userId={}", userId)

                // 2) credentials
                UserCredentialsTable.insert {
                    it[UserCredentialsTable.userId] = userId
                    it[UserCredentialsTable.username] = reg.username
                    it[UserCredentialsTable.password] = reg.password
                }
                log.debug("Inserted into UserCredentialsTable for userId={}", userId)

                val unitSystemId   = StaticLookups.idFor(reg.unitSystem)
                val energySystemId = StaticLookups.idFor(reg.energySystem)

                // 3) parameters
                UserParametersTable.insert {
                    it[UserParametersTable.userId] = userId
                    it[UserParametersTable.weight] = reg.weight
                    it[UserParametersTable.height] = reg.height
                    it[UserParametersTable.birthDate] = reg.birthDate
                    it[UserParametersTable.unitSystemId] = unitSystemId
                }
                log.debug("Inserted into UserParametersTable for userId={}", userId)

                // get the health goal id or add it
                var healthGoalId: Int? = null
                val healthGoal = reg.healthGoal
                if (healthGoal != null) {
                    healthGoalId = PrimaryHealthGoalsTable
                        .select(PrimaryHealthGoalsTable.healthGoalId)
                        .where(PrimaryHealthGoalsTable.goalName eq healthGoal)
                        .map { it[PrimaryHealthGoalsTable.healthGoalId] }
                        .singleOrNull()
                        ?: PrimaryHealthGoalsTable.insert {
                            it[PrimaryHealthGoalsTable.goalName] = healthGoal
                        }[PrimaryHealthGoalsTable.healthGoalId]
                    log.debug("Inserted into PrimaryHealthGoalsTable for userId={}", userId)
                }

                // 4) preferences
                UserPreferencesTable.insert {
                    it[UserPreferencesTable.userId] = userId
                    it[UserPreferencesTable.unitSystemId] = unitSystemId
                    it[UserPreferencesTable.energySystemId] = energySystemId
                    it[UserPreferencesTable.healthGoalId] = healthGoalId
                    it[UserPreferencesTable.dailyStepGoal] = reg.dailyStepGoal
                    it[UserPreferencesTable.waterIntakeGoal] = reg.waterIntakeGoal
                    it[UserPreferencesTable.calorieGoal] = reg.calorieGoal
                    it[UserPreferencesTable.sleepGoal] = reg.sleepGoal
                    it[UserPreferencesTable.workoutsGoal] = reg.workoutsGoal
                }
                log.debug("Inserted into UserPreferencesTable for userId={}", userId)
                userId
            }

            log.debug("Setting caches for userId={}", newUserId)
            redis.setJson("usernameOf:userId:${newUserId}", reg.username, 600)
            redis.setJson("userIdOf:username:${reg.username}", newUserId, 600)
            redis.setJson("passwordOf:userId:${newUserId}", reg.password, 600)

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

    /** Get userDTO by userId */
    @OptIn(ExperimentalTime::class)
    suspend fun getUserDTO(userId: Int): UserDTO? {
        log.debug("Fetching UserDTO for userId={}", userId)

        val cachedUserDTO = redis.getJson<UserDTO>("userDTOOf:userId:$userId")
        if (cachedUserDTO != null) {
            log.info("UserDTO found in cache for userId={}", userId)
            return cachedUserDTO
        } else {
            log.debug("No UserDTO found in cache for userId={}", userId)
        }

        val userDTO: UserDTO? = try {
            DatabaseFactory.read {
                log.debug("Joining tables to fetch UserDTO for userId={}", userId)
                try {
                    UsersTable
                        .innerJoin(UserCredentialsTable)
                        .innerJoin(UserParametersTable)
                        .innerJoin(UserPreferencesTable)
                        .join(UnitSystemsTable,
                            onColumn = UserParametersTable.unitSystemId,
                            otherColumn = UnitSystemsTable.unitSystemId,
                            joinType = JoinType.INNER
                        )
                        .innerJoin(EnergySystemsTable)
                        .leftJoin(PrimaryHealthGoalsTable) // leftJoin so missing healthGoal → null
                        .selectAll()
                        .where { UsersTable.id eq userId }
                        .limit(1)
                        .map { row ->
                            try {
                                UserDTO(
                                    user = User(
                                        userId    = row[UsersTable.id].value,
                                        firstName = row[UsersTable.firstName],
                                        lastName  = row[UsersTable.lastName],
                                        email     = row[UsersTable.email],
                                        avatarUrl = row[UsersTable.avatarUrl],
                                        isAdmin   = row[UsersTable.isAdmin],
                                        createdAt = row[UsersTable.createdAt].toKotlinInstant(),
                                        clubId = row[UsersTable.clubId],
                                    ),
                                    username        = row[UserCredentialsTable.username],
                                    weight          = row[UserParametersTable.weight],
                                    height          = row[UserParametersTable.height],
                                    birthDate       = row[UserParametersTable.birthDate],
                                    unitSystem      = row[UnitSystemsTable.systemName],
                                    energySystem    = row[EnergySystemsTable.systemName],
                                    healthGoal      = row[PrimaryHealthGoalsTable.goalName],       // nullable
                                    dailyStepGoal   = row[UserPreferencesTable.dailyStepGoal],
                                    waterIntakeGoal = row[UserPreferencesTable.waterIntakeGoal],
                                    calorieGoal     = row[UserPreferencesTable.calorieGoal],
                                    sleepGoal       = row[UserPreferencesTable.sleepGoal],
                                    workoutsGoal    = row[UserPreferencesTable.workoutsGoal]
                                )
                            } catch (dtoEx: Exception) {
                                log.error(
                                    "Failed to map ResultRow → UserDTO for userId={}",
                                    userId,
                                    dtoEx
                                )
                                throw dtoEx
                            }
                        }
                        .singleOrNull()
                } catch (sqlOrMapEx: Exception) {
                    log.error(
                        "Error fetching or building UserDTO for userId={}",
                        userId,
                        sqlOrMapEx
                    )
                    null
                }
            }
        } catch (txEx: Exception) {
            log.error(
                "Transaction error when reading UserDTO for userId={}",
                userId,
                txEx
            )
            null
        }

        if (userDTO != null) {
            log.info("UserDTO fetched successfully for userId={}", userId)
            log.debug("Setting cache for userId={}", userId)
            redis.setJson("userDTOOf:userId:$userId", userDTO, 600)
        } else {
            log.info("No UserDTO found or error occurred for userId={}", userId)
        }
        return userDTO
    }

    /** Delete user and all related data. Returns true if any rows were deleted. */
    suspend fun deleteUser(userId: Int): Boolean {
        log.debug("Deleting user (and cascading related rows) for userId={}", userId)

        return try {
            val deletedCount = DatabaseFactory.write {
                UsersTable.deleteWhere { UsersTable.id eq userId }
            }

            if (deletedCount > 0) {
                log.info("User deletion (with cascade) succeeded for userId={}", userId)
                log.debug("Deleting user from cache for userId={}", userId)
                redis.del("userDTOOf:userId:$userId")
                redis.del("usernameOf:userId:$userId")
                redis.del("passwordOf:userId:$userId")
                true
            } else {
                log.warn("No user found to delete for userId={}", userId)
                false
            }
        } catch (e: Exception) {
            log.error("Failed to delete user data for userId={}", userId, e)
            false
        }
    }

    /** Update user data. Returns true if any rows were updated. */
    suspend fun updateUser(userId: Int, userUpdatable: UserUpdatable): Boolean {
        log.debug("Updating user data for userId={}", userId)
        return try {
            DatabaseFactory.write {
                userUpdatable.avatarUrl?.let { a ->
                    UsersTable.update({ UsersTable.id eq userId }) {
                        it[avatarUrl] = a
                    }
                }
                userUpdatable.height?.let { h ->
                    UserParametersTable.update({ UserParametersTable.userId eq userId }) {
                        it[height] = h
                    }
                }
                userUpdatable.weight?.let { w ->
                    UserParametersTable.update({ UserParametersTable.userId eq userId }) {
                        it[weight] = w
                    }
                }
                userUpdatable.healthGoal?.let { h ->
                    val healthId = PrimaryHealthGoalsTable
                        .select(PrimaryHealthGoalsTable.goalName eq h)
                        .map { it[PrimaryHealthGoalsTable.healthGoalId] }
                        .singleOrNull()
                        ?: PrimaryHealthGoalsTable.insert {
                            it[goalName] = h
                        }[PrimaryHealthGoalsTable.healthGoalId]
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) {
                        it[healthGoalId] = healthId
                    }
                }
                userUpdatable.dailyStepGoal?.let { d ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) {
                        it[dailyStepGoal] = d
                    }
                }
                userUpdatable.waterIntakeGoal?.let { w ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) {
                        it[waterIntakeGoal] = w
                    }
                }
                userUpdatable.calorieGoal?.let { c ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) {
                        it[calorieGoal] = c
                    }
                }
                userUpdatable.sleepGoal?.let { s ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) {
                        it[sleepGoal] = s
                    }
                }
                userUpdatable.workoutsGoal?.let { w ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) {
                        it[workoutsGoal] = w
                    }
                }
            }
            log.debug("Deleting cache for userId={}", userId)
            redis.del("userDTOOf:userId:$userId")

            true
        } catch (e: Exception) {
            log.error("Error updating user data for userId={}", userId, e)
            false
        }
    }

    /** Get friendship status related with userId and friendId with statuses from `FriendshipStatusFrontEnd` */
    suspend fun getFriendshipStatus(selfId: Int, friendId: Int): FriendshipStatusFrontEnd? {
        log.debug("Fetching friendship status for selfId={} and friendId={}", selfId, friendId)
        if (selfId == friendId) {
            log.debug("Setting cache for selfId={} and friendId={}", selfId, friendId)
            redis.setJson("friendshipStatusFrontEndOf:$selfId:$friendId", FriendshipStatusFrontEnd.SELF, 600)
            return FriendshipStatusFrontEnd.SELF
        }

        val cachedStatus = redis.getJson<FriendshipStatusFrontEnd>("friendshipStatusFrontEndOf:$selfId:$friendId")
        if (cachedStatus != null) {
            log.info("Friendship status found in cache for selfId={} and friendId={}", selfId, friendId)
            return cachedStatus
        } else {
            log.debug("No friendship status found in cache for selfId={} and friendId={}", selfId, friendId)
        }

        return try {
            DatabaseFactory.read {
                FriendshipsTable
                    .selectAll()
                    .where {
                        ((FriendshipsTable.userId eq selfId) and (FriendshipsTable.friendId eq friendId)) or
                                ((FriendshipsTable.userId eq friendId) and (FriendshipsTable.friendId eq selfId))
                    }
                    .singleOrNull()
            }
                ?.let { row ->
                    val status = StaticLookups.nameForFriendshipStatusId(row[FriendshipsTable.friendshipStatus])
                    when (status) {
                        FriendshipStatus.ACCEPTED -> FriendshipStatusFrontEnd.YOUR_FRIEND
                        FriendshipStatus.PENDING -> {
                            if (row[FriendshipsTable.userId].value == selfId) {
                                FriendshipStatusFrontEnd.INVITE_SENT
                            } else {
                                FriendshipStatusFrontEnd.NOT_YOUR_FRIEND
                            }
                        }
                        else -> FriendshipStatusFrontEnd.NOT_YOUR_FRIEND
                    }
                }
                .also { result ->
                    if (result != null) {
                        log.info("Friendship status found: {}", result)
                        log.debug("Setting cache for friendship status for selfId={} and friendId={}", selfId, friendId)
                        redis.setJson("friendshipStatusFrontEndOf:$selfId:$friendId", result, 600)
                    } else {
                        log.info("No friendship status found for selfId={} and friendId={}", selfId, friendId)
                    }
                }
        } catch (e: Exception) {
            log.error("Error fetching friendship status for userId={} and friendId={}", selfId, friendId, e)
            null
        }
    }

    /** Get all friendships for a user with a given status */
    suspend fun getFriendshipsWhereStatus(userId: Int, status: FriendshipStatus): List<Int> {
        return DatabaseFactory.read {
            val statusPendingId = StaticLookups.idFor(status)

            when (status) {
                FriendshipStatus.PENDING -> {
                    FriendshipsTable
                        .selectAll()
                        .where { // we're looking for friendship requests for the given userId
                            (FriendshipsTable.friendId eq userId) and
                            (FriendshipsTable.friendshipStatus eq statusPendingId)
                        }
                        .map { row ->
                            // whichever side isn’t the given userId
                            val uid = row[FriendshipsTable.userId].value
                            val fid = row[FriendshipsTable.friendId].value
                            if (uid == userId) fid else uid
                        }
                        .distinct()
                }
                else -> {
                    FriendshipsTable
                        .selectAll()
                        .where {
                            ((FriendshipsTable.userId eq userId) or
                                    (FriendshipsTable.friendId eq userId)) and
                                    (FriendshipsTable.friendshipStatus eq statusPendingId)
                        }
                        .map { row ->
                            val uid = row[FriendshipsTable.userId].value
                            val fid = row[FriendshipsTable.friendId].value
                            if (uid == userId) fid else uid
                        }
                        .distinct()
                }
            }
        }
    }

    /** Finds all friendship requests for a user (status pending)*/
    suspend fun getFriendshipRequests(userId: Int): List<Int> {
        log.debug("Fetching friendship requests for userId={}", userId)

        val cachedRequests = redis.getJson<List<Int>>("friendshipRequestsOf:userId:$userId")
        if (cachedRequests != null) {
            log.info("Friendship requests found in cache for userId={}", userId)
            return cachedRequests
        } else {
            log.debug("No friendship requests found in cache for userId={}", userId)
        }

        return getFriendshipsWhereStatus(userId, FriendshipStatus.PENDING)
            .also {
                log.info("Found {} friendship requests for userId={}", it.size, userId)
                log.debug("Setting cache for friendship requests for userId={}", userId)
                redis.setJson("friendshipRequestsOf:userId:$userId", it, 600)
            }
    }

    fun deleteFriendshipCache(uId: Int, fId: Int) {
        log.debug("Deleting cache for friends and requests of userId={} and friendId={}", fId, uId)
        redis.del(
            "friendshipStatusFrontEndOf:$fId:$uId",
            "friendshipStatusFrontEndOf:$uId:$fId",
            "friendshipRequestsOf:userId:$fId",
            "friendshipRequestsOf:userId:$uId",
            "friendsOf:userId:$fId",
            "friendsOf:userId:$uId"
        )
    }

    /** Set friendship request status. Returns success boolean */
    suspend fun setFriendshipRequestStatus(uId: Int, fId: Int, status: FriendshipStatus?): Boolean {
        log.debug("Setting friendship request status={} from userId={} to friendId={}", status, uId, fId)

        return try {
            DatabaseFactory.write {
                val statusId = StaticLookups.idFor(status!!)
                when (status) {
                    FriendshipStatus.ACCEPTED, FriendshipStatus.REJECTED -> {
                        val rows = FriendshipsTable.update({
                            (FriendshipsTable.userId eq fId) and (FriendshipsTable.friendId eq uId)
                        }) {
                            it[friendshipStatus] = statusId
                        }
                        if (rows == 0) {
                            log.warn("No friendship request found to update for userId={} and friendId={}", fId, uId)
                            return@write false
                        } else {
                            log.info("Friendship request updated successfully for userId={} and friendId={}", fId, uId)
                            deleteFriendshipCache(uId, fId)
                            return@write true
                        }
                    }
                    else -> {
                        val friendshipRelationExists = FriendshipsTable
                            .selectAll()
                            .where{
                                ((FriendshipsTable.userId eq uId) and (FriendshipsTable.friendId eq fId)) or
                                        ((FriendshipsTable.userId eq fId) and (FriendshipsTable.friendId eq uId))
                            }
                            .count() > 0
                        log.debug("Friendship exists: {}", friendshipRelationExists)

                        if (!friendshipRelationExists) {
                            FriendshipsTable.insert {
                                it[userId] = uId
                                it[friendId] = fId
                                it[friendshipStatus] = statusId
                            }
                            deleteFriendshipCache(uId, fId)
                            return@write true
                        } else {
                            log.debug("Friendship already exists, updating status")
                            FriendshipsTable.update({
                                (FriendshipsTable.userId eq uId) and
                                        (FriendshipsTable.friendId eq fId) or
                                        (FriendshipsTable.userId eq fId) and
                                        (FriendshipsTable.friendId eq uId)
                            }) {
                                it[friendshipStatus] = statusId
                            }
                            deleteFriendshipCache(uId, fId)
                            return@write true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error setting friendship request status={} from userId={} to friendId={}", status, uId, fId, e)
            false
        }
    }

    /** Removes friendship row.
     * status not used, but necessary for the function signature
     * */
    suspend fun removeFriendship(userId: Int, friendId: Int, status: FriendshipStatus? = null): Boolean {
        log.debug("Removing friendship from userId={} to friendId={}", userId, friendId)
        return try {
            DatabaseFactory.write {
                FriendshipsTable.deleteWhere {
                    (FriendshipsTable.userId eq userId) and
                    (FriendshipsTable.friendId eq friendId) or
                    (FriendshipsTable.userId eq friendId) and
                    (FriendshipsTable.friendId eq userId)
                }
            }
            deleteFriendshipCache(userId, friendId)
            true
        } catch (e: Exception) {
            log.error("Error removing friendship from userId={} to friendId={}", userId, friendId, e)
            false
        }
    }

    /** Get all friends for a user (status accepted) */
    suspend fun getFriends(userId: Int): List<Int> {
        log.debug("Fetching friends for userId={}", userId)

        val cachedFriends = redis.getJson<List<Int>>("friendsOf:userId:$userId")
        if (cachedFriends != null) {
            log.info("Friends found in cache for userId={}", userId)
            return cachedFriends
        } else {
            log.debug("No friends found in cache for userId={}", userId)
        }

        return getFriendshipsWhereStatus(userId, FriendshipStatus.ACCEPTED)
            .also {
                log.info("Found {} friends for userId={}", it.size, userId)
                log.debug("Setting cache for friends for userId={}", userId)
                redis.setJson("friendsOf:userId:$userId", it, 600)
            }
    }

    /**
     * Find user IDs by username substring.
     * Returns a list of user IDs that match the given substring.
     * The search is case-insensitive.
     */
    suspend fun findUserIdsByUsernameSubstring(list: List<Int>, substring: String): List<Int> {
        log.debug("Searching for user IDs where username ILIKE '%{}%'", substring)
        return try {
            DatabaseFactory.read {
                UserCredentialsTable
                    .select(UserCredentialsTable.userId)
                    .where {
                        (UserCredentialsTable.userId inList list) and
                        // LOWER(username) LIKE '%lower(substring)%'
                        (UserCredentialsTable.username.lowerCase() like "%${substring.lowercase()}%")
                    }
                    .map { row ->
                        row[UserCredentialsTable.userId].value
                    }
            }
        } catch (ex: Exception) {
            log.error("Error querying user IDs by username substring='{}'", substring, ex)
            emptyList()
        }.also { result ->
            log.info("Found {} matching user IDs for substring='{}'", result.size, substring)
        }
    }

    /** Get basic info for a list of users (userId, username, avatarUrl) */
    suspend fun getUsersBasicInfo(userIds: List<Int>): List<UserBasicInfo> {
        log.debug("Fetching basic info for userIds={}", userIds)

        return try {
            DatabaseFactory.read {
                try {
                    UsersTable
                        .innerJoin(UserCredentialsTable)
                        .select(
                            UsersTable.id,
                            UserCredentialsTable.username,
                            UsersTable.avatarUrl
                        )
                        .where { UsersTable.id inList userIds }
                        .map { row ->
                            UserBasicInfo(
                                userId    = row[UsersTable.id].value,
                                username  = row[UserCredentialsTable.username],
                                avatarUrl = row[UsersTable.avatarUrl]
                            )
                        }
                } catch (sqlEx: Exception) {
                    log.error(
                        "Error querying basic info for userIds={}",
                        userIds,
                        sqlEx
                    )
                    emptyList()
                }
            }
        } catch (txEx: Exception) {
            log.error(
                "Transaction error when reading basic info for userIds={}",
                userIds,
                txEx
            )
            emptyList()
        }.also { list ->
            log.info(
                "Fetched {} basic user records for userIds={}",
                list.size,
                userIds
            )
        }
    }

    suspend fun updateClubId(userId: Int, clubId: Int): Boolean {
        log.debug("Updating club ID for userId={} to clubId={}", userId, clubId)
        return try {
            DatabaseFactory.write {
                UsersTable.update({ UsersTable.id eq userId }) {
                    it[UsersTable.clubId] = clubId
                }
            }
            true
        } catch (e: Exception) {
            log.error("Error updating club ID for userId={}", userId, e)
            false
        }
    }

    suspend fun getAllIds(): List<Int> {
        log.debug("Fetching all user IDs")

        val cachedIds = redis.getJson<List<Int>>("allIds")
        if (cachedIds != null) {
            log.info("Found {} user IDs for all user IDs", cachedIds.size)
            return cachedIds
        } else {
            log.debug("No user IDs found in cache for all user IDs")
        }

        return try {
            DatabaseFactory.read {
                UsersTable
                    .selectAll()
                    .map { it[UsersTable.id].value }
            }
        } catch (e: Exception) {
            log.error("Error fetching all user IDs", e)
            emptyList()
        }.also { list ->
            log.info("Fetched {} user IDs", list.size)
            redis.setJson("allIds", list)
        }
    }

}
