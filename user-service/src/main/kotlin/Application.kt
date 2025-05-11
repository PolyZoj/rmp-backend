package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.DataPayload
import ru.polyZoj.models.User
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import common.exceptions.ArgumentNotFoundException
import common.kafka.KafkaConfig
import common.models.EnergySystem
import common.models.FriendshipStatusFrontEnd
import common.models.UnitSystem
import common.models.UserBasicInfo
import common.models.UserRegistration
import common.models.UserUpdatable
import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.polyZoj.configs.JwtConfig
import ru.polyZoj.configs.generateToken
import java.time.LocalDate
import java.util.concurrent.TimeUnit

val pendingResponses = ConcurrentHashMap<String, CompletableFuture<DataPayload>>()

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.module() {
    val log = logger<Application>()

    val jwtConfig = JwtConfig.fromConfig(environment.config)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists("user-requests", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("user-responses", 1, 3.toShort())

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val responseConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf("user-responses"))
    responseConsumer.startConsuming { conversationId, message ->
        log.info("Received response: $message")
        pendingResponses[conversationId]?.complete(message)
        pendingResponses.remove(conversationId)
    }

    fun handleUserConsumerCommand(
        data: DataPayload,
        conversationId: String,
        argToCheck: String = "user_id",
        onSuccess: (DataPayload) -> DataPayload,
        errorStatusCode: HttpStatusCode,
        errorDescription: String = "Error processing request"
    ) {
        val command = data.message
        log.info("Received command: $command, data: $data")
        val checkable = data.getParam<String>(argToCheck)
        if (checkable == null) {
            val msg = DataPayload.error(
                status = HttpStatusCode.BadRequest,
                description = "Invalid credentials"
            )
            producerService.send("user-gateway-responses", conversationId, msg)
            return
        }

        val requestPayload = DataPayload(command, data.params)
        val future = CompletableFuture<DataPayload>()
        pendingResponses[conversationId] = future

        log.info("sending request to user-requests: $requestPayload")
        producerService.send("user-requests", conversationId, requestPayload)

        future.orTimeout(5, TimeUnit.SECONDS).whenComplete { response, error ->
            if (error != null || response.params.isEmpty() || response.message == "error") {
                log.warn("Received from user-interface: $response")
                val msg = if (response.message == "error") {
                    response
                } else {
                    DataPayload.error(
                        status = errorStatusCode,
                        description = errorDescription
                    )
                }
                producerService.send("user-gateway-responses", conversationId, msg)
                return@whenComplete
            }
            log.info("Received from user-interface: $response")
            val msg = onSuccess(response)
            log.info("sending request to user-gateway-responses: $msg")
            producerService.send("user-gateway-responses", conversationId, msg)
        }
    }

    fun friendshipStatusHelper(
        data: DataPayload,
        conversationId: String,
    ) {
        val friendId = data.getParam<String>("friend_id")
        if (friendId == null) {
            val msg = DataPayload.error(
                status = HttpStatusCode.BadRequest,
                description = "Not found friend_id in request"
            )
            producerService.send("user-gateway-responses", conversationId, msg)
            return
        }
        handleUserConsumerCommand(
            data,
            conversationId,
            onSuccess = { response ->
                val success = response.message
                val msg = if (success == "success") {
                    DataPayload.build("success") {
                        param("success", true)
                    }
                } else {
                    DataPayload.error(
                        status = HttpStatusCode.InternalServerError,
                        description = "Message from user-interface did not contain success: $success"
                    )
                }
                msg
            },
            errorStatusCode = HttpStatusCode.InternalServerError,
            errorDescription = "Error accepting friend request"
        )
    }

    val authConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf("auth-requests"))
    authConsumer.startConsuming { conversationId, data ->
        log.info("Received auth request: $data")
        val command = data.message
        when (command) {
            /**
             * Needs username in [0], password in [1]
             * Returns userId in [0], token in [1]
             */
            "login" -> {
                val username = data.getParam<String>("username")
                val password = data.getParam<String>("password")
                log.info("login, username: $username, password: $password")
                if (username == null || password == null) {
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Invalid credentials"
                    )
                    producerService.send("auth-responses", conversationId, msg)
                    return@startConsuming
                }

                val requestPayload = DataPayload.build("login") {
                    param("username", username)
                    param("password", password)
                }
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, requestPayload)

                future.orTimeout(5, TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null || response.params.isEmpty() || response.message == "error") {
                        log.warn("Received from user-interface: $response")
                        var msg: DataPayload?
                        if (response.message == "error") {
                            msg = response
                        } else {
                            msg = DataPayload.error(
                                status = HttpStatusCode.Unauthorized,
                                description = "Invalid credentials"
                            )
                        }
                        producerService.send("auth-responses", conversationId, msg)
                        return@whenComplete
                    }
                    log.info("Received from user-interface: $response")
                    val userId = response.getParam<String>("user_id")!!
                    val token = generateToken(User(userId, username, password), jwtConfig)
                    val msg = DataPayload.build(userId) {
                        param("user_id", userId)
                        param("token", token)
                    }
                    log.info("sending request to auth-responses: $msg")
                    producerService.send("auth-responses", conversationId, msg)
                }
            }

            /**
             * Needs `UserRegistration` properties in [0..n]
             * Returns userId in [0] and token in [1]
             */
            "register" -> {
                var userRegistration: UserRegistration?
                try {
                    userRegistration = UserRegistration(
                        username = data.getParam<String>("username") ?:
                            throw ArgumentNotFoundException("username"),
                        password = data.getParam<String>("password") ?:
                            throw ArgumentNotFoundException("password"),
                        firstName = data.getParam<String>("first_name") ?:
                            throw ArgumentNotFoundException("first_name"),
                        lastName = data.getParam<String>("last_name") ?:
                            throw ArgumentNotFoundException("last_name"),
                        email = data.getParam<String>("email") ?:
                            throw ArgumentNotFoundException("email"),
                        avatarUrl = data.getParam<String>("avatar_url"),
                        weight = data.getParam<Float>("weight") ?:
                            throw ArgumentNotFoundException("weight"),
                        height = data.getParam<Short>("height") ?:
                            throw ArgumentNotFoundException("height"),
                        birthDate = data.getParam<LocalDate>("birth_date") ?:
                            throw ArgumentNotFoundException("birth_date"),
                        unitSystem = data.getParam<String>("unit_system")?.let { raw ->
                            try {
                                UnitSystem.valueOf(raw.uppercase())
                            } catch (e: IllegalArgumentException) {
                                throw IllegalArgumentException("Invalid unit system: $raw")
                            }
                        } ?: throw ArgumentNotFoundException("unit_system"),
                        energySystem = data.getParam<String>("energy_system")?.let { raw ->
                            try {
                                EnergySystem.valueOf(raw.uppercase())
                            } catch (e: IllegalArgumentException) {
                                throw IllegalArgumentException("Invalid energy system: $raw")
                            }
                        } ?: throw ArgumentNotFoundException("energy_system"),

                        healthGoal = data.getParam<String>("health_goal"),
                        dailyStepGoal = data.getParam<Int>("daily_step_goal"),
                        waterIntakeGoal = data.getParam<Int>("water_intake_goal"),
                        calorieGoal = data.getParam<Short>("calorie_goal"),
                        sleepGoal = data.getParam<Float>("sleep_goal"),
                        workoutsGoal = data.getParam<Short>("workouts_goal"),
                    )
                } catch (e: Exception) {
                    log.warn("Error registering user: $e")
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = e.message ?: "Invalid registration data"
                    )
                    producerService.send("auth-responses", conversationId, msg)
                    return@startConsuming
                }
                val requestPayload = DataPayload.build("register") {
                    param("user_registration", userRegistration)
                }
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, requestPayload)

                future.orTimeout(5, TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null
                        || response.message == "error"
                        || response.getParam<String>("user_id") == null
                        ) {
                        log.warn("Received from user-interface: $response")
                        var msg: DataPayload?
                        if (response.message == "error") {
                            msg = response
                        } else {
                            msg = DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Error registering user"
                            )
                        }
                        producerService.send("auth-responses", conversationId, msg)
                        return@whenComplete
                    }
                    log.info("Received from user-interface: $response")
                    val userId = response.getParam<String>("user_id")!!
                    val token = generateToken(User(userId, userRegistration.username, userRegistration.password), jwtConfig)
                    val msg = DataPayload.build(userId) {
                        param("user_id", userId)
                        param("token", token)
                    }
                    log.info("sending request to auth-responses: $msg")
                    producerService.send("auth-responses", conversationId, msg)
                }

            }

            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                log.warn("Unknown command: $command" ,"\n", "sending to auth-responses: $msg")
                producerService.send("auth-responses", conversationId, msg)
            }
        }
    }

    val userConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf("user-gateway-requests"))
    userConsumer.startConsuming { conversationId, data ->
        log.info("Received user request: $data")
        val command = data.message
        when (command) {

            /** Needs userId and selfId, returns flattened userDTO + status */
            "userInfo" -> {
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    onSuccess = { response ->
                        // only checking some params, they are a lot
                        val userId = response.getParam<String>("user_id")
                            ?: throw IllegalArgumentException("user_id not found in response")
                        val username = response.getParam<String>("username")
                            ?: throw IllegalArgumentException("username not found in response")
                        val status = response.getParam<FriendshipStatusFrontEnd>("status")
                            ?: throw IllegalArgumentException("status not found in response")
                        response
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving user"
                )
            }

            /** Needs username, returns userId */
            "findByUsername" -> {
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    argToCheck = "username",
                    onSuccess = { response ->
                        val userId = response.getParam<String>("user_id")
                        DataPayload.build("success") {
                            param("user_id", userId)
                        }
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving user"
                )
            }

            /** Needs userId and UserUpdatable, returns success */
            "updateUserInfo" -> {
                var userUpdatable: UserUpdatable?
                try {
                    userUpdatable = UserUpdatable(
                        avatarUrl = data.getParam<String?>("avatar_url"),
                        weight = data.getParam<Float?>("weight"),
                        height = data.getParam<Short?>("height"),
                        healthGoal = data.getParam<String?>("health_goal"),
                        dailyStepGoal = data.getParam<Int?>("daily_step_goal"),
                        waterIntakeGoal = data.getParam<Int?>("water_intake_goal"),
                        calorieGoal = data.getParam<Short?>("calorie_goal"),
                        sleepGoal = data.getParam<Float?>("sleep_goal"),
                        workoutsGoal = data.getParam<Short?>("workouts_goal")
                    )
                } catch (e: IllegalArgumentException) {
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = e.message ?: "Invalid update data"
                    )
                    producerService.send("user-gateway-responses", conversationId, msg)
                    return@startConsuming
                }
                val dataPayload = DataPayload.build("updateUserInfo") {
                    param("user_id", data.getParam<String>("user_id"))
                    param("user_data", userUpdatable)
                }
                handleUserConsumerCommand(
                    dataPayload,
                    conversationId,
                    onSuccess = { response ->
                        val success = response.message
                        val msg = if (success == "success") {
                            DataPayload.build("success") {
                                param("success", true)
                            }
                        } else {
                            DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Message from user-interface did not contain success: $success"
                            )
                        }
                        msg
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error deleting user"
                )
            }

            /** Needs userId, returns success */
            "deleteUser" -> {
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    onSuccess = { response ->
                        val success = response.message
                        val msg = if (success == "success") {
                            DataPayload.build("success") {
                                param("success", true)
                            }
                        } else {
                            DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Message from user-interface did not contain success: $success"
                            )
                        }
                        msg
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error deleting user"
                )
            }

            /** Needs userId, returns List<Pair<Int, String>> */
            "getFriendRequests" -> {
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    onSuccess = { response ->
                        val friendRequests = response.getParam<List<Pair<Int, String>>>("friend_requests")
                            ?: throw IllegalArgumentException("friend_requests not found in response")
                        DataPayload.build("success") {
                            param("friend_requests", friendRequests)
                        }
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving friend requests"
                )
            }

            /** Needs userId, friendId, returns success */
            "acceptFriendRequest" -> {
                friendshipStatusHelper(
                    data,
                    conversationId
                )
            }

            /** Needs userId, friendId, returns success */
            "denyFriendRequest" -> {
                friendshipStatusHelper(
                    data,
                    conversationId
                )
            }

            /** Needs userId, friendId, returns success */
            "addFriendRequest" -> {
                friendshipStatusHelper(
                    data,
                    conversationId
                )
            }

            /** Needs userId, friendId, returns success */
            "removeFriend" -> {
                friendshipStatusHelper(
                    data,
                    conversationId
                )
            }

            /** Needs userId, returns List<UserBasicInfo> */
            "getFriendsList" -> {
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    onSuccess = { response ->
                        val friendsList = response.getParam<List<UserBasicInfo>>("friends")
                            ?: throw IllegalArgumentException("friends not found in response")
                        DataPayload.build("success") {
                            param("friends", friendsList)
                        }
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving friends list"
                )
            }

            /** Needs userId, find_username String, returns possible-friend List<UserBasicInfo> */
            "findFriend" -> {
                val findUsername = data.getParam<String>("find_username")
                if (findUsername == null) {
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Not found find_username in request"
                    )
                    producerService.send("user-gateway-responses", conversationId, msg)
                    return@startConsuming
                }
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    onSuccess = { response ->
                        val possibleFriends = response.getParam<List<UserBasicInfo>>("possible_friends")
                            ?: throw IllegalArgumentException("possible_friends not found in response")
                        DataPayload.build("success") {
                            param("possible_friends", possibleFriends)
                        }
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving possible friends"
                )
            }



            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                log.warn("Unknown command: $command" ,"\n", "sending to user-gateway-responses: $msg")
                producerService.send("user-gateway-responses", conversationId, msg)
            }
        }
    }

    val clubConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf("club-user-bridge"))
    clubConsumer.startConsuming { conversationId, data ->
        log.info("Received club request: $data")
        val command = data.message
        when (command) {
            /** Needs userId and clubId, returns success */
            "updateClubId" -> {
                val userId = data.getParam<Int>("user_id")
                val clubId = data.getParam<Int>("club_id")
                if (userId == null || clubId == null) {
                    producerService.send("club-user-bridge", conversationId, DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user_id or club_id"
                    ))
                    return@startConsuming
                }

                val requestPayload = DataPayload.build(command) {
                    param("user_id", userId.toString())
                    param("club_id", clubId.toString())
                }
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, requestPayload)

                future.orTimeout(5, TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null || response.params.isEmpty() || response.message == "error") {
                        log.warn("Received from user-interface: $response")
                        val msg = if (response.message == "error") {
                            response
                        } else {
                            DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Error updating club id"
                            )
                        }
                        // producerService.send("club-user-bridge", conversationId, msg)
                        return@whenComplete
                    }
                    log.info("Received from user-interface: $response")
                    // log.info("sending request to club-gateway-responses: $response")
                    // producerService.send("club-user-bridge", conversationId, response)
                }
            }

            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                log.warn("Unknown command: $command" ,"\n", "sending to user-gateway-responses: $msg")
                producerService.send("club-gateway-responses", conversationId, msg)
            }
        }
    }

}
