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
import common.kafka.KafkaConfig
import ru.polyZoj.models.Club
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import common.toJsonElement
import common.Level
import common.LogSender

data class JwtConfig(
    val secret: String,
    val domain: String,
    val audience: String,
    val realm: String,
    val expiresIn: Long = 3_600_000
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): JwtConfig = JwtConfig(
            secret = config.property("jwt.secret").getString(),
            domain = config.property("jwt.domain").getString(),
            audience = config.property("jwt.audience").getString(),
            realm = config.property("jwt.realm").getString(),
            expiresIn = 3_600_000
        )
    }
}

fun main() {
    embeddedServer(
        factory = Netty,
        port = 8080,
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    configureContentNegotiation()
    configureKafka()
}

private fun Application.configureContentNegotiation() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        })
    }
}

private fun Application.configureKafka() {

    val kafkaConfig = KafkaConfig().apply {
        createTopicIfNotExists("club-requests", 1, 3.toShort())
        createTopicIfNotExists("club-responses", 1, 3.toShort())
    }

    val kafkaProducer =  createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)
    val logger = LogSender(kafkaProducer)
    fun logInfo(ctx: String, msg: String) = logger.log("club-service", Level.INFO, msg, ctx)
    fun logError(ctx: String, msg: String) = logger.log("club-service", Level.ERROR, msg, ctx)

    val gatewayConsumerService = KafkaConsumerService(
        consumer = createKafkaConsumer("club-service-consumer"),
        topics = listOf("club-gateway-requests")
    )

    val interfaceConsumerService = KafkaConsumerService(
        consumer = createKafkaConsumer("club-service-consumer"),
        topics = listOf("club-responses")
    )


    gatewayConsumerService.startConsuming { conversationId, payload ->
        handleKafkaGatewayMessage(conversationId, payload, producerService, logger)
    }

    interfaceConsumerService.startConsuming { conversationId, payload ->
        handleKafkaInterfaceMessage(conversationId, payload, producerService, logger)
    }
}


private fun handleKafkaGatewayMessage(
    conversationId: String,
    payload: DataPayload,
    producer: KafkaProducerService,
    logger: LogSender
) {
    logger.log("club-service", Level.INFO, "Consumed message -> ConversationID: $conversationId, Message: $payload.message", "message consumed")
    try {
        when (payload.message.lowercase()) {
            "create" -> handleCreateClub(payload, conversationId, producer)
            "listclubs" -> handleListClubs(payload, conversationId, producer)
            "addmember" -> handleAddMember(payload, conversationId, producer)
            "removemember" -> handleRemoveMember(payload, conversationId, producer)
            "getinfo" -> handleGetInfo(payload, conversationId, producer)
            else -> sendError(conversationId, "Invalid message", producer)
        }
    } catch (e: Exception) {
        sendError(conversationId, "Invalid request format", producer)
    }
}

private fun handleKafkaInterfaceMessage(
    conversationId: String,
    payload: DataPayload,
    producer: KafkaProducerService,
    logger: LogSender
) {
    logger.log("club-service", Level.INFO, "Consumed message -> ConversationID: $conversationId, Message: $payload.message", "message consumed")
    try {
        when (payload.message.lowercase()) {
            "clubcreated" -> {
                producer.send("club-gateway-responses"
                ,conversationId
                ,DataPayload.build("created") {
                    param("clubId", payload.getParam<String>("clubId").orEmpty())
                })
            }
            "clubslisted" -> {
                producer.send("club-gateway-responses"
                ,conversationId
                ,DataPayload.build("clubsListed") {
                    param("clubs", payload.getParam<List<Club?>>("clubs"))
                })
            }
            "memberadded" -> {
                producer.send("club-gateway-responses"
               ,conversationId
               ,DataPayload.build("memberAdded") {
                    param("userId", payload.getParam<String>("userId").orEmpty())
                    param("clubId", payload.getParam<String>("clubId").orEmpty())
                })
            }
            "memberremoved" -> {
                producer.send("club-gateway-responses"
               ,conversationId
               ,DataPayload.build("memberRemoved") {
                    param("userId", payload.getParam<String>("userId").orEmpty())
                    param("clubId", payload.getParam<String>("clubId").orEmpty())
                })
            }
            "clubinfo" -> {
                producer.send("club-gateway-responses"
               ,conversationId
               ,DataPayload.build("clubInfo") {
                    param("club", payload.getParam<Club>("club"))
                })
            }


            else -> sendError(conversationId, "Invalid message", producer)
        }
    } catch (e: Exception) {
        sendError(conversationId, "Invalid request format", producer)
    }
}

private fun handleCreateClub(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val name = payload.getParam<String>("name").orEmpty()
    val description = payload.getParam<String>("description").orEmpty()
    val ownerId = payload.getParam<String>("ownerId").orEmpty()
    if (name.isBlank() || description.isBlank() || ownerId.toDoubleOrNull() == null) {
        sendError(conversationId, "Missing required params or Id not a number", producer)
        return
    }

    producer.send("club-requests", conversationId, payload)
}

private fun handleListClubs(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {

    producer.send("club-requests", conversationId, payload)
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
    
    producer.send("club-requests", conversationId, payload)
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
    
    producer.send("club-requests", conversationId, payload)
}

private fun handleGetInfo(
    payload: DataPayload,
    conversationId: String,
    producer: KafkaProducerService
) {
    val clubId = payload.getParam<String>("clubId").orEmpty()
    
    if (clubId.isBlank()) {
        sendError(conversationId, "Missing club ID", producer)
        return
    }
    producer.send("club-requests", conversationId, payload)
}
    

// TODO: Изменить чтобы использовал DataPayload.error с кодом ошибки
private fun sendError(conversationId: String, message: String, producer: KafkaProducerService) {
    val response = DataPayload(
        message = "error",
        params = mapOf("message" to message.toJsonElement())
    )
    producer.send("club-gateway-responses", conversationId, response)
}

