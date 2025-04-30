package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.DataPayload
import common.kafka.KafkaConfig
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import ru.polyZoj.models.Club
import ru.polyZoj.repositories.ClubDataSource
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
    
    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists("club-requests", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("club-responses", 1, 3.toShort())

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val kafkaConsumer = createKafkaConsumer("club-service-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf("club-gateway-requests"))

    consumerService.startConsuming { conversationId, message ->
        println("Consumed message -> ConversationID: $conversationId, Message: $message")
        
        try {
            val payload = Json.decodeFromString<DataPayload>(message)
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
    val name = payload.params.getOrNull(0) ?: ""
    val description = payload.params.getOrNull(1) ?: ""
    val ownerId = payload.params.getOrNull(2) ?: ""
    if (name.isBlank() || ownerId.isBlank()) {
        sendError(conversationId, "Missing required params", producer)
        return
    }
    val club = ClubDataSource.createClub(name, description, ownerId)
    val response = DataPayload(
        message = "created",
        params = listOf(club.id, club.name)
    )
    producer.send("club-gateway-responses", conversationId, Json.encodeToString(response))
}

private fun handleListClubs(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val limit = payload.params.getOrNull(0)?.toIntOrNull() ?: 10
    val offset = payload.params.getOrNull(1)?.toIntOrNull() ?: 0
    
    val clubs = ClubDataSource.getClubs(limit, offset)
    
    val response = DataPayload(
        message = "clubsList",
        params = clubs.map { Json.encodeToString(clubs) },
    )
    producer.send("club-gateway-responses", conversationId, Json.encodeToString(response))
}

private fun handleAddMember(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.params.getOrNull(0) ?: ""
    val userId = payload.params.getOrNull(1) ?: ""
    
    if (clubId.isBlank() || userId.isBlank()) {
        sendError(conversationId, "Missing club ID or user ID", producer)
        return
    }
    
    when (ClubDataSource.addMember(clubId, userId)) {
        true -> {
            val response = DataPayload(
                message = "memberAdded",
                params = listOf(userId, clubId)
            )
            producer.send("club-gateway-responses", conversationId, Json.encodeToString(response))
        }
        false -> sendError(conversationId, "Club not found or user already member", producer)
    }
}

private fun handleRemoveMember(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.params.getOrNull(0) ?: ""
    val userId = payload.params.getOrNull(1) ?: ""
    
    if (clubId.isBlank() || userId.isBlank()) {
        sendError(conversationId, "Missing club ID or user ID", producer)
        return
    }
    
    when (ClubDataSource.removeMember(clubId, userId)) {
        true -> {
            val response = DataPayload(
                message = "memberRemoved",
                params = listOf(userId, clubId)
            )
            producer.send("club-gateway-responses", conversationId, Json.encodeToString(response))
        }
        false -> sendError(conversationId, "Club not found or user not member", producer)
    }
}

private fun handleGetInfo(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.params.getOrNull(0) ?: ""
    val club = ClubDataSource.getClub(clubId)
    
    if (club != null) {
        val response = DataPayload(
            message = "clubInfo",
            params = listOf(club.id, club.name, club.description, club.ownerId, Json.encodeToString(club.members))
        )
        producer.send("club-gateway-responses", conversationId, Json.encodeToString(response))
    } else {
        sendError(conversationId, "Club not found", producer)
    }
}

private fun sendError(conversationId: String, message: String, producer: KafkaProducerService) {
    val response = DataPayload(
        message = "error",
        params = listOf(message)
    )
    producer.send("club-gateway-responses", conversationId, Json.encodeToString(response))
}

