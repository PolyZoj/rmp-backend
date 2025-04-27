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
import ru.polyZoj.models.Club
import ru.polyZoj.repositories.ClubDataSource
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import common.toJsonElement

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
                realm = config.property("jwt.realm").getString(),
                expiresIn = 3_600_000
            )
        }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val kafkaConsumer = createKafkaConsumer("club-service-consumer")
    val consumerTopics = listOf("club-requests")
    val consumerService = KafkaConsumerService(kafkaConsumer, consumerTopics)

    consumerService.startConsuming { conversationId, payload ->
        println("Consumed message -> ConversationID: $conversationId, Message: $payload")
        
        try {
            println(payload)
            when (payload.message.lowercase()) {
                "create" -> handleCreateClub(payload, conversationId, producerService)
                "listclubs" -> handleListClubs(payload, conversationId, producerService)
                "addmember" -> handleAddMember(payload, conversationId, producerService)
                "removemember" -> handleRemoveMember(payload, conversationId, producerService)
                "getinfo" -> handleGetInfo(payload, conversationId, producerService)
                else -> sendError(conversationId, "Invalid message", producerService)
            }
        } catch (e: Exception) {
            sendError(conversationId, "Invalid request format", producerService)
        }
    }
}

private fun handleCreateClub(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    println("sending response")
    val name = payload.getParam<String>("name").orEmpty()
    val description = payload.getParam<String>("description").orEmpty()
    val ownerId = payload.getParam<String>("ownerId").orEmpty()
    println("sending response")
    if (name.isBlank() || ownerId.isBlank()) {
        sendError(conversationId, "Missing required params", producer)
        return
    }
    println("sending response")
    val club = ClubDataSource.createClub(name, description, ownerId)
    val response = DataPayload.build("created") {
        param("id", club.id)
        param("name", club.name)
    }
    producer.send("club-responses", conversationId, response)
}

private fun handleListClubs(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val limit = payload.getParam<Int>("limit") ?: 10
    val offset = payload.getParam<Int>("offset") ?: 0

    val clubs = ClubDataSource.getClubs(limit, offset)
    
    val response = DataPayload.build("clubs") {
        param("clubs", clubs)
    }
    producer.send("club-responses", conversationId, response)
}

private fun handleAddMember(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.getParam<String>("clubId").orEmpty()
    val userId = payload.getParam<String>("userId").orEmpty()
    
    if (clubId.isBlank() || userId.isBlank()) {
        sendError(conversationId, "Missing club ID or user ID", producer)
        return
    }
    
    when (ClubDataSource.addMember(clubId, userId)) {
        true -> {
            val response = DataPayload.build("memberAdded") {
                param("userId", userId)
                param("clubId", clubId)
            }
            producer.send("club-responses", conversationId, response)
        }
        false -> sendError(conversationId, "Club not found or user already member", producer)
    }
}

private fun handleRemoveMember(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.getParam<String>("clubId").orEmpty()
    val userId = payload.getParam<String>("userId").orEmpty()
    
    if (clubId.isBlank() || userId.isBlank()) {
        sendError(conversationId, "Missing club ID or user ID", producer)
        return
    }
    
    when (ClubDataSource.removeMember(clubId, userId)) {
        true -> {
            val response = DataPayload.build("memberRemoved") {
                param("userId", userId)
                param("clubId", clubId)
            }
            producer.send("club-responses", conversationId, response)
        }
        false -> sendError(conversationId, "Club not found or user not member", producer)
    }
}

private fun handleGetInfo(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.getParam<String>("clubId").orEmpty()
    val club = ClubDataSource.getClub(clubId)
    
    if (club != null) {
        val response = DataPayload.build("clubInfo") {
            param("id", club.id)
            param("name", club.name)
            param("description", club.description)
            param("ownerId", club.ownerId)
            param("members", club.members)
        }
        producer.send("club-responses", conversationId, response)
    } else {
        sendError(conversationId, "Club not found", producer)
    }
}

// TODO: Изменить чтобы использовал DataPayload.error с кодом ошибки
private fun sendError(conversationId: String, message: String, producer: KafkaProducerService) {
    val response = DataPayload(
        message = "error",
        params = mapOf("message" to message.toJsonElement())
    )
    producer.send("club-responses", conversationId, response)
}

