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
import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import common.models.UserRegistration
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
                            param("user_id", userId)
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
                    val errorMsg = when (e.fieldName) {
                        "email" -> "That email is already registered."
                        "username" -> "That username is taken."
                        else -> "Duplicate field: ${e.fieldName}"
                    }
                    val err = DataPayload.error(
                        status = HttpStatusCode.Conflict,
                        description = errorMsg
                    )
                    log.error("Error creating user: $errorMsg", e)
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
                if (userId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val userDTO = userRepository.getUserDTO(userId.toInt())
                    if (userDTO != null) {
                        val resp = DataPayload.build(userId) {
                            param("user_dto", userDTO)
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

            "updateUserDTO" -> {
                log.info("Update user DTO command received, data: $data")
                val userId = data.getParam<String>("user_id")
                if (userId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user ID"
                    )
                    producerService.send("user-responses", conversationId, err)
                } else {
                    val userDTO = userRepository.getUserDTO(userId.toInt())
                    if (userDTO != null) {
                        // TODO: Update logic in repository
                        val resp = DataPayload.build(userId) {
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
                            param("message", "User deleted successfully")
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
