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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.polyZoj.models.*
import java.time.LocalDate
import io.ktor.http.HttpStatusCode

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

    val kafkaConsumer = createKafkaConsumer("club-interface-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf("club-requests"))

    val clubRepository = ClubRepository()

    consumerService.startConsuming { conversationId, data ->
        log.info("Received message: $data")
        val command = data.message
        when (command.lowercase()) {
            "create" -> {
                log.info("Club create command received, data: $data")
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
                    log.info("sending response to club-responses: $response")
                    producerService.send("club-responses", conversationId, response)
                }
            }
            "listclubs" -> {
            }

            "addmember" -> {
                log.info("Add member command received, data: $data")
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
                }
            }


            "removemember" -> {
                log.info("Remove member command received, data: $data")
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
                }
            }

            "getinfo" -> {
                log.info("Get club info command received, data: $data")
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
                log.error("Unknown command: $command")
                producerService.send("club-responses", conversationId, err)
            }
        }
    }

}
