package ru.polyZoj

import common.DataPayload
import common.kafka.KafkaConfig
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.models.Achievement
import common.models.AchievementStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.polyZoj.logic.AchievementCalculator
import ru.polyZoj.logic.StatsClient
import ru.polyZoj.logic.consumerConfig
import ru.polyZoj.logic.producerConfig
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    val statsProducerService = KafkaProducer<String, String>(producerConfig())
    val statsConsumerService = KafkaConsumer<String, String>(consumerConfig("challenges-stats-consumer"))

    val statsClient = StatsClient(statsProducerService, statsConsumerService)
    val calculator = AchievementCalculator(statsClient)

    // payloads from challenges-interface
    val challengesResponseConsumer = KafkaConsumerService(createKafkaConsumer("challenges-service-consumer"), listOf("challenges-responses"))
    challengesResponseConsumer.startConsuming { conversationId, message ->
        log.info("Received response: $message")
        pendingResponses[conversationId]?.complete(message)
        pendingResponses.remove(conversationId)
    }

    fun handleAchievementsRequest(data: DataPayload, conversationId: String, userId: String) {
        val future = CompletableFuture<DataPayload>()
        pendingResponses[conversationId] = future

        log.info("Sending request to challenges-requests: $data")
        producerService.send("challenges-requests", conversationId, data)

        future.orTimeout(5, TimeUnit.SECONDS).whenComplete { response, error ->
            if (error != null || response.params.isEmpty() || response.message == "error") {
                log.warn("Received from challenges-interface: $response")
                val msg = if (response.message == "error") {
                    response
                } else {
                    DataPayload.error(
                        status = HttpStatusCode.InternalServerError,
                        description = "Error from challenges-interface",
                    )
                }
                producerService.send("challenges-gateway-responses", conversationId, msg)
                return@whenComplete
            }

            log.info("Received from challenges-interface: $response")
            val achievements = response.getParam<List<Achievement>>("achievements")
            val updated = achievements?.map { orig ->
                val wasCompleted = orig.status == AchievementStatus.COMPLETED
                val new = calculator.evaluateAndUpdate(userId = userId, orig)
                val isCompleted = new.status == AchievementStatus.COMPLETED
                if (isCompleted != wasCompleted) {
                    producerService.send(
                        "challenges-requests",
                        UUID.randomUUID().toString(),
                        DataPayload.build("completeAchievement") {
                            param("achievement_id", orig.id)
                        }

                    )
                }
                new
            }
            producerService.send(
                "challenges-gateway-responses",
                conversationId,
                DataPayload.build("success") {
                    param("achievements", updated)
                })
        }
    }

    // payloads from challenges-gateway
    val challengesConsumer = KafkaConsumerService(createKafkaConsumer("challenges-service-consumer"), listOf("challenges-gateway-requests"))
    challengesConsumer.startConsuming { conversationId, data ->
        log.info("Received challenges request: $data")
        val command = data.message
        when (command) {

            "achievementsAll" -> {
                val userId = data.getParam<String>("user_id")
                if (userId == null){
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user id"
                    )
                    producerService.send("challenges-gateway-responses", conversationId, err)
                    return@startConsuming
                }

                handleAchievementsRequest(data, conversationId, userId)
            }

            "achievementsDay" -> {
                val userId = data.getParam<String>("user_id")
                val date = data.getParam<LocalDate>("date")
                if (userId == null || date == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing required fields"
                    )
                    producerService.send("challenges-gateway-responses", conversationId, err)
                    return@startConsuming
                }

                handleAchievementsRequest(data, conversationId, userId)
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
