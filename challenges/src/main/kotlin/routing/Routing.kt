package ru.polyZoj.routing

import common.DataPayload
import common.LogSender
import common.kafka.KafkaConfig
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.kafka.topics.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import common.Level
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

fun Application.configureRouting() {
    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists(CHALLENGES_GATEWAY_REQ, 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists(CHALLENGES_GATEWAY_RES, 1, 3.toShort())

    val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    val reqProcessor = RequestProcessor()

    val kafkaProducer = createKafkaProducer()
    val logger = LogSender(kafkaProducer)

    fun log(level: Level, message: String, context: String) {
        logger.log("challenges", level, message, context)
    }
    fun logRequest(context: String) = log(Level.INFO, "Received request", context)
    fun logKafkaSend(context: String, payload: DataPayload) = log(Level.INFO, "Sending request to $CHALLENGES_GATEWAY_REQ: $payload", context)
    suspend fun respondError(
        call: ApplicationCall,
        status: HttpStatusCode,
        message: String,
        context: String
    ) {
        log(Level.ERROR, "Responding ${status.value}: $message", context)
        call.respond(status, message)
    }

    val consumer = createKafkaConsumer("challenges-gateway-consumer")
    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf(CHALLENGES_GATEWAY_RES))
        while (true) {
            val records = consumer.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val responsePayload = record.value()
                        pendingResponses[record.key()]?.complete(responsePayload)
                    } catch (e: Exception) {
                        pendingResponses[record.key()]?.complete(
                            DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Failed to process response: ${e.message}"
                            )
                        )
                    }
                }
            }
        }
    }

    fun verifyJWTandGetUserId(call: ApplicationCall): String? {
        return call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
    }

    routing {
        authenticate("auth-jwt"){
            route("/api/v1/challenges") {

                route("/achievements") {
                    // GET /challenges/{id} - получение всех ачивок
                    get("/{id}") {
                        val id = call.parameters["id"]
                        val staticPath = "GET /api/v1/challenges/achievements/{$id}"
                        val userId = verifyJWTandGetUserId(call)
                            ?: return@get respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)
                        val param = id ?: userId

                        logRequest(staticPath)

                        val requestPayload = DataPayload.build("achievementsAll") {
                            param("user_id", param)
                        }

                        logKafkaSend(staticPath, requestPayload)

                        reqProcessor.processRequest(
                            requestPayload,
                            CHALLENGES_GATEWAY_REQ,
                            call,
                            kafkaProducer,
                            pendingResponses,
                            mutex,
                            ::handleSuccessfulResponse
                        )
                    }

                    // GET /challenges/{id}/{day} - получение ачивок данного дня
                    get("/{id}/{day}") {
                        val idParam = call.parameters["id"]
                        val dayParam = call.parameters["day"]
                        val staticPath = "GET /api/v1/challenges/achievements/{$idParam}/{$dayParam}"
                        val userId = verifyJWTandGetUserId(call)
                            ?: return@get respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)
                        val targetId = idParam ?: userId

                        val date = try {
                            LocalDate.parse(dayParam)
                        } catch (_: DateTimeParseException) {
                            return@get respondError(call, HttpStatusCode.BadRequest, "Invalid date format: $dayParam", staticPath)
                        }

                        logRequest(staticPath)

                        val requestPayload = DataPayload.build("achievementsDay") {
                            param("user_id", targetId)
                            param("date", date)
                        }
                        logKafkaSend(staticPath, requestPayload)

                        reqProcessor.processRequest(
                            requestPayload,
                            CHALLENGES_GATEWAY_REQ,
                            call,
                            kafkaProducer,
                            pendingResponses,
                            mutex,
                            ::handleSuccessfulResponse
                        )
                    }

                    // GET /challenges/{id}/today - получение ачивок за сегодня
                    get("/{id}/today") {
                        val staticPath = "GET /api/v1/challenges/achievements/{id}/today"
                        val idParam = call.parameters["id"]
                        val userId = verifyJWTandGetUserId(call)
                            ?: return@get respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)
                        val targetId = idParam ?: userId

                        logRequest(staticPath)

                        val today = LocalDate.now()
                        val requestPayload = DataPayload.build("achievementsDay") {
                            param("user_id", targetId)
                            param("date", today)
                        }
                        logKafkaSend(staticPath, requestPayload)
                        reqProcessor.processRequest(
                            requestPayload,
                            CHALLENGES_GATEWAY_REQ,
                            call,
                            kafkaProducer,
                            pendingResponses,
                            mutex,
                            ::handleSuccessfulResponse
                        )
                    }

                    post("/add") {
                        val staticPath = "POST /api/v1/challenges/achievements/add"
                        logRequest(staticPath)

                        val userId = verifyJWTandGetUserId(call)
                            ?: return@post respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)

                        val text = call.receiveText()
                        val json = try {
                            Json.parseToJsonElement(text).jsonObject
                        } catch (_: Exception) {
                            return@post respondError(call, HttpStatusCode.BadRequest, "Malformed JSON", staticPath)
                        }

                        val requestPayload = DataPayload.build("createAchievement") {
                            param("user_id", userId) // is not in body
                            param("icon", json["icon"]?.jsonPrimitive?.content)
                            param("description", json["description"]?.jsonPrimitive?.content)
                            param("title", json["title"]?.jsonPrimitive?.content)

                            // can be null, will create IN_PROGRESS as default
                            param("status", json["status"]?.jsonPrimitive?.contentOrNull) // see `common.models.ChallengesModels.AchievementStatus`
                            param("goal", json["goal"]?.jsonPrimitive?.content)
                            param("type", json["type"]?.jsonPrimitive?.content) // see `common.models.ChallengesModels.AchievementType`
                            param("start_date", json["start_date"]?.jsonPrimitive?.content)
                            param("end_date", json["end_date"]?.jsonPrimitive?.content)
                        }

                        logKafkaSend(staticPath, requestPayload)
                        reqProcessor.processRequest(
                            requestPayload,
                            CHALLENGES_GATEWAY_REQ,
                            call,
                            kafkaProducer,
                            pendingResponses,
                            mutex,
                            ::handleSuccessfulResponse
                        )
                    }
                }
            }
        }

    }
}

private suspend fun handleSuccessfulResponse(
    operation: String,
    result: DataPayload,
    call: ApplicationCall
) {
    when (operation) {
        "achievementsAll",
        "achievementsDay",
        "achievementsToday",
        "createAchievement" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type: $operation")
        )
    }
}
