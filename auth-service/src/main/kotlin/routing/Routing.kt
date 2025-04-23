package ru.polyZoj.routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import ru.polyZoj.models.LoginRequest
import ru.polyZoj.models.RegisterRequest
import ru.polyZoj.models.TokenResponse
import common.DataPayload
import common.kafka.RequestProcessor
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.concurrent.ExecutionException
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands

val redisCommands: RedisCommands<String, String> = RedisClient.create("redis://redis:6379").connect().sync()

fun Application.configureRouting() {
    val json = Json { ignoreUnknownKeys = true }
    val producer = KafkaProducer<String, String>(producerConfig())
    val consumer = KafkaConsumer<String, String>(consumerConfig("auth-consumer"))
    val responses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    val reqProcessor = RequestProcessor()
    
    createKafkaTopics()

    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf("auth-responses"))
        while (true) {
            val records = consumer.poll(java.time.Duration.ofMillis(100))
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
        route("/api/v1/auth") {
            post("/register") {
                val request = call.receive<RegisterRequest>()
                val payload = DataPayload(
                    message = "register",
                    params = listOf(request.username, request.password)
                )
                reqProcessor.processAuthRequest(payload, "auth-requests", call, producer, responses, mutex, json, ::handleSuccessfulResponse)
            }

            post("/login") {
                val request = call.receive<LoginRequest>()
                val payload = DataPayload(
                    message = "login",
                    params = listOf(request.username, request.password)
                )
                reqProcessor.processAuthRequest(payload, "auth-requests", call, producer, responses, mutex, json, ::handleSuccessfulResponse)
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
        "register" -> call.respond(
            HttpStatusCode.OK,
            TokenResponse(id = result.message ,token = result.params.get(0))
        )

        "login" -> {

            redisCommands.setex(result.message, 600, result.params.get(0))

            call.respond(
            HttpStatusCode.OK,
            TokenResponse(id = result.message ,token = result.params.get(0))
        )
        }

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type")
        )
    }
}

fun producerConfig(): Properties {
    return Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

        put("acks", "all")
        put("enable.idempotence", "true")
        put("max.in.flight.requests.per.connection", "1")

        put("retries", "5")
        put("linger.ms", "1")
        put("delivery.timeout.ms", "120000")
    }
}

fun consumerConfig(groupId: String): Properties {
    return Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")

        put("group.id", groupId)
        put("auto.offset.reset", "earliest")
        put("enable.auto.commit", "false")

        put("isolation.level", "read_committed")
        put("max.poll.records", "50")

        put("session.timeout.ms", "15000")
        put("heartbeat.interval.ms", "5000")
        put("max.poll.interval.ms", "300000")

    }
}

private fun Application.createKafkaTopics() {
    val adminProps = Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("client.id", "auth-service-admin")
    }

    val admin = AdminClient.create(adminProps)
    
    val topics = listOf(
        NewTopic("auth-requests", 1, 3.toShort())
            .configs(mapOf("min.insync.replicas" to "2")),
        NewTopic("auth-responses", 1, 3.toShort())
            .configs(mapOf("min.insync.replicas" to "2"))
    )

    try {
        admin.createTopics(topics).all().get()
        log.info("Successfully created Kafka topics")
    } catch (e: ExecutionException) {
        if (e.cause is TopicExistsException) {
            log.info("Kafka topics already exist")
        } else {
            log.error("Failed to create Kafka topics: ${e.message}")
        }
    } finally {
        admin.close()
    }
}