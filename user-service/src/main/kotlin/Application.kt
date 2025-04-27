package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.config.ApplicationConfig
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
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import common.exceptions.ArgumentNotFoundException
import common.kafka.KafkaConfig
import common.models.EnergySystem
import common.models.UnitSystem
import common.models.UserDTO
import common.models.UserRegistration
import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.TimeUnit

data class JwtConfig(
    val secret: String,
    val domain: String,
    val audience: String,
    val realm: String,
    val expiresIn: Long = 3_600_000
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): JwtConfig {
            return JwtConfig(
                secret = config.property("jwt.secret").getString(),
                domain = config.property("jwt.domain").getString(),
                audience = config.property("jwt.audience").getString(),
                realm = config.property("jwt.realm").getString()
            )
        }
    }
}

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
                var msg: DataPayload?
                if (response.message == "error") {
                    msg = response
                } else {
                    msg = DataPayload.error(
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
                } catch (e: IllegalArgumentException) {
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
                        || response.getParam<String>("token") == null
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

            /** Needs userId in [0], returns userDTO in [0] */
            "userInfo" -> {
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    onSuccess = { response ->
                        val userDTO = response.getParam<UserDTO>("user_dto")
                            ?: throw IllegalArgumentException("user_dto not found in response")
                        DataPayload.build("success") {
                            param("user_dto", userDTO)
                        }
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error retrieving user"
                )
            }

            /** Needs username in [0], returns userId in [0] */
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

            /** Needs userId in [0] and vararg as data in [1..n], returns userId in [0] */
            "updateUserInfo" -> {
                handleUserConsumerCommand(
                    data,
                    conversationId,
                    onSuccess = { response ->
                        val userId = response.getParam<String>("user_id")
                        DataPayload.build("success") {
                            param("user_id", userId)
                        }
                    },
                    errorStatusCode = HttpStatusCode.InternalServerError,
                    errorDescription = "Error updating user"
                )
            }

            /** Needs userId in [0], returns success in [0] */
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
}

fun generateToken(user: User, config: JwtConfig): String {
    return JWT.create()
        .withSubject(user.id)
        .withIssuer(config.domain)
        .withAudience(config.audience)
        .withClaim("username", user.username)
        .withExpiresAt(Date(System.currentTimeMillis() + config.expiresIn))
        .sign(Algorithm.HMAC256(config.secret))
}
