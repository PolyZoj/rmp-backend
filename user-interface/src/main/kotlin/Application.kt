package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.DataPayload
import ru.polyZoj.db.*
import ru.polyZoj.exceptions.DuplicateFieldException
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.polyZoj.models.UserRegistration
import ru.polyZoj.repositories.UserRepository
import java.time.LocalDate

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

    consumerService.startConsuming { conversationId, message ->
        log.info("Received message: $message")
        val data = Json.decodeFromString<DataPayload>(message)
        val command = data.message
        val args = data.params
        when (command) {
            "login" -> {
                log.info("Login command received, args: $args")
                val username = args.getOrNull(0)
                val password = args.getOrNull(1)
                var response: DataPayload
                if (username == null || password == null) {
                    response = DataPayload("error", listOf("Invalid credentials"))
                } else {
                    val userId = userRepository.login(username, password)
                    response = if (userId != null) {
                        DataPayload(userId.toString(), listOf(userId.toString()))
                    } else {
                        DataPayload("error", listOf("Invalid credentials"))
                    }
                }
                log.info("sending response to user-responses: $response")
                producerService.send("user-responses", conversationId, Json.encodeToString(response))
            }

            "findByUsername" -> {
                log.info("Find by username command received, args: $args")
                val username = args.getOrNull(0)
                if (username == null) {
                    val err = DataPayload("error", listOf("Missing username"))
                    producerService.send("user-responses", conversationId, Json.encodeToString(err))
                } else {
                    val userId = userRepository.findByUsername(username)
                    val resp = if (userId != null) {
                        DataPayload(userId.toString(), listOf(userId.toString()))
                    } else {
                        DataPayload("error", listOf("User not found"))
                    }
                    log.info("sending response to user-responses: $resp")
                    producerService.send("user-responses", conversationId, Json.encodeToString(resp))
                }
            }

            "createUser" -> {
                log.info("Create user command received, args: $args")
                val reg = UserRegistration(
                    firstName = args.getOrNull(0) ?: throw IllegalArgumentException("First name is required"),
                    lastName = args.getOrNull(1) ?: throw IllegalArgumentException("Last name is required"),
                    username = args.getOrNull(2) ?: throw IllegalArgumentException("Username is required"),
                    email = args.getOrNull(3) ?: throw IllegalArgumentException("Email is required"),
                    password = args.getOrNull(4) ?: throw IllegalArgumentException("Password is required"),
                    weight = args.getOrNull(5)?.toFloat() ?: throw IllegalArgumentException("Weight is required"),
                    height = args.getOrNull(6)?.toShort() ?: throw IllegalArgumentException("Height is required"),
                    birthDate = args.getOrNull(7)?.let { LocalDate.parse(it) }
                        ?: throw IllegalArgumentException("Birth date is required"),
                    unitSystemId = args.getOrNull(8)?.toInt()
                        ?: throw IllegalArgumentException("Unit system ID is required"),
                    energySystemId = args.getOrNull(9)?.toInt()
                        ?: throw IllegalArgumentException("Energy system ID is required"),
                    healthGoalId = args.getOrNull(10)?.toInt(),
                    dailyStepGoal = args.getOrNull(11)?.toInt(),
                    waterIntakeGoal = args.getOrNull(12)?.toInt(),
                    calorieGoal = args.getOrNull(13)?.toShort(),
                    sleepGoal = args.getOrNull(14)?.toFloat(),
                    workoutsCount = args.getOrNull(15)?.toShort(),
                    avatarUrl = args.getOrNull(16),
                    )
                try {
                    val newId = userRepository.registerUser(reg)
                    val resp = DataPayload(newId.toString(), listOf(newId.toString()))
                    log.info("User created successfully, sending response: $resp")
                    producerService.send("user-responses", conversationId, Json.encodeToString(resp))

                } catch (e: DuplicateFieldException) {
                    val errorMsg = when (e.fieldName) {
                        "email" -> "That email is already registered."
                        "username" -> "That username is taken."
                        else -> "Duplicate field: ${e.fieldName}"
                    }
                    val errPayload = DataPayload("error", listOf(errorMsg))
                    log.error("Error creating user: $errorMsg", e)
                    producerService.send("user-responses", conversationId, Json.encodeToString(errPayload))
                } catch (e: Exception) {
                    // fallback for any other failure
                    val errPayload = DataPayload("Internal server error, please try again later.", emptyList())
                    log.error("Error creating user: ${e.message}", e)
                    producerService.send("user-responses", conversationId, Json.encodeToString(errPayload))
                }
            }

            "getUserDTO" -> {
                log.info("Get user DTO command received, args: $args")
                val userId = args.getOrNull(0)
                if (userId == null) {
                    val err = DataPayload("error", listOf("Missing user ID"))
                    producerService.send("user-responses", conversationId, Json.encodeToString(err))
                } else {
                    val userDTO = userRepository.getUserDTO(userId.toInt())
                    if (userDTO != null) {
                        val resp = DataPayload(userId, listOf(Json.encodeToString(userDTO)))
                        producerService.send("user-responses", conversationId, Json.encodeToString(resp))
                    } else {
                        val err = DataPayload("error", listOf("User not found"))
                        producerService.send("user-responses", conversationId, Json.encodeToString(err))
                    }
                }
            }

            "updateUserDTO" -> {
                log.info("Update user DTO command received, args: $args")
                val userId = args.getOrNull(0)
                if (userId == null) {
                    val err = DataPayload("error", listOf("Missing user ID"))
                    producerService.send("user-responses", conversationId, Json.encodeToString(err))
                } else {
                    val userDTO = userRepository.getUserDTO(userId.toInt())
                    if (userDTO != null) {
                        // Update logic here TODO: add update user info in repository
                        val resp = DataPayload(userId, listOf(Json.encodeToString(userDTO)))
                        producerService.send("user-responses", conversationId, Json.encodeToString(resp))
                    } else {
                        val err = DataPayload("error", listOf("User not found"))
                        producerService.send("user-responses", conversationId, Json.encodeToString(err))
                    }
                }
            }

            "deleteUser" -> {
                log.info("Delete user command received, args: $args")
                val userId = args.getOrNull(0)
                if (userId == null) {
                    val err = DataPayload("error", listOf("Missing user ID"))
                    producerService.send("user-responses", conversationId, Json.encodeToString(err))
                } else {
                    val deleted = userRepository.deleteUser(userId.toInt())
                    if (deleted) {
                        val resp = DataPayload("success", listOf("User deleted successfully"))
                        producerService.send("user-responses", conversationId, Json.encodeToString(resp))
                    } else {
                        val err = DataPayload("error", listOf("User not found"))
                        producerService.send("user-responses", conversationId, Json.encodeToString(err))
                    }
                }
            }

            else -> {
                val err = DataPayload("error", listOf("Unknown command"))
                log.error("Unknown command: $command")
                producerService.send("user-responses", conversationId, Json.encodeToString(err))
            }
        }
    }

}
