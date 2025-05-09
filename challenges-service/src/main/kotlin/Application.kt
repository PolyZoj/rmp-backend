package ru.polyZoj

import common.DataPayload
import common.kafka.KafkaConfig
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

val pendingResponses = ConcurrentHashMap<String, CompletableFuture<DataPayload>>()


fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.module() {
    val log = logger<Application>()

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }

    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists("challenges-requests", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("challenges-responses", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("challenges-stats-requests", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("challenges-stats-responses", 1, 3.toShort())

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    // payloads from challenges-interface
    val challengesResponseConsumer = KafkaConsumerService(createKafkaConsumer("challenges-service-consumer"), listOf("challenges-responses"))
    challengesResponseConsumer.startConsuming { conversationId, message ->
        log.info("Received response: $message")
        pendingResponses[conversationId]?.complete(message)
        pendingResponses.remove(conversationId)
    }

    // payloads from stats-interface
    val statsResponseConsumer = KafkaConsumerService(createKafkaConsumer("challenges-service-consumer"), listOf("challenges-stats-responses"))

    // payloads from challenges-gateway
    val challengesConsumer = KafkaConsumerService(createKafkaConsumer("challenges-service-consumer"), listOf("challenges-gateway-requests"))
    challengesConsumer.startConsuming { conversationId, data ->
        log.info("Received challenges request: $data")
        val command = data.message
        when (command) {

            "achievementsAll" -> {

            }

            "achievementsDay" -> {

            }

            "achievementsToday" -> {

            }

            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                log.warn("Unknown command: $command" ,"\n", "sending to challenges-gateway-responses: $msg")
                producerService.send("challenges-gateway-responses", conversationId, msg)
            }
        }
    }
}
