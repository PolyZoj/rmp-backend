package ru.polyZoj.repositories

import common.Level
import common.LogSender
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

class UserRepository(private val logger: LogSender) {

    /**
     * Cache keys:
     * - userIdOf:username:<username>
     * - usernameOf:userId:<userId>
     * - passwordOf:userId:<userId>
     * - userDTOOf:userId:<userId>
     * - friendshipRequestsOf:userId:<selfId>
     * - friendsOf:userId:<userId>
     * - allIds
     */
    private val redis = RedisFactory.sync

    fun logInfo (ctx: String, msg: String) = logger.log("user-interface", Level.INFO,  msg, "UserRepository: $ctx")
    fun logError(ctx: String, msg: String) = logger.log("user-interface", Level.ERROR, msg, "UserRepository: $ctx")
    fun logDebug(ctx: String, msg: String) = logger.log("user-interface", Level.DEBUG, msg, "UserRepository: $ctx")
    fun logWarn (ctx: String, msg: String) = logger.log("user-interface", Level.WARN,  msg, "UserRepository: $ctx")
    fun logTrace(ctx: String, msg: String) = logger.log("user-interface", Level.TRACE, msg, "UserRepository: $ctx")

    /** Look up a user’s id by username */
    suspend fun findByUsername(username: String): Int? {
        val ctx = "findByUsername"
        logDebug(ctx,"Entering $ctx with username={$username}")

        val cachedId = redis.getJson<Int>("userIdOf:username:$username")
        if (cachedId != null) {
            logInfo(ctx, "HIT: Found id=${cachedId} of $username in cache")
            return cachedId
        } else {
            logWarn(ctx, "MISS: id of $username not found in cache")
        }

        val result: Int? = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { UserCredentialsTable.username eq username }
                .limit(1)
                .map { it[UserCredentialsTable.userId].value }
                .singleOrNull()
        }
        if (result != null) {
            logInfo(ctx, "Found id=${result} of $username in database")
            logDebug(ctx, "Setting cache for userIdOf:username:$username")
            redis.setJson("userIdOf:username:$username", result, 600)
        } else {
            logError(ctx, "User not found in database")
        }
        return result
    }

    suspend fun findUsernameById(userId: Int): String? {
        val ctx = "findUsernameById"
        logDebug(ctx, "Entering $ctx with userId={$userId}")

        val cachedUsername = redis.getJson<String>("usernameOf:userId:$userId")
        if (cachedUsername != null) {
            logInfo(ctx, "HIT: Found username=${cachedUsername} of id=$userId in cache")
            return cachedUsername
        } else {
            logWarn(ctx, "MISS: username of userId=$userId not found in cache")
        }

        val result = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.username)
                .where { UserCredentialsTable.userId eq userId }
                .limit(1)
                .map { it[UserCredentialsTable.username] }
                .singleOrNull()
        }
        if (result != null) {
            logInfo(ctx, "Found username=${result} of id=$userId in database")
            logDebug(ctx, "Setting cache for usernameOf:userId:$userId")
            redis.setJson("usernameOf:userId:$userId", result, 600)
        } else {
            logError(ctx, "Username not found in database for id=$userId")
        }
        return result
    }

    /** Return userId if credentials match **/
    suspend fun login(username: String, password: String): Int? {
        val ctx = "login"
        logDebug(ctx, "Entering $ctx with username={$username}")
        val cachedId = redis.getJson<Int>("userIdOf:username:$username")
        val cachedUsername = redis.getJson<String>("usernameOf:userId:$cachedId")
        val cachedPassword = redis.getJson<String>("passwordOf:userId:$cachedId")

        if (cachedUsername != null && cachedPassword != null && cachedId != null) {
            logInfo(ctx, "HIT: Found credentials in cache for $username")
            if (cachedUsername == username && cachedPassword == password) {
                logInfo(ctx, "Login successful for $username using cache (userId=$cachedId)")
                return cachedId
            } else {
                logWarn(ctx, "Invalid cached credentials for $username")
            }
        } else {
            logWarn(ctx, "MISS: Credentials for $username not found in cache")
        }

        val userId: Int? = DatabaseFactory.read {
            UserCredentialsTable.select(UserCredentialsTable.userId)
                .where { (UserCredentialsTable.username eq username) and (UserCredentialsTable.password eq password) }
                .limit(1)
                .map { it[UserCredentialsTable.userId].value }
                .singleOrNull()
        }

        if (userId != null) {
            logInfo(ctx, "Login successful for $username in database (userId=$userId)")
            logDebug(ctx, "Setting caches for userId=$userId")
            redis.setJson("userIdOf:username:$username", userId, 600)
            redis.setJson("usernameOf:userId:$userId", username, 600)
            redis.setJson("passwordOf:userId:$userId", password, 600)
        } else {
            logError(ctx, "Login failed for $username – credentials not found in database")
        }
        return userId
    }

    /** Create a brand‐new user (all tables) and return their new user_id */
    @OptIn(ExperimentalTime::class)
    suspend fun createUser(reg: UserRegistration): Int {
        val ctx = "createUser"
        logDebug(ctx, "Entering $ctx with username={${reg.username}}")
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
                logDebug(ctx, "Inserted into UsersTable (userId=$userId)")

                // 2) credentials
                UserCredentialsTable.insert {
                    it[UserCredentialsTable.userId] = userId
                    it[UserCredentialsTable.username] = reg.username
                    it[UserCredentialsTable.password] = reg.password
                }
                logDebug(ctx, "Inserted into UserCredentialsTable (userId=$userId)")

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
                logDebug(ctx, "Inserted into UserParametersTable (userId=$userId)")

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
                    logTrace(ctx, "Inserted/Found PrimaryHealthGoalsTable row (userId=$userId)")
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
                logDebug(ctx, "Inserted into UserPreferencesTable (userId=$userId)")
                userId
            }

            logDebug(ctx, "Setting caches for userId=$newUserId")
            redis.setJson("usernameOf:userId:${newUserId}", reg.username, 600)
            redis.setJson("userIdOf:username:${reg.username}", newUserId, 600)
            redis.setJson("passwordOf:userId:${newUserId}", reg.password, 600)
            redis.del("allIds")
            logInfo(ctx, "User registered successfully (userId=$newUserId)")
            return newUserId
        } catch (e: ExposedSQLException) {
            logError(ctx, "Error registering user '${reg.username}': ${e.message}")
            if (e.cause is PSQLException && (e.cause as PSQLException).sqlState == "23505") {
                val msg = e.cause!!.message ?: ""
                when {
                    "users_email_key" in msg -> {
                        logWarn(ctx, "Duplicate email during registration: ${reg.email}")
                        throw DuplicateFieldException("email")
                    }
                    "users_credentials_user_name_key" in msg -> {
                        logWarn(ctx, "Duplicate username during registration: ${reg.username}")
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
        val ctx = "getUserDTO"
        logDebug(ctx, "Entering $ctx with userId={$userId}")

        val cachedUserDTO = redis.getJson<UserDTO>("userDTOOf:userId:$userId")
        if (cachedUserDTO != null) {
            logInfo(ctx, "HIT: Found UserDTO of id=$userId in cache")
            return cachedUserDTO
        } else {
            logWarn(ctx, "MISS: UserDTO of id=$userId not found in cache")
        }

        val userDTO: UserDTO? = try {
            DatabaseFactory.read {
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
                    .leftJoin(PrimaryHealthGoalsTable)
                    .selectAll()
                    .where { UsersTable.id eq userId }
                    .limit(1)
                    .map { row ->
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
                            healthGoal      = row[PrimaryHealthGoalsTable.goalName],
                            dailyStepGoal   = row[UserPreferencesTable.dailyStepGoal],
                            waterIntakeGoal = row[UserPreferencesTable.waterIntakeGoal],
                            calorieGoal     = row[UserPreferencesTable.calorieGoal],
                            sleepGoal       = row[UserPreferencesTable.sleepGoal],
                            workoutsGoal    = row[UserPreferencesTable.workoutsGoal]
                        )
                    }
                    .singleOrNull()
            }
        } catch (e: Exception) {
            logError(ctx, "Error fetching UserDTO for userId=$userId: ${e.message}")
            null
        }

        if (userDTO != null) {
            logInfo(ctx, "Fetched UserDTO successfully for userId=$userId")
            logDebug(ctx, "Setting cache for userDTOOf:userId:$userId")
            redis.setJson("userDTOOf:userId:$userId", userDTO, 600)
        } else {
            logError(ctx, "UserDTO not found in database for userId=$userId")
        }
        return userDTO
    }

    /** Delete user and all related data. Returns true if any rows were deleted. */
    suspend fun deleteUser(userId: Int): Boolean {
        val ctx = "deleteUser"
        logDebug(ctx, "Entering $ctx with userId={$userId}")

        return try {
            val deletedCount = DatabaseFactory.write {
                UsersTable.deleteWhere { UsersTable.id eq userId }
            }

            if (deletedCount > 0) {
                logInfo(ctx, "User deletion succeeded for userId=$userId (rows=$deletedCount)")
                logDebug(ctx, "Deleting cache for userId=$userId")
                redis.del(
                    "userDTOOf:userId:$userId",
                    "usernameOf:userId:$userId",
                    "passwordOf:userId:$userId",
                    "allIds",
                    "friendshipRequestsOf:userId:$userId",
                    "friendsOf:userId:$userId"
                )
                true
            } else {
                logError(ctx, "User not found to delete for userId=$userId")
                false
            }
        } catch (e: Exception) {
            logError(ctx, "Failed to delete user data for userId=$userId: ${e.message}")
            false
        }
    }

    /** Update user data. Returns true if any rows were updated. */
    suspend fun updateUser(userId: Int, userUpdatable: UserUpdatable): Boolean {
        val ctx = "updateUser"
        logDebug(ctx, "Entering $ctx with userId={$userId}")
        return try {
            DatabaseFactory.write {
                userUpdatable.avatarUrl?.let { a ->
                    UsersTable.update({ UsersTable.id eq userId }) { it[avatarUrl] = a }
                }
                userUpdatable.height?.let { h ->
                    UserParametersTable.update({ UserParametersTable.userId eq userId }) { it[height] = h }
                }
                userUpdatable.weight?.let { w ->
                    UserParametersTable.update({ UserParametersTable.userId eq userId }) { it[weight] = w }
                }
                userUpdatable.healthGoal?.let { h ->
                    val healthId = PrimaryHealthGoalsTable
                        .select(PrimaryHealthGoalsTable.goalName eq h)
                        .map { it[PrimaryHealthGoalsTable.healthGoalId] }
                        .singleOrNull()
                        ?: PrimaryHealthGoalsTable.insert { it[goalName] = h }[PrimaryHealthGoalsTable.healthGoalId]
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) { it[healthGoalId] = healthId }
                }
                userUpdatable.dailyStepGoal?.let { d ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) { it[dailyStepGoal] = d }
                }
                userUpdatable.waterIntakeGoal?.let { w ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) { it[waterIntakeGoal] = w }
                }
                userUpdatable.calorieGoal?.let { c ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) { it[calorieGoal] = c }
                }
                userUpdatable.sleepGoal?.let { s ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) { it[sleepGoal] = s }
                }
                userUpdatable.workoutsGoal?.let { w ->
                    UserPreferencesTable.update({ UserPreferencesTable.userId eq userId }) { it[workoutsGoal] = w }
                }
            }
            logInfo(ctx, "User data updated successfully for userId=$userId")
            logDebug(ctx, "Deleting cache for userId=$userId")
            redis.del("userDTOOf:userId:$userId")
            true
        } catch (e: Exception) {
            logError(ctx, "Error updating user data for userId=$userId: ${e.message}")
            false
        }
    }

    /** Get friendship status related with userId and friendId with statuses from `FriendshipStatusFrontEnd` */
    suspend fun getFriendshipStatus(selfId: Int, friendId: Int): FriendshipStatusFrontEnd? {
        val ctx = "getFriendshipStatus"
        logDebug(ctx, "Entering $ctx with selfId={$selfId}, friendId={$friendId}")

        if (selfId == friendId) {
            return FriendshipStatusFrontEnd.SELF
        }

        return try {
            val status = DatabaseFactory.read {
                FriendshipsTable.selectAll()
                    .where {
                        ((FriendshipsTable.userId eq selfId) and (FriendshipsTable.friendId eq friendId)) or
                        ((FriendshipsTable.userId eq friendId) and (FriendshipsTable.friendId eq selfId))
                    }.singleOrNull()
            }.let { row ->
                if (row == null) {
                    FriendshipStatusFrontEnd.NOT_YOUR_FRIEND
                } else {
                    val dbStatus = StaticLookups.nameForFriendshipStatusId(row[FriendshipsTable.friendshipStatus])
                    when (dbStatus) {
                        FriendshipStatus.ACCEPTED -> FriendshipStatusFrontEnd.YOUR_FRIEND
                        FriendshipStatus.PENDING -> {
                            if (row[FriendshipsTable.userId].value == selfId) FriendshipStatusFrontEnd.INVITE_SENT
                            else FriendshipStatusFrontEnd.NOT_YOUR_FRIEND
                        }
                        else -> FriendshipStatusFrontEnd.NOT_YOUR_FRIEND
                    }
                }
            }
            logInfo(ctx, "Friendship status determined: $status for selfId=$selfId and friendId=$friendId")
            status
        } catch (e: Exception) {
            logError(ctx, "Error fetching friendship status for selfId=$selfId and friendId=$friendId: ${e.message}")
            null
        }
    }

    /** Get all friendships for a user with a given status */
    suspend fun getFriendshipsWhereStatus(userId: Int, status: FriendshipStatus): List<Int> {
        val statusId = StaticLookups.idFor(status)
        return DatabaseFactory.read {
            when (status) {
                FriendshipStatus.PENDING -> {
                    FriendshipsTable.selectAll()
                        .where { // we're looking for friendship requests for the given userId
                            (FriendshipsTable.friendId eq userId) and
                            (FriendshipsTable.friendshipStatus eq statusId)
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
                    FriendshipsTable.selectAll()
                        .where {
                            ((FriendshipsTable.userId eq userId) or
                                    (FriendshipsTable.friendId eq userId)) and
                                    (FriendshipsTable.friendshipStatus eq statusId)
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
        val ctx = "getFriendshipRequests"
        logDebug(ctx, "Entering $ctx with userId={$userId}")

        val cachedRequests = redis.getJson<List<Int>>("friendshipRequestsOf:userId:$userId")
        if (cachedRequests != null) {
            logInfo(ctx, "HIT: Found friendship requests for userId=$userId in cache (size=${cachedRequests.size})")
            return cachedRequests
        } else {
            logWarn(ctx, "MISS: Friendship requests for userId=$userId not in cache")
        }

        return getFriendshipsWhereStatus(userId, FriendshipStatus.PENDING).also {
            logInfo(ctx, "Found ${it.size} friendship requests for userId=$userId from database")
            logDebug(ctx, "Setting cache for friendshipRequestsOf:userId:$userId")
            redis.setJson("friendshipRequestsOf:userId:$userId", it, 600)
        }
    }

    fun deleteFriendshipCache(uId: Int, fId: Int) {
        val ctx = "deleteFriendshipCache"
        logDebug(ctx, "Entering $ctx for uId=$uId and fId=$fId – deleting related cache keys")
        redis.del(
            "friendshipRequestsOf:userId:$fId",
            "friendshipRequestsOf:userId:$uId",
            "friendsOf:userId:$fId",
            "friendsOf:userId:$uId"
        )
    }

    /** Set friendship request status. Returns success boolean */
    suspend fun setFriendshipRequestStatus(uId: Int, fId: Int, status: FriendshipStatus?): Boolean {
        val ctx = "setFriendshipRequestStatus"
        logDebug(ctx, "Entering $ctx with uId={$uId}, fId={$fId}, status={$status}")

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
                            logError(ctx, "No friendship request found to update for fId=$fId and uId=$uId")
                            return@write false
                        } else {
                            logInfo(ctx, "Friendship request status updated to $status for fId=$fId and uId=$uId")
                            deleteFriendshipCache(uId, fId)
                            return@write true
                        }
                    }
                    else -> {
                        val friendshipExists = FriendshipsTable.selectAll()
                            .where{
                                ((FriendshipsTable.userId eq uId) and (FriendshipsTable.friendId eq fId)) or
                                        ((FriendshipsTable.userId eq fId) and (FriendshipsTable.friendId eq uId))
                            }.count() > 0

                        if (!friendshipExists) {
                            FriendshipsTable.insert {
                                it[userId] = uId
                                it[friendId] = fId
                                it[friendshipStatus] = statusId
                            }
                            logInfo(ctx, "Friendship request created with status=$status between uId=$uId and fId=$fId")
                        } else {
                            FriendshipsTable.update({
                                (FriendshipsTable.userId eq uId) and
                                        (FriendshipsTable.friendId eq fId) or
                                        (FriendshipsTable.userId eq fId) and
                                        (FriendshipsTable.friendId eq uId)
                            }) {
                                it[friendshipStatus] = statusId
                            }
                            logInfo(ctx, "Friendship status updated to $status between uId=$uId and fId=$fId")
                        }
                        deleteFriendshipCache(uId, fId)
                        true
                    }
                }
            }
        } catch (e: Exception) {
            logError(ctx, "Error setting friendship status for uId=$uId and fId=$fId: ${e.message}")
            false
        }
    }

    /** Removes friendship row.
     * status not used, but necessary for the function signature
     * */
    suspend fun removeFriendship(userId: Int, friendId: Int, status: FriendshipStatus? = null): Boolean {
        val ctx = "removeFriendship"
        logDebug(ctx, "Entering $ctx with userId={$userId}, friendId={$friendId}")
        return try {
            DatabaseFactory.write {
                FriendshipsTable.deleteWhere {
                    (FriendshipsTable.userId eq userId) and
                    (FriendshipsTable.friendId eq friendId) or
                    (FriendshipsTable.userId eq friendId) and
                    (FriendshipsTable.friendId eq userId)
                }
            }
            logInfo(ctx, "Friendship removed between userId=$userId and friendId=$friendId")
            deleteFriendshipCache(userId, friendId)
            true
        } catch (e: Exception) {
            logError(ctx, "Error removing friendship between userId=$userId and friendId=$friendId: ${e.message}")
            false
        }
    }

    /** Get all friends for a user (status accepted) */
    suspend fun getFriends(userId: Int): List<Int> {
        val ctx = "getFriends"
        logDebug(ctx, "Entering $ctx with userId={$userId}")

        val cachedFriends = redis.getJson<List<Int>>("friendsOf:userId:$userId")
        if (cachedFriends != null) {
            logInfo(ctx, "HIT: Found friends for userId=$userId in cache (size=${cachedFriends.size})")
            return cachedFriends
        } else {
            logWarn(ctx, "MISS: Friends for userId=$userId not in cache")
        }

        return getFriendshipsWhereStatus(userId, FriendshipStatus.ACCEPTED).also {
            logInfo(ctx, "Found ${it.size} friends for userId=$userId from database")
            logDebug(ctx, "Setting cache for friendsOf:userId:$userId")
            redis.setJson("friendsOf:userId:$userId", it, 600)
        }
    }

    /**
     * Find user IDs by username substring.
     * Returns a list of user IDs that match the given substring.
     * The search is case-insensitive.
     */
    suspend fun findUserIdsByUsernameSubstring(list: List<Int>, substring: String): List<Int> {
        val ctx = "findUserIdsByUsernameSubstring"
        logDebug(ctx, "Entering $ctx with substring='{$substring}' and list size=${list.size}")
        return try {
            val result = DatabaseFactory.read {
                UserCredentialsTable.select(UserCredentialsTable.userId)
                    .where {
                        (UserCredentialsTable.userId inList list) and
                        // LOWER(username) LIKE '%lower(substring)%'
                        (UserCredentialsTable.username.lowerCase() like "%${substring.lowercase()}%")
                    }
                    .map { row ->
                        row[UserCredentialsTable.userId].value
                    }
            }
            logInfo(ctx, "Found ${result.size} matching user IDs for substring='$substring'")
            result
        } catch (e: Exception) {
            logError(ctx, "Error querying user IDs by substring='$substring': ${e.message}")
            emptyList()
        }
    }

    /** Get basic info for a list of users (userId, username, avatarUrl) */
    suspend fun getUsersBasicInfo(userIds: List<Int>): List<UserBasicInfo> {
        val ctx = "getUsersBasicInfo"
        logDebug(ctx, "Entering $ctx with userIds size=${userIds.size}")
        return try {
            val list = DatabaseFactory.read {
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
            }
            logInfo(ctx, "Fetched ${list.size} basic user records for provided IDs")
            list
        } catch (e: Exception) {
            logError(ctx, "Error fetching basic info for userIds: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateClubId(userId: Int, clubId: Int): Boolean {
        val ctx = "updateClubId"
        logDebug(ctx, "Entering $ctx with userId={$userId}, clubId={$clubId}")
        return try {
            DatabaseFactory.write {
                UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.clubId] = clubId }
            }
            logInfo(ctx, "Club ID updated to $clubId for userId=$userId")
            redis.del("userDTOOf:userId:$userId")
            true
        } catch (e: Exception) {
            logError(ctx, "Error updating club ID for userId=$userId: ${e.message}")
            false
        }
    }

    suspend fun getAllIds(): List<Int> {
        val ctx = "getAllIds"
        logDebug(ctx, "Entering $ctx")

        val cachedIds = redis.getJson<List<Int>>("allIds")
        if (cachedIds != null) {
            logInfo(ctx, "HIT: Retrieved ${cachedIds.size} user IDs from cache")
            return cachedIds
        } else {
            logWarn(ctx, "MISS: All user IDs not found in cache")
        }

        return try {
            val list = DatabaseFactory.read {
                UsersTable.selectAll().map { it[UsersTable.id].value }
            }
            logInfo(ctx, "Fetched ${list.size} user IDs from database")
            logDebug(ctx, "Setting cache for allIds")
            redis.setJson("allIds", list, 60)
            list
        } catch (e: Exception) {
            logError(ctx, "Error fetching all user IDs: ${e.message}")
            emptyList()
        }.also { list ->
            log.info("Fetched {} user IDs", list.size)
            redis.setJson("allIds", list, 60)
        }
    }

}
