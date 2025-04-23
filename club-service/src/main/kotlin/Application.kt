package ru.polyZog

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import ru.polyZog.kafka.KafkaConsumerService
import ru.polyZog.kafka.KafkaProducerService
import ru.polyZog.kafka.createKafkaConsumer
import ru.polyZog.kafka.createKafkaProducer
import ru.polyZog.models.DataPayload
import ru.polyZog.models.Club
import ru.polyZog.repositories.ClubDataSource
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

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

    val kafkaConsumer = createKafkaConsumer()
    val consumerTopics = listOf("club-requests")
    val consumerService = KafkaConsumerService(kafkaConsumer, consumerTopics)

    consumerService.startConsuming { conversationId, message ->
        println("Consumed message -> ConversationID: $conversationId, Message: $message")
        
        try {
            val payload = Json.decodeFromString<DataPayload>(message)
            
            when (payload.message.lowercase()) {
                "create" -> handleCreateClub(payload, conversationId, producerService)
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
    val name = payload.parameters.getOrNull(0) ?: ""
    val description = payload.parameters.getOrNull(1) ?: ""
    val ownerId = payload.parameters.getOrNull(2) ?: ""
    
    if (name.isBlank() || ownerId.isBlank()) {
        sendError(conversationId, "Missing required parameters", producer)
        return
    }
    println("sending response")
    val club = ClubDataSource.createClub(name, description, ownerId)
    val response = DataPayload(
        message = "created",
        clubId = club.id,
        parameters = listOf(club.id, club.name)
    )
    producer.send("club-responses", conversationId, Json.encodeToString(response))
}

private fun handleAddMember(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.clubId ?: ""
    val userId = payload.parameters.firstOrNull() ?: ""
    
    if (clubId.isBlank() || userId.isBlank()) {
        sendError(conversationId, "Missing club ID or user ID", producer)
        return
    }
    
    when (ClubDataSource.addMember(clubId, userId)) {
        true -> {
            val response = DataPayload(
                message = "memberAdded",
                clubId = clubId,
                parameters = listOf(userId)
            )
            producer.send("club-responses", conversationId, Json.encodeToString(response))
        }
        false -> sendError(conversationId, "Club not found or user already member", producer)
    }
}

private fun handleRemoveMember(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.clubId ?: ""
    val userId = payload.parameters.firstOrNull() ?: ""
    
    if (clubId.isBlank() || userId.isBlank()) {
        sendError(conversationId, "Missing club ID or user ID", producer)
        return
    }
    
    when (ClubDataSource.removeMember(clubId, userId)) {
        true -> {
            val response = DataPayload(
                message = "memberRemoved",
                clubId = clubId,
                parameters = listOf(userId)
            )
            producer.send("club-responses", conversationId, Json.encodeToString(response))
        }
        false -> sendError(conversationId, "Club not found or user not member", producer)
    }
}

private fun handleGetInfo(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.clubId ?: ""
    val club = ClubDataSource.getClub(clubId)
    
    if (club != null) {
        val response = DataPayload(
            message = "clubInfo",
            clubId = clubId,
            parameters = listOf(club.name, club.description, club.ownerId, club.members.size.toString())
        )
        producer.send("club-responses", conversationId, Json.encodeToString(response))
    } else {
        sendError(conversationId, "Club not found", producer)
    }
}

private fun sendError(conversationId: String, message: String, producer: KafkaProducerService) {
    val response = DataPayload(
        message = "error",
        parameters = listOf(message)
    )
    producer.send("club-responses", conversationId, Json.encodeToString(response))
}