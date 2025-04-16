package ru.polyZog.routing

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import ru.polyZog.models.LoginRequest
import ru.polyZog.models.RegisterRequest
import ru.polyZog.models.TokenResponse
import ru.polyZog.models.User
import ru.polyZog.repositories.UserDataSource
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import ru.polyZog.models.DataPayload
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.concurrent.ExecutionException


fun Application.configureRouting() {
    val json = Json { ignoreUnknownKeys = true }
    val producer = KafkaProducer<String, String>(kafkaConfig("auth-producer"))
    val consumer = KafkaConsumer<String, String>(kafkaConfig("auth-consumer"))
    val responses = ConcurrentHashMap<String, CompletableDeferred<String>>()
    val mutex = Mutex()
    
    createKafkaTopics()

    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf("auth-responses"))
        while (true) {
            val records = consumer.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val responsePayload = json.decodeFromString<DataPayload>(record.value())
                        responses[record.key()]?.complete(responsePayload.message)
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
                            "error"
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
                processAuthRequest(payload, call, producer, responses, mutex, json)
            }

            post("/login") {
                val request = call.receive<LoginRequest>()
                val payload = DataPayload(
                    message = "login",
                    params = listOf(request.username, request.password)
                )
                processAuthRequest(payload, call, producer, responses, mutex, json)
            }
        }
    }
}

private suspend fun processAuthRequest(
    payload: DataPayload,
    call: ApplicationCall,
    producer: KafkaProducer<String, String>,
    responses: ConcurrentHashMap<String, CompletableDeferred<String>>,
    mutex: Mutex,
    json: Json
) {
    val correlationId = UUID.randomUUID().toString()
    val responseDeferred = CompletableDeferred<String>()

    mutex.withLock {
        responses[correlationId] = responseDeferred
    }

    producer.send(ProducerRecord(
        "auth-requests",
        correlationId,
        json.encodeToString(payload)
    ))

    try {
        val result = withTimeoutOrNull(5000) { responseDeferred.await() }
        when {
            result == null -> call.respond(
                HttpStatusCode.GatewayTimeout,
                mapOf("error" to "Authentication service timeout")
            )

            result.startsWith("error:") -> call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to result.removePrefix("error:"))
            )

            else -> handleSuccessfulResponse(payload.message, result, call)
        }
    } finally {
        mutex.withLock { responses.remove(correlationId) }
    }
}

suspend private fun handleSuccessfulResponse(
    operation: String,
    result: String,
    call: ApplicationCall
) {
    when (operation) {
        "register" -> call.respond(
            HttpStatusCode.Created,
            mapOf("message" to "User created successfully")
        )

        "login" -> {

            call.respond(
            HttpStatusCode.OK,
            TokenResponse(token = result)
        )
        }

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type")
        )
    }
}

fun kafkaConfig(groupId: String): Properties {
    return Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("group.id", groupId)
        put("auto.offset.reset", "earliest")
        put("enable.auto.commit", "true")
        put("max.poll.records", "100")
    }
}

private fun Application.createKafkaTopics() {
    val adminProps = Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("client.id", "auth-service-admin")
    }

    val admin = AdminClient.create(adminProps)
    
    val topics = listOf(
        NewTopic("auth-requests", 3, 1.toShort()),
        NewTopic("auth-responses", 3, 1.toShort())
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