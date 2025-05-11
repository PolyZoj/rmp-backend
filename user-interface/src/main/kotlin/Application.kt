package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.DataPayload
import ru.polyZoj.db.*
import common.exceptions.DuplicateFieldException
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.models.FriendshipStatus
import common.models.UserBasicInfo
import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import common.models.UserRegistration
import common.models.UserUpdatable
import ru.polyZoj.repositories.UserRepository


fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.module() {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    val log = logger<Application>()

    val ds = DataSourceConfig()
    DatabaseFactory.init(ds)

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val kafkaConsumer = createKafkaConsumer("user-interface-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf("user-requests"))

    val userRepository = UserRepository()

    suspend fun handleFriendshipPostRequest(
        request: String,
        data: DataPayload,
        status: FriendshipStatus?,
        conversationId: String,
        action: suspend (Int, Int, FriendshipStatus?) -> Boolean
    ) {
        log.info("Request received: $request")
        val userId = data.getParam<String>("user_id")
        val friendId = data.getParam<String>("friend_id")
        if (userId == null || friendId == null) {
            val err = DataPayload.error(
                status = HttpStatusCode.BadRequest,
                description = "Missing user ID or friend ID"
            )
            producerService.send("user-responses", conversationId, err)
        } else {
            val success = action(
                userId.toInt(),
                friendId.toInt(),
                status
            )
            if (success) {
                val resp = DataPayload.build("success") {
                    param("success", true)
                }
                producerService.send("user-responses", conversationId, resp)
            } else {
                val err = DataPayload.error(
                    status = HttpStatusCode.NotFound,
                    description = "User or friend not found"
                )
                producerService.send("user-responses", conversationId, err)
            }
        }
    }

    suspend fun getFriendRequestsHelper(userId: Int): List<UserBasicInfo> {
        log.info("Get friend requests for user ID: $userId")
        val idList = userRepository.getFriendshipRequests(userId)
        log.info("Friendship requests IDs: $idList")
        if (idList.isEmpty()) {
            return emptyList()
        }
        val userList = mutableListOf<UserBasicInfo>()
        for (id in idList) {
            val username = userRepository.findUsernameById(id)
            if (username != null) {
                userList.add(UserBasicInfo(id, username))
            }
        }
        return userList
    }

    suspend fun getFriendsHelper(userId: Int): List<UserBasicInfo> {
        val friendsIds = userRepository.getFriends(userId)
        val friendsInfo = userRepository.getUsersBasicInfo(friendsIds)
        return friendsInfo
    }

    suspend fun findFriendHelper(searchString: String): List<UserBasicInfo> {
        val friendsIds = userRepository.getAllIds()
        val friendsMatchedSubstring = userRepository.findUserIdsByUsernameSubstring(friendsIds, searchString)
        val friendsInfo = userRepository.getUsersBasicInfo(friendsMatchedSubstring)
        return friendsInfo
    }

    consumerService.startConsuming { conversationId, data ->
        log.info("Received message: $data")
        val command = data.message
        when (command) {
            "login" -> {
                log.info("Login command received, data: $data")
                val username = data.getParam<String>("username")
                val password = data.getParam<String>("password")
                var response: DataPayload
                val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Invalid credentials"
                    )
                if (username == null || password == null) {
                    log.warn("Missing username or password")
                    response = err
                } else {
                    log.info("Attempting to login user $username")
                    val userId = userRepository.login(username, password)
                    response = if (userId != null) {
                        log.info("User $username with user_id = $userId, logged in successfully")
                        DataPayload.build(userId.toString()) {
                            param("user_id", userId.toString())
                        }
                    } else {
                        err
                    }
                }
                log.info("sending response to user-responses: $response")
                producerService.send("user-responses", conversationId, response)
            }

            "findByUsername" -> {
                log.info("Find by username command received, data: $data")
                val username = data.getParam<String>("username")
                if (username == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing username"
                    )
                    producerService.send("user-responses", conversationId, err)
                    return@startConsuming
                } else {
                    val userId = userRepository.findByUsername(username)
                    val resp = if (userId != null) {
                        DataPayload.build(userId.toString()) {
                            param("user_id", userId.toString())
                        }
                    } else {
                        DataPayload.error(
                            status = HttpStatusCode.NotFound,
                            description = "User not found"
                        )
                    }
                    log.info("sending response to user-responses: $resp")
                    producerService.send("user-responses", conversationId, resp)
                }
            }

            "register" -> {
                log.info("Create user command received, data: $data")
                val reg = data.getParam<UserRegistration>("user_registration")
                if (reg == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Invalid registration data"
                    )
                    producerService.send("user-responses", conversationId, err)
                    return@startConsuming
                }
                try {
                    val newId = userRepository.createUser(reg)
                    val resp = DataPayload.build(newId.toString()) {
                        param("user_id", newId.toString())
                    }
                    log.info("User created successfully, sending response: $resp")
                    producerService.send("user-responses", conversationId, resp)
                    return@startConsuming

                } catch (e: IllegalArgumentException) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Invalid registration data: ${e.message}"
                    )
                    log.error("Error creating user: ${e.message}", e)
                    producerService.send("user-responses", conversationId, err)

                } catch (e: DuplicateFieldException) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.Conflict,
                        description = when (e.fieldName) {
                            "email" -> "That email is already registered."
                            "username" -> "That username is taken."
                            else -> "Duplicate field: ${e.fieldName}"
                        }
                    )
                    log.error("Error creating user: $e")
                    producerService.send("user-responses", conversationId, err)
                } catch (e: Exception) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.InternalServerError,
                        description = "Internal server error: ${e.message}"
                    )
                    log.error("Error creating user: ${e.message}", e)
                    producerService.send("user-responses", conversationId, err)
                }
            }

            "userInfo" -> {
                log.info("Get user DTO command received, data: $data")
                val userId = data.getParam<String>("user_id")
                val selfId = data.getParam<String>("self_id")
                if (userId == null || selfId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID or self ID"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val userDTO = userRepository.getUserDTO(userId.toInt())
                    val status = userRepository.getFriendshipStatus(
                        selfId = selfId.toInt(),
                        friendId = userId.toInt()
                    )
                    if (userDTO != null) {
                        val resp = DataPayload.build(userId) {
                            with(userDTO.user) {
                                param("user_id",        userId)
                                param("first_name",     firstName)
                                param("last_name",      lastName)
                                param("email",          email)
                                param("avatar_url",     avatarUrl)
                                param("is_admin",       isAdmin)
                                param("club_id",        clubId)
                            }
                            param("username",           userDTO.username)
                            param("weight",             userDTO.weight)
                            param("height",             userDTO.height)
                            param("birth_date",         userDTO.birthDate)
                            param("unit_system",        userDTO.unitSystem)
                            param("energy_system",      userDTO.energySystem)
                            param("health_goal",        userDTO.healthGoal)
                            param("daily_step_goal",    userDTO.dailyStepGoal)
                            param("water_intake_goal",  userDTO.waterIntakeGoal)
                            param("calorie_goal",       userDTO.calorieGoal)
                            param("sleep_goal",         userDTO.sleepGoal)
                            param("workouts_goal",      userDTO.workoutsGoal)
                            param("status",             status)

                        }
                        producerService.send("user-responses", conversationId, resp)
                    } else {
                        val err = DataPayload.error(
                            status = HttpStatusCode.NotFound,
                            description = "User not found"
                        )
                        producerService.send("user-responses", conversationId, err)
                    }
                }
            }

            "updateUserInfo" -> {
                log.info("Update user info command received, data: $data")
                val userId = data.getParam<String>("user_id")
                val userUpdatable = data.getParam<UserUpdatable>("user_data")
                if (userId == null || userUpdatable == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID or user data"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val success = userRepository.updateUser(userId.toInt(), userUpdatable)
                    if (success) {
                        val resp = DataPayload.build("success") {
                            param("success", true)
                        }
                        producerService.send("user-responses", conversationId, resp)
                    } else {
                        val err = DataPayload.error(
                            status = HttpStatusCode.NotFound,
                            description = "User not found"
                        )
                        producerService.send("user-responses", conversationId, err)
                    }
                }
            }

            "deleteUser" -> {
                log.info("Delete user command received, data: $data")
                val userId = data.getParam<String>("user_id")
                if (userId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val deleted = userRepository.deleteUser(userId.toInt())
                    if (deleted) {
                        val resp = DataPayload.build("success") {
                            param("success", true)
                        }
                        producerService.send("user-responses", conversationId, resp)
                    } else {
                        val err = DataPayload.error(
                            status = HttpStatusCode.NotFound,
                            description = "User not found"
                        )
                        producerService.send("user-responses", conversationId, err)
                    }
                }
            }

            "getFriendRequests" -> {
                log.info("Get friend requests, data: $data")
                val userId = data.getParam<String>("user_id")
                if (userId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val requests = getFriendRequestsHelper(userId.toInt())
                    log.info("Result of action: $requests")
                    val resp = if (requests.isEmpty()) {
                        DataPayload.build(userId) {
                            param("friend_requests", emptyList<String>())
                        }
                    } else {
                        DataPayload.build(userId) {
                            param("friend_requests", requests)
                        }
                    }
                    producerService.send("user-responses", conversationId, resp)
                }
            }

            "acceptFriendRequest" -> {
                log.info("Accept friend request, data: $data")
                handleFriendshipPostRequest(
                    request = "acceptFriendRequest",
                    data = data,
                    status = FriendshipStatus.ACCEPTED,
                    conversationId = conversationId,
                    action = userRepository::setFriendshipRequestStatus
                )
            }

            "denyFriendRequest" -> {
                log.info("Deny friend request, data: $data")
                handleFriendshipPostRequest(
                    request = "denyFriendRequest",
                    data = data,
                    status = FriendshipStatus.REJECTED,
                    conversationId = conversationId,
                    action = userRepository::setFriendshipRequestStatus
                )
            }

            "addFriendRequest" -> {
                log.info("Add friend request, data: $data")
                handleFriendshipPostRequest(
                    request = "addFriendRequest",
                    data = data,
                    status = FriendshipStatus.PENDING,
                    conversationId = conversationId,
                    action = userRepository::setFriendshipRequestStatus
                )
            }

            "removeFriend" -> {
                log.info("Remove friend, data: $data")
                handleFriendshipPostRequest(
                    request = "removeFriend",
                    data = data,
                    status = null,
                    conversationId = conversationId,
                    action = userRepository::removeFriendship
                )
            }

            "getFriendsList" -> {
                log.info("Get friends list, data: $data")
                val userId = data.getParam<String>("user_id")
                if (userId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val friendsInfoList = getFriendsHelper(userId.toInt())
                    val resp = if (friendsInfoList.isEmpty()) {
                        DataPayload.build(userId) {
                            param("friends", emptyList<String>())
                        }
                    } else {
                        DataPayload.build(userId) {
                            param("friends", friendsInfoList)
                        }
                    }
                    producerService.send("user-responses", conversationId, resp)
                }
            }

            "findFriend" -> {
                log.info("Find friend, data: $data")
                val userId = data.getParam<String>("user_id")
                val searchString = data.getParam<String>("find_username")
                if (userId == null || searchString == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID or search string"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val friendsInfo = findFriendHelper(searchString)
                    val resp = DataPayload.build(userId) {
                        param("possible_friend", friendsInfo)
                    }
                    producerService.send("user-responses", conversationId, resp)
                }
            }

            "updateClubId" -> {
                log.info("Update club id, data: $data")
                val userId = data.getParam<String>("user_id")
                val clubId = data.getParam<String>("club_id")
                if (userId == null || clubId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID or club ID"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val success = userRepository.updateClubId(userId.toInt(), clubId.toInt())
                    if (success) {
                        val resp = DataPayload.build("success") {
                            param("success", true)
                        }
                        producerService.send("user-responses", conversationId, resp)
                    } else {
                        val err = DataPayload.error(
                            status = HttpStatusCode.NotFound,
                            description = "User or club not found"
                        )
                        producerService.send("user-responses", conversationId, err)
                    }
                }
            }

            else -> {
                val err = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command: $command"
                )
                log.error("Unknown command: $command")
                producerService.send("user-responses", conversationId, err)
            }
        }
    }

}
