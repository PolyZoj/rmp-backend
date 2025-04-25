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
import common.kafka.KafkaConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.Date

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
        val response = Json.decodeFromString<DataPayload>(message)
        pendingResponses[conversationId]?.complete(response)
        pendingResponses.remove(conversationId)
    }

    val authConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf("auth-requests"))
    authConsumer.startConsuming { conversationId, message ->
        log.info("Received auth request: $message")
        val data = Json.decodeFromString<DataPayload>(message)
        val command = data.message
        val username = data.params.getOrNull(0)
        val password = data.params.getOrNull(1)

        when (command) {
            "login" -> {
                log.info("login, username: $username, password: $password")
                if (username == null || password == null) {
                    val msg = DataPayload("user-service", listOf("Invalid credentials"))
                    producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                    return@startConsuming
                }
                val requestPayload = DataPayload("login", listOf(username, password))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null || response.params.isEmpty() || response.message == "error") {
                        log.warn("Received from user-interface: $response")
                        val msg = DataPayload("error", listOf("Invalid credentials"))
                        producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }
                    log.info("Received from user-interface: $response")
                    val userId = response.params[0]
                    val token = generateToken(User(userId, username, ""), jwtConfig)
                    val msg = DataPayload(userId, listOf(token))
                    log.info("sending request to auth-responses: $msg")
                    producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                }
            }

            "register" -> {
                log.info("register, username: $username, password: $password")
                if (username == null || password == null) {
                    val msg = DataPayload("user-service", listOf("Invalid credentials"))
                    producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                    return@startConsuming
                }
                val requestPayload = DataPayload("createUser", listOf(username, password))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null) {
                        log.warn("Received from user-interface: $response")
                        val msg = DataPayload("user-service", listOf("Error creating user"))
                        producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }
                    log.info("Received from user-interface: $response")
                    val userId = response.params.getOrNull(0) ?: "unknown"
                    val msg = DataPayload(userId, listOf("Success register"))
                    log.info("sending request to auth-responses: $msg")
                    producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                }
            }

            else -> {
                val msg = DataPayload("user-service", listOf("Unknown command"))
                log.warn("Unknown command: $command" ,"\n", "sending to auth-responses: $msg")
                producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
            }
        }
    }

    val userConsumer = KafkaConsumerService(createKafkaConsumer("user-service-consumer"), listOf("user-gateway-requests"))
    userConsumer.startConsuming { conversationId, message ->
        log.info("Received user request: $message")
        val data = Json.decodeFromString<DataPayload>(message)
        val command = data.message
        val args = data.params
        when (command) {
            "userInfo" -> {
                log.info("userInfo, args: $args")
                val userId = args.getOrNull(0)
                if (userId == null) {
                    val msg = DataPayload("user-service", listOf("Invalid credentials"))
                    producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                }
                val requestPayload = DataPayload("getUserDTO", listOf(userId.toString()))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future


                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null) {
                        val msg = DataPayload("user-service", listOf("Error getting user"))
                        producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }
                    val userDTO = response.params.getOrNull(0)
                    val msg = DataPayload("user-service", listOf(userDTO.toString()))
                    log.info("sending request to user-gateway-responses: $msg")
                    producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                }

            }

            "findByUsername" -> {
                log.info("findByUsername, args: $args")
                val username = args.getOrNull(0)
                if (username == null) {
                    val msg = DataPayload("user-service", listOf("Invalid credentials"))
                    producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                }
                val requestPayload = DataPayload("findByUsername", listOf(username.toString()))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null) {
                        val msg = DataPayload("user-service", listOf("Error getting user"))
                        producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }
                    val userId = response.params.getOrNull(0)
                    val msg = DataPayload("user-service", listOf(userId.toString()))
                    log.info("sending request to user-gateway-responses: $msg")
                    producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                }
            }

            "updateUserInfo" -> {
                log.info("updateUserInfo, args: $args")
                val userId = args.getOrNull(0)
                if (userId == null) {
                    val msg = DataPayload("user-service", listOf("Invalid credentials"))
                    producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                }
                val requestPayload = DataPayload("updateUserDTO", listOf(userId.toString()))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null) {
                        val msg = DataPayload("user-service", listOf("Error getting user"))
                        producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }
                    val userDTO = response.params.getOrNull(0)
                    val msg = DataPayload("user-service", listOf(userDTO.toString()))
                    log.info("sending request to user-gateway-responses: $msg")
                    producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                }
            }

            "deleteUser" -> {
                log.info("deleteUser, args: $args")
                val userId = args.getOrNull(0)
                if (userId == null) {
                    val msg = DataPayload("user-service", listOf("Invalid credentials"))
                    producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                }
                val requestPayload = DataPayload("deleteUser", listOf(userId.toString()))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                log.info("sending request to user-requests: $requestPayload")
                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null) {
                        val msg = DataPayload("user-service", listOf("Error getting user"))
                        producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }
                    val success = response.message
                    if (success == "success") {
                        val msg = DataPayload("user-service", listOf("Successfully deleted"))
                        log.info("sending request to user-gateway-responses: $msg")
                        producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                    } else {
                        val msg = DataPayload("user-service", listOf("Error deleting user"))
                        log.info("sending request to user-gateway-responses: $msg")
                        producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
                    }
                }
            }

            else -> {
                val msg = DataPayload("user-service", listOf("Unknown command"))
                log.warn("Unknown command: $command" ,"\n", "sending to user-gateway-responses: $msg")
                producerService.send("user-gateway-responses", conversationId, Json.encodeToString(msg))
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
