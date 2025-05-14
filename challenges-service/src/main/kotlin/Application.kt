package ru.polyZoj

import common.DataPayload
import common.Level
import common.LogSender
import common.kafka.KafkaConfig
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.kafka.topics.*
import common.models.Achievement
import common.models.AchievementStatus
import common.models.AchievementType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
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


object AppScopes {
    /** Detached from individual requests, cancelled only on shutdown. */
    val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {

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
    kafkaConfig.createTopicIfNotExists(CHALLENGES_SERVICE_REQ, 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists(CHALLENGES_SERVICE_RES, 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists(CHALLENGES_STATS_REQ, 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists(CHALLENGES_STATS_RES, 1, 3.toShort())

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val statsProducerService = KafkaProducer<String, String>(producerConfig())
    val statsConsumerService = KafkaConsumer<String, String>(consumerConfig("challenges-stats-consumer"))

    val statsClient = StatsClient(statsProducerService, statsConsumerService)

    val logger = LogSender(kafkaProducer)
    val calculator = AchievementCalculator(statsClient, logger)

    fun log(level: Level, message: String, context: String) {
        AppScopes.loggerScope.launch(Dispatchers.IO) {
            logger.log("challenges-service", level, message, context)
        }
    }

    fun logInfo(ctx: String, msg: String) = log(Level.INFO, msg, ctx)
    fun logError(ctx: String, msg: String) = log( Level.ERROR, msg, ctx)


    // payloads from challenges-interface
    val challengesResponseConsumer = KafkaConsumerService(createKafkaConsumer("challenges-service-consumer"), listOf(CHALLENGES_SERVICE_RES))
    challengesResponseConsumer.startConsuming { conversationId, message ->
        pendingResponses[conversationId]?.complete(message)
        pendingResponses.remove(conversationId)
    }

    fun handleAchievementsRequest(
        data: DataPayload,
        conversationId: String,
        userId: String,
        command: String
    ) {
        val future = CompletableFuture<DataPayload>()
        pendingResponses[conversationId] = future

        logInfo(command, "Sending request to $CHALLENGES_SERVICE_REQ: $data")
        producerService.send(CHALLENGES_SERVICE_REQ, conversationId, data)

        future.orTimeout(5, TimeUnit.SECONDS).whenComplete { response, error ->
            if (error != null || response.params.isEmpty() || response.message == "error") {
                logError(command, "Received from challenges-interface: $response")
                val msg = if (response.message == "error") {
                    response
                } else {
                    DataPayload.error(
                        status = HttpStatusCode.InternalServerError,
                        description = "Error from challenges-interface",
                    )
                }
                producerService.send(CHALLENGES_GATEWAY_RES, conversationId, msg)
                return@whenComplete
            }

            logInfo(command, "Received from challenges-interface: $response")
            val achievements = response.getParam<List<Achievement>>("achievements")
            val updated = achievements?.map { orig ->
                val wasCompleted = orig.status == AchievementStatus.COMPLETED
                val new = calculator.evaluateAndUpdate(userId = userId, orig)
                val isCompleted = new.status == AchievementStatus.COMPLETED
                if (isCompleted != wasCompleted) {
                    producerService.send(
                        CHALLENGES_SERVICE_REQ,
                        UUID.randomUUID().toString(),
                        DataPayload.build("completeAchievement") {
                            param("achievement", orig)
                        }

                    )
                }
                new
            }
            logInfo(command, "Sending to $CHALLENGES_GATEWAY_RES: $updated")
            producerService.send(
                CHALLENGES_GATEWAY_RES,
                conversationId,
                DataPayload.build("success") {
                    param("achievements", updated)
                })
        }
    }

    // payloads from challenges-gateway
    val challengesConsumer = KafkaConsumerService(createKafkaConsumer("challenges-service-consumer"), listOf(CHALLENGES_GATEWAY_REQ))
    challengesConsumer.startConsuming { conversationId, data ->
        val command = data.message
        logInfo(command, "Received request: $data")
        when (command) {

            "achievementsAll" -> {
                val userId = data.getParam<String>("user_id")
                if (userId == null){
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing user id"
                    )
                    logError(command, "Sending to $CHALLENGES_GATEWAY_RES: $err")
                    producerService.send(CHALLENGES_GATEWAY_RES, conversationId, err)
                    return@startConsuming
                }

                handleAchievementsRequest(data, conversationId, userId, command)
            }

            "achievementsDay" -> {
                val userId = data.getParam<String>("user_id")
                val date = data.getParam<LocalDate>("date")
                if (userId == null || date == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing required fields"
                    )
                    logError(command, "Sending to $CHALLENGES_GATEWAY_RES: $err")
                    producerService.send(CHALLENGES_GATEWAY_RES, conversationId, err)
                    return@startConsuming
                }

                handleAchievementsRequest(data, conversationId, userId, command)
            }

            "createAchievement" -> {
                var achievement: Achievement?
                try {
                    achievement = Achievement(
                        userId = data.getParam<String>("user_id")!!,
                        icon = data.getParam<String>("icon")!!,
                        description = data.getParam<String>("description")!!,
                        title = data.getParam<String>("title")!!,
                        status = data.getParam<AchievementStatus>("status")!!,
                        goal = data.getParam<Double>("goal")!!,
                        type = data.getParam<AchievementType>("type")!!,
                        startDate = data.getParam<LocalDate>("start_date")!!,
                        endDate = data.getParam<LocalDate>("end_date")!!
                    )
                } catch (e: Exception) {
                    val msg = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Could not create achievement: ${e.message}",
                    )
                    logError(command, "Sending to $CHALLENGES_GATEWAY_RES: $msg")
                    producerService.send(CHALLENGES_GATEWAY_RES, conversationId, msg)
                    return@startConsuming
                }
                val dataPayload = DataPayload.build("createAchievement") {
                    param("achievement", achievement)
                }

                val future = CompletableFuture<DataPayload>()
                pendingResponses[conversationId] = future

                logInfo(command, "Sending request to $CHALLENGES_GATEWAY_REQ: $dataPayload")
                producerService.send(CHALLENGES_SERVICE_REQ, conversationId, dataPayload)

                future.orTimeout(5, TimeUnit.SECONDS).whenComplete { response, error ->
                    if (error != null || response.params.isEmpty() || response.message == "error") {
                        logError(command, "Received from challenges-interface: $response")
                        val msg = if (response.message == "error") {
                            response
                        } else {
                            DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Error from challenges-interface",
                            )
                        }
                        producerService.send(CHALLENGES_GATEWAY_RES, conversationId, msg)
                        return@whenComplete
                    }

                    logInfo(command, "Received from challenges-interface: $response")
                    val ach = response.getParam<Achievement>("achievement")
                    producerService.send(
                        CHALLENGES_GATEWAY_RES,
                        conversationId,
                        DataPayload.build("success") {
                            param("achievement", ach)
                        }
                    )
                }
            }

            else -> {
                val msg = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command"
                )
                logError(command, "Unknown command: $command \n Sending to $CHALLENGES_GATEWAY_RES: $msg")
                producerService.send(CHALLENGES_GATEWAY_RES, conversationId, msg)
            }
        }
    }
}
