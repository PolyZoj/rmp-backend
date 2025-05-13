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
import common.Level
import common.LogSender
import ru.polyZoj.models.User
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import common.exceptions.ArgumentNotFoundException
import common.kafka.KafkaConfig
import common.kafka.topics.*
import common.models.EnergySystem
import common.models.UnitSystem
import common.models.UserBasicInfo
import common.models.UserRegistration
import common.models.UserUpdatable
import io.ktor.http.HttpStatusCode
import ru.polyZoj.configs.JwtConfig
import ru.polyZoj.configs.generateToken
import java.time.LocalDate
import java.util.concurrent.TimeUnit

val pendingResponses = ConcurrentHashMap<String, CompletableFuture<DataPayload>>()

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    val jwtConfig = JwtConfig.fromConfig(environment.config)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists(USER_SERVICE_REQ , 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists(USER_SERVICE_RES, 1, 3.toShort())

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val logger = LogSender(kafkaProducer)
    fun logInfo(ctx: String, msg: String) = logger.log("user-service", Level.INFO, msg, ctx)
    fun logError(ctx: String, msg: String) = logger.log("user-service", Level.ERROR, msg, ctx)

    val responseConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf(USER_SERVICE_RES))
    responseConsumer.startConsuming { conversationId, message ->
        pendingResponses[conversationId]?.complete(message)
        pendingResponses.remove(conversationId)
    }

    fun forwardToUserService(
        original: DataPayload,
        conversationId: String,
        onSuccess: (DataPayload) -> DataPayload,
        errorStatus: HttpStatusCode,
        errorDescription: String,
        requiredParam: String? = "user_id",
        replyTopic: String = USER_GATEWAY_RES,
    ) {
        val command = original.message
        logInfo(command, "Received request: $original")

        if (requiredParam != null && original.getParam<String>(requiredParam) == null) {
            val err = DataPayload.error(errorStatus, "Missing $requiredParam")
            logError(command, "Validation failed – sending error: $err")
            producerService.send(replyTopic, conversationId, err)
            return
        }
        val future = CompletableFuture<DataPayload>()
        pendingResponses[conversationId] = future

        logInfo(command, "Sending request to $USER_SERVICE_REQ: $original")
        producerService.send(USER_SERVICE_REQ, conversationId, original)

        future.orTimeout(5, TimeUnit.SECONDS).whenComplete { res, ex ->
            if (ex != null) {
                val err = DataPayload.error(errorStatus, errorDescription)
                logError(command, "Future failed: ${ex.message}")
                producerService.send(replyTopic, conversationId, err)
                return@whenComplete
            }

            if (res.message == "error") {
                logError(command, "Received domain error: $res")
                producerService.send(replyTopic, conversationId, res)
                return@whenComplete
            }

            logInfo(command, "Received success: $res")
            val responsePayload = onSuccess(res)
            logInfo(command, "Sending response to $replyTopic: $responsePayload")
            producerService.send(replyTopic, conversationId, responsePayload)
        }
    }

    val authConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf(AUTH_REQ))
    authConsumer.startConsuming { conversationId, data ->
        val command = data.message
        when (command) {
            /**
             * Needs username in [0], password in [1]
             * Returns userId in [0], token in [1]
             */
            "login" -> {
                logInfo(command, "Received auth request: $data")
                val username = data.getParam<String>("username")
                val password = data.getParam<String>("password")
                if (username == null || password == null) {
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Invalid credentials"
                    )
                    logError(command, "Username/password missing – $msg")
                    producerService.send(AUTH_RES, conversationId, msg)
                    return@startConsuming
                }

                val requestPayload = DataPayload.build(command) {
                    param("username", username)
                    param("password", password)
                }

                forwardToUserService(
                    original = requestPayload,
                    conversationId = conversationId,
                    onSuccess = { serviceResp ->
                        val userId = serviceResp.getParam<String>("user_id")!!
                        val token = generateToken(User(userId, username, password), jwtConfig)
                        DataPayload.build("success") {
                            param("user_id", userId)
                            param("token", token)
                        }
                    },
                    errorStatus = HttpStatusCode.Unauthorized,
                    errorDescription = "Invalid credentials",
                    requiredParam = null,
                    replyTopic = AUTH_RES
                )
            }

            /**
             * Needs `UserRegistration` properties in [0..n]
             * Returns userId in [0] and token in [1]
             */
            "register" -> {
                logInfo(command, "Received register request: $data")
                val userRegistration = try {
                    UserRegistration(
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
                            } catch (_: IllegalArgumentException) {
                                throw IllegalArgumentException("Invalid unit system: $raw")
                            }
                        } ?: throw ArgumentNotFoundException("unit_system"),
                        energySystem = data.getParam<String>("energy_system")?.let { raw ->
                            try {
                                EnergySystem.valueOf(raw.uppercase())
                            } catch (_: IllegalArgumentException) {
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
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = e.message ?: "Invalid registration data"
                    )
                    logError(command, "Validation failed – $e")
                    producerService.send(AUTH_RES, conversationId, msg)
                    return@startConsuming
                }
                val requestPayload = DataPayload.build(command) {
                    param("user_registration", userRegistration)
                }
                forwardToUserService(
                    original = requestPayload,
                    conversationId = conversationId,
                    onSuccess = { serviceResp ->
                        val userId = serviceResp.getParam<String>("user_id")!!
                        val token = generateToken(User(userId, userRegistration.username, userRegistration.password), jwtConfig)
                        DataPayload.build("success") {
                            param("user_id", userId)
                            param("token", token)
                        }
                    },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error registering user",
                    requiredParam = null,
                    replyTopic = AUTH_RES
                )

            }

            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                logError(command, "Unknown command – $data")
                producerService.send(AUTH_RES, conversationId, msg)
            }
        }
    }

    val userConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf(USER_GATEWAY_REQ))
    userConsumer.startConsuming { conversationId, data ->
        log.info("Received user request: $data")
        val command = data.message
        when (command) {

            "deleteUser", "acceptFriendRequest", "denyFriendRequest", "addFriendRequest", "removeFriend" ->
                forwardToUserService(
                    original = data,
                    conversationId = conversationId,
                    onSuccess = { DataPayload.build("success") { param("success", true) } },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error executing ${data.message}"
                )

            /** Needs userId and selfId, returns flattened userDTO + status */
            "userInfo" ->
                forwardToUserService(
                    original = data,
                    conversationId = conversationId,
                    onSuccess = { it }, // pass‑through full payload
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving user"
                )

            /** Needs username, returns userId */
            "findByUsername" ->
                forwardToUserService(
                    original = data,
                    conversationId = conversationId,
                    requiredParam = "username",
                    onSuccess = { resp ->
                        DataPayload.build("success") { param("user_id", resp.getParam<String>("user_id")) }
                    },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving user"
                )

            /** Needs userId and UserUpdatable, returns success */
            "updateUserInfo" -> {
                val userUpdatable = try {
                    UserUpdatable(
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
                } catch (e: Exception) {
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = e.message ?: "Invalid update data"
                    )
                    logError(command, "Validation failed – $e")
                    producerService.send(USER_GATEWAY_RES, conversationId, msg)
                    return@startConsuming
                }
                val dataPayload = DataPayload.build(command) {
                    param("user_id", data.getParam<String>("user_id"))
                    param("user_data", userUpdatable)
                }
                forwardToUserService(
                    original = dataPayload,
                    conversationId = conversationId,
                    onSuccess = { DataPayload.build("success") { param("success", true) } },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error updating user"
                )
            }

            /** Needs userId, returns List<Pair<Int, String>> */
            "getFriendRequests" ->
                forwardToUserService(
                    original = data,
                    conversationId = conversationId,
                    onSuccess = { resp ->
                        DataPayload.build("success") { param("friend_requests", resp.getParam<List<Pair<Int, String>>>("friend_requests")) }
                    },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving friend requests"
                )

            /** Needs userId, returns List<UserBasicInfo> */
            "getFriendsList" ->
                forwardToUserService(
                    original = data,
                    conversationId = conversationId,
                    onSuccess = { resp ->
                        DataPayload.build("success") { param("friends", resp.getParam<List<UserBasicInfo>>("friends")) }
                    },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving friends list"
                )

            /** Needs userId, find_username String, returns possible-friend List<UserBasicInfo> */
            "findFriend" -> {
                val findUsername = data.getParam<String>("find_username")
                if (findUsername == null) {
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Not found find_username in request"
                    )
                    logError(command, "Validation failed – $msg")
                    producerService.send(USER_GATEWAY_RES, conversationId, msg)
                    return@startConsuming
                }
                forwardToUserService(
                    original = data,
                    conversationId = conversationId,
                    onSuccess = { resp ->
                        DataPayload.build("success") { param("possible_friend", resp.getParam<List<UserBasicInfo>>("possible_friend")) }
                    },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving possible friends"
                )
            }



            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                logError(command, "Validation failed – $msg")
                producerService.send(USER_GATEWAY_RES, conversationId, msg)
            }
        }
    }

    val clubConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf("club-user-bridge"))
    clubConsumer.startConsuming { conversationId, data ->
        val command = data.message
        when (command) {
            /** Needs userId and clubId, returns success */
            "updateClubId" -> {
                val userId = data.getParam<Int>("user_id")
                val clubId = data.getParam<Int>("club_id")
                if (userId == null || clubId == null) {
                    val err = DataPayload.error(HttpStatusCode.BadRequest, "Missing user_id or club_id")
                    logError(command, "Validation failed – $err")
                    producerService.send("club-user-bridge", conversationId, err)
                    return@startConsuming
                }

                val requestPayload = DataPayload.build(command) {
                    param("user_id", userId.toString())
                    param("club_id", clubId.toString())
                }
                forwardToUserService(
                    original = requestPayload,
                    conversationId = conversationId,
                    onSuccess = { DataPayload.build("success") { param("success", true) } },
                    errorStatus = HttpStatusCode.InternalServerError,
                    errorDescription = "Error updating club id",
                    replyTopic = "club-user-bridge"
                )
            }

            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                logError(command, "Unknown club command: $command")
                producerService.send("club-gateway-responses", conversationId, msg)
            }
        }
    }

}
