package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.DataPayload
import ru.polyZoj.db.*
import ru.polyZoj.repositories.ClubRepository
import ru.polyZoj.exceptions.DuplicateFieldException
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.Level
import common.LogSender
import ru.polyZoj.models.*
import java.time.LocalDate
import io.ktor.http.HttpStatusCode

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}


fun Application.module() {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }


    val ds = DataSourceConfig()
    DatabaseFactory.init(ds)

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val logger = LogSender(kafkaProducer)
    fun logInfo(ctx: String, msg: String) = logger.log("club-interface", Level.INFO, msg, ctx)
    fun logError(ctx: String, msg: String) = logger.log("club-interface", Level.ERROR, msg, ctx)

    val kafkaConsumer = createKafkaConsumer("club-interface-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf("club-requests"))

    val clubRepository = ClubRepository(logger)

    consumerService.startConsuming { conversationId, data ->
        logInfo("message consumed", "Received message: $data")
        val command = data.message
        when (command.lowercase()) {
            "create" -> {
                logInfo("create command", "Club create command received, data: $data")
                val ownerId = data.getParam<String>("ownerId")
                val description = data.getParam<String>("description")
                val name = data.getParam<String>("name")
                if (name == null || description == null || ownerId == null){
                    val response = DataPayload.error(
                        HttpStatusCode.BadRequest,
                        description = "Invalid credentials"
                    )
                    producerService.send("club-responses", conversationId, response)
                } else {
                    val clubId = clubRepository.createClub(name, description, ownerId.toInt())
                    val response = if (clubId != null) {
                        log.info("Club $name created with clubId = $clubId")
                        DataPayload.build("clubCreated") {
                            param("clubId", clubId.toString())
                        }
                    } else {
                        DataPayload.error(
                            HttpStatusCode.BadRequest,
                            description = "Invalid credentials"
                        )
                    }
                    logInfo("sending message", "sending response to club-responses: $response")
                    producerService.send("club-responses", conversationId, response)
                    producerService.send(
                        "club-user-bridge",
                        conversationId,
                        DataPayload.build("updateClubId"){
                            param("club_id", clubId ?: 0)
                            param("user_id", ownerId.toInt())
                        }
                    )
                }
            }

            "listclubs" -> {
                logInfo("list command", "List clubs command received, data: $data")
                val limit = data.getParam<Int>("limit")
                val offset = data.getParam<Int>("offset")
                
                if (limit == null || offset == null) {
                    val response = DataPayload.error(
                        HttpStatusCode.BadRequest,
                        description = "Missing limit or offset parameters"
                    )
                    producerService.send("club-responses", conversationId, response)
                } else {
                    val clubs = clubRepository.listClubs(limit, offset)
                    val response = DataPayload.build("clubsListed") {
                        param("clubs", clubs)
                    }
                    logInfo("sending message", "Sending list of ${clubs.size} clubs")
                    producerService.send("club-responses", conversationId, response)
                }
            }

            "addmember" -> {
                logInfo("add member command", "Add member command received, data: $data")
                val clubId = data.getParam<Int>("clubId")
                val userId = data.getParam<Int>("userId")
                if (clubId == null || userId == null) {
                    val response = DataPayload.error(
                        HttpStatusCode.BadRequest,
                        description = "Missing clubId or userId"
                    )
                    producerService.send("club-responses", conversationId, response)
                } else {
                    val success = clubRepository.addMember(clubId, userId)
                    val response = if (success) {
                        DataPayload.build("memberAdded") {
                            param("userId", userId.toString())
                            param("clubId", clubId.toString())
                        }
                    } else {
                        DataPayload.error(
                            HttpStatusCode.InternalServerError,
                            description = "Failed to add member"
                        )
                    }
                    producerService.send("club-responses", conversationId, response)
                    producerService.send(
                        "club-user-bridge",
                        conversationId,
                        DataPayload.build("updateClubId"){
                            param("club_id", clubId)
                            param("user_id", userId)
                        }
                    )
                }
            }


            "removemember" -> {
                logInfo("Remove member command", "Remove member command received, data: $data")
                val clubId = data.getParam<Int>("clubId")
                val userId = data.getParam<Int>("userId")
                if (clubId == null || userId == null) {
                    val response = DataPayload.error(
                        HttpStatusCode.BadRequest,
                        description = "Missing clubId or userId"
                    )
                    producerService.send("club-responses", conversationId, response)
                } else {
                    val success = clubRepository.removeMember(clubId, userId)
                    val response = if (success) {
                        DataPayload.build("memberRemoved") {
                            param("userId", userId.toString())
                            param("clubId", clubId.toString())
                        }
                    } else {
                        DataPayload.error(
                            HttpStatusCode.InternalServerError,
                            description = "Failed to remove member"
                        )
                    }
                    producerService.send("club-responses", conversationId, response)
                    producerService.send(
                        "club-user-bridge",
                        conversationId,
                        DataPayload.build("updateClubId"){
                            param("club_id", 0)
                            param("user_id", userId)
                        }
                    )
                }
            }

            "getinfo" -> {
                logInfo("Get club command", "Get club command received, data: $data")
                val clubId = data.getParam<String>("clubId")
                if (clubId == null) {
                    val response = DataPayload.error(
                        HttpStatusCode.BadRequest,
                        description = "Missing clubId"
                    )
                    producerService.send("club-responses", conversationId, response)
                } else {
                    val club = clubRepository.getClub(clubId.toInt())
                    val response = if (club != null) {
                        DataPayload.build("clubInfo") {
                            param("club", club)
                        }
                    } else {
                        DataPayload.error(
                            HttpStatusCode.NotFound,
                            description = "Club not found"
                        )
                    }
                    producerService.send("club-responses", conversationId, response)
                }
            }

            else -> {
                val err = DataPayload.error(
                    HttpStatusCode.BadRequest,
                    description = "Invalid message"
                )
                logError("error", "Unknown command: $command")
                producerService.send("club-responses", conversationId, err)
            }
        }
    }

}
