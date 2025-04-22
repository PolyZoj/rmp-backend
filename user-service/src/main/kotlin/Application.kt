package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import ru.polyZoj.kafka.KafkaConsumerService
import ru.polyZoj.kafka.KafkaProducerService
import ru.polyZoj.kafka.createKafkaConsumer
import ru.polyZoj.kafka.createKafkaProducer
import ru.polyZoj.models.DataPayload
import ru.polyZoj.models.User
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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

fun Application.module() {
    val jwtConfig = JwtConfig.fromConfig(environment.config)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val responseConsumer = KafkaConsumerService(createKafkaConsumer(), listOf("user-responses"))
    responseConsumer.startConsuming { conversationId, message ->
        val response = Json.decodeFromString<DataPayload>(message)
        pendingResponses[conversationId]?.complete(response)
        pendingResponses.remove(conversationId)
    }

    val authConsumer = KafkaConsumerService(createKafkaConsumer(), listOf("auth-requests"))
    authConsumer.startConsuming { conversationId, message ->
        val data = Json.decodeFromString<DataPayload>(message)
        val command = data.params.getOrNull(0)
        val username = data.params.getOrNull(1) ?: ""
        val password = data.params.getOrNull(2) ?: ""

        when (command) {
            "login" -> {
                val requestPayload = DataPayload("user-service", listOf("findByUsername", username))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null || response.params.isEmpty()) {
                        val msg = DataPayload("error", listOf("Invalid credentials"))
                        producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }

                    val userId = response.params[0]
                    val token = generateToken(User(userId, username, ""), jwtConfig)
                    val msg = DataPayload(userId, listOf(token))
                    producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                }
            }

            "register" -> {
                val requestPayload = DataPayload("user-service", listOf("createUser", username, password))
                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                producerService.send("user-requests", conversationId, Json.encodeToString(requestPayload))

                future.orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null) {
                        val msg = DataPayload("user-service", listOf("Error creating user"))
                        producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                        return@whenComplete
                    }

                    val userId = response.params.getOrNull(0) ?: "unknown"
                    val msg = DataPayload(userId, listOf("Success register"))
                    producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
                }
            }

            else -> {
                val msg = DataPayload("user-service", listOf("Unknown command"))
                producerService.send("auth-responses", conversationId, Json.encodeToString(msg))
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
