package ru.polyZoj.routing

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import ru.polyZoj.models.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.*
import ru.polyZoj.models.DataPayload
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import ru.polyZoj.configs.*
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import org.slf4j.LoggerFactory
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import com.auth0.jwt.interfaces.Claim
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val logger = LoggerFactory.getLogger("StatsService")

fun Application.configureRouting() {

    val json = Json { ignoreUnknownKeys = true }
    val producerWrite = KafkaProducer<String, String>(producerConfig())
    val consumerWrite = KafkaConsumer<String, String>(consumerConfig("stats-consumer-write"))
    val producerRead = KafkaProducer<String, String>(producerConfig())
    val consumerRead = KafkaConsumer<String, String>(consumerConfig("stats-consumer-read"))
    val producerReadDaily = KafkaProducer<String, String>(producerConfig())
    val consumerReadDaily = KafkaConsumer<String, String>(consumerConfig("stats-consumer-read-daily"))
    val responses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    
    createKafkaTopics()

    CoroutineScope(Dispatchers.IO).launch {
        consumerWrite.subscribe(listOf("stats-resp-write"))
        while (true) {
            val records = consumerWrite.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val responsePayload = json.decodeFromString<DataPayload>(record.value())
                        responses[record.key()]?.complete(responsePayload)
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
                            DataPayload("error", listOf())
                        )
                    }
                }
            }
        }
    }

    CoroutineScope(Dispatchers.IO).launch {
        consumerRead.subscribe(listOf("stats-resp-read"))
        while (true) {
            val records = consumerRead.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val responsePayload = json.decodeFromString<DataPayload>(record.value())
                        responses[record.key()]?.complete(responsePayload)
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
                            DataPayload("error", listOf())
                        )
                    }
                }
            }
        }
    }

    CoroutineScope(Dispatchers.IO).launch {
        consumerReadDaily.subscribe(listOf("stats-resp-read-daily"))
        while (true) {
            val records = consumerReadDaily.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val responsePayload = json.decodeFromString<DataPayload>(record.value())
                        responses[record.key()]?.complete(responsePayload)
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
                            DataPayload("error", listOf())
                        )
                    }
                }
            }
        }
    }


    CoroutineScope(Dispatchers.IO).launch {
        consumerRead.subscribe(listOf("stats-resp-read-daily"))
        while (true) {
            val records = consumerRead.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val responsePayload = json.decodeFromString<DataPayload>(record.value())
                        responses[record.key()]?.complete(responsePayload)
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
                            DataPayload("error", listOf())
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
            route("/stats") {
                get("/{user_id}") {
                    val requestId = UUID.randomUUID().toString()

                    val userId = call.parameters["user_id"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing user_id"))
                        return@get
                    }    

                    val payload = DataPayload(
                        message = "get_stats",
                        params = listOf(userId)
                    )

                    logger.info(json.encodeToString(payload))

                    producerRead.send(ProducerRecord(
                        "stats-req-read",
                        requestId,
                        json.encodeToString(payload)
                    ))

                    val response = withTimeoutOrNull(5000) {
                        CompletableDeferred<DataPayload>().apply {
                            responses[requestId] = this
                        }.await()
                    }

                    when {
                        response == null -> call.respond(
                            HttpStatusCode.GatewayTimeout,
                            DataPayload("timeout", emptyList())
                        )
                        response.message == "error" -> call.respond(
                            HttpStatusCode.InternalServerError,
                            response
                        )
                        else -> {

                            logger.info(response.params.toString())

                            val stats = parseStatsResponse(response)
                            call.respond(stats)
                        }
                    }
                }

                get("/daily/{user_id}/{date}") {
                    val requestId = UUID.randomUUID().toString()
                    
                    val userId = call.parameters["user_id"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing user_id"))
                        return@get
                    }
                    
                    val dateString = call.parameters["date"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing date"))
                        return@get
                    }

                    try {
                        LocalDate.parse(dateString, DateTimeFormatter.ISO_DATE)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid date format. Use YYYY-MM-DD"))
                        return@get
                    }

                    val payload = DataPayload(
                        message = "get_daily_stats",
                        params = listOf(userId, dateString)
                    )

                    producerReadDaily.send(ProducerRecord(
                        "stats-req-read-daily",
                        requestId,
                        json.encodeToString(payload)
                    ))

                    val response = withTimeoutOrNull(5000) {
                        CompletableDeferred<DataPayload>().apply {
                            responses[requestId] = this
                        }.await()
                    }

                    when {
                        response == null -> call.respond(
                            HttpStatusCode.GatewayTimeout,
                            DataPayload("timeout", emptyList())
                        )
                        response.message == "error" -> call.respond(
                            HttpStatusCode.InternalServerError,
                            response
                        )
                        else -> {
                            val stats = parseDailyStatsResponse(response)
                            call.respond(stats)
                        }
                    }
                }

                post("/add") {
                    val requestId = UUID.randomUUID().toString()
                    val request = call.receive<AddStatsRequest>()

                    val payload = DataPayload(
                        message = "add_stats",
                        params = listOf(
                            request.id,
                            request.type,
                            request.add.toString()
                        )
                    )

                    producerWrite.send(ProducerRecord(
                        "stats-req-write",
                        requestId,
                        json.encodeToString(payload)
                    ))

                    val response = withTimeoutOrNull(5000) {
                        CompletableDeferred<DataPayload>().apply {
                            responses[requestId] = this
                        }.await()
                    }

                    when {
                        response == null -> call.respond(
                            HttpStatusCode.GatewayTimeout,
                            DataPayload("timeout", emptyList())
                        )
                        response.message == "success" -> call.respond(response)
                        else -> call.respond(
                            HttpStatusCode.InternalServerError,
                            response
                        )
                    }
                }
            }
        }
    }
}

private fun parseStatsResponse(response: DataPayload): StatsResponse {
    return try {
        StatsResponse(
            level = response.params[1].toInt(),
            xp = response.params[2].toInt(),
            calorie_count = response.params[3].toInt(),
            water_count = response.params[4].toInt(),
            workouts_count = response.params[5].toInt(),
            completed_challenges = response.params[6].toInt()
        )
    } catch (e: Exception) {
        StatsResponse(
            level = 0,
            xp = 0,
            calorie_count = 0,
            water_count = 0,
            workouts_count = 0,
            completed_challenges = 0
        )
    }
}

private fun parseDailyStatsResponse(response: DataPayload): DailyStatsResponse {
    return try {
        DailyStatsResponse(
            date = response.params[1],
            level = response.params[2].toInt(),
            xp = response.params[3].toInt(),
            calorie_count = response.params[4].toInt(),
            water_count = response.params[5].toInt(),
            workouts_count = response.params[6].toInt(),
            completed_challenges = response.params[7].toInt()
        )
    } catch (e: Exception) {
        logger.error("Error parsing daily stats: ${e.message}")
        DailyStatsResponse(
            date = "error",
            level = 0,
            xp = 0,
            calorie_count = 0,
            water_count = 0,
            workouts_count = 0,
            completed_challenges = 0
        )
    }
}