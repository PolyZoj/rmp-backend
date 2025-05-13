package ru.polyZoj.routing

import com.auth0.jwt.interfaces.Claim
import common.DataPayload
import common.kafka.KafkaConfig
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

val log_cool = logger<Application>()

fun Application.configureRouting() {
    val log = logger<Application>()

    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists("challenges-gateway-requests", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("challenges-gateway-responses", 1, 3.toShort())

    val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    val reqProcessor = RequestProcessor()

    val kafkaProducer = createKafkaProducer()

    val consumer = createKafkaConsumer("challenges-gateway-consumer")
    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf("challenges-gateway-responses"))
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
        val principal = call.principal<JWTPrincipal>()
            ?: return null

        val userIdClaim: Claim = principal.payload.getClaim("userId")
        return userIdClaim.asString()
    }


    routing {
        authenticate("auth-jwt"){
            route("/api/v1/challenges") {

                route("/achievements") {
                    // GET /challenges/{id} - получение всех ачивок
                    get("/{id}") {
                        val id = call.parameters["id"]
                        val userId = verifyJWTandGetUserId(call)
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                        val param = id ?: userId
                        log.info("GET /achievements/{}", param)

                        val requestPayload = DataPayload.build("achievementsAll") {
                            param("user_id", userId)
                        }
                        log.info("sending request to challenges-gateway-requests: {}", requestPayload)
                        reqProcessor.processRequest(
                            requestPayload,
                            "challenges-gateway-requests",
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
                        val userId = verifyJWTandGetUserId(call)
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                        val targetId = idParam ?: userId
                        val date = try {
                            LocalDate.parse(dayParam)
                        } catch (e: DateTimeParseException) {
                            return@get call.respond(HttpStatusCode.BadRequest, "Invalid date format: $dayParam")
                        }
                        log.info("GET /achievements/{}/{}", targetId, date)

                        val requestPayload = DataPayload.build("achievementsDay") {
                            param("user_id", targetId)
                            param("date", date)
                        }
                        log.info("sending request to challenges-gateway-requests: {}", requestPayload)
                        reqProcessor.processRequest(
                            requestPayload,
                            "challenges-gateway-requests",
                            call,
                            kafkaProducer,
                            pendingResponses,
                            mutex,
                            ::handleSuccessfulResponse
                        )
                    }

                    // GET /challenges/{id}/today - получение ачивок за сегодня
                    get("/{id}/today") {
                        val idParam = call.parameters["id"]
                        val userId = verifyJWTandGetUserId(call)
                            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                        val targetId = idParam ?: userId
                        val today = LocalDate.now()
                        log.info("GET /achievements/{}/today", targetId)

                        val requestPayload = DataPayload.build("achievementsDay") {
                            param("user_id", targetId)
                            param("date", today)
                        }
                        log.info("sending request to challenges-gateway-requests: {}", requestPayload)
                        reqProcessor.processRequest(
                            requestPayload,
                            "challenges-gateway-requests",
                            call,
                            kafkaProducer,
                            pendingResponses,
                            mutex,
                            ::handleSuccessfulResponse
                        )
                    }

                    post("/add") {
                        log.info("POST /achievements/add")
                        val userId = verifyJWTandGetUserId(call)
                            ?: return@post call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                        val text = call.receiveText()
                        val json = Json.parseToJsonElement(text).jsonObject

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

                        log.info("sending request to challenges-gateway-requests: {}", requestPayload)
                        reqProcessor.processRequest(
                            requestPayload,
                            "challenges-gateway-requests",
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

    log_cool.info(result.params.toString())

    when (operation) {
        "achievementsAll" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "achievementsDay" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "achievementsToday" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

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
