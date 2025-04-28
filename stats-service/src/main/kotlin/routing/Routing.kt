package ru.polyZoj.routing

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import ru.polyZoj.models.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.*
import common.DataPayload
import common.kafka.RequestProcessor
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import ru.polyZoj.configs.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun Application.configureRouting() {

    val json = Json { ignoreUnknownKeys = true }
    val producerWrite = KafkaProducer<String, String>(producerConfig())
    val consumerWrite = KafkaConsumer<String, String>(consumerConfig("stats-consumer-write"))
    val producerRead = KafkaProducer<String, String>(producerConfig())
    val consumerRead = KafkaConsumer<String, String>(consumerConfig("stats-consumer-read"))
    val responses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    val reqProcessor = RequestProcessor()
    
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

    routing {
        route("/stats") {
            get("/{user_id}") {
                val authHeader = call.request.headers["Authorization"]
                if (authHeader?.startsWith("Bearer ") != true) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }

                val mockStats = StatsResponse(
                    level = 5,
                    xp = 2450,
                    calorie_count = 1200,
                    water_count = 8,
                    workouts_count = 15,
                    completed_challenges = 3
                )
                
                call.respond(mockStats)
            }

            post("/add") {
                val authHeader = call.request.headers["Authorization"]
                if (authHeader?.startsWith("Bearer ") != true) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }

                val request = call.receive<AddStatsRequest>()

                call.respond(StatusResponse(true))
            }
        }
    }
}