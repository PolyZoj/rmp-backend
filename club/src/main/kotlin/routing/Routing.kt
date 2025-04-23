package ru.polyZog.routing

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
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import ru.polyZog.models.ClubCreateRequest
import ru.polyZog.models.ClubMemberRequest
import ru.polyZog.models.Club
import ru.polyZog.models.DataPayload

fun Application.configureRouting() {
    val json = Json { ignoreUnknownKeys = true }
    val producer = KafkaProducer<String, String>(producerConfig())
    val consumer = KafkaConsumer<String, String>(consumerConfig("club-gateway-consumer"))
    val responses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    
    createClubKafkaTopics()

    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf("club-responses"))
        while (true) {
            val records = consumer.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val response = json.decodeFromString<DataPayload>(record.value())
                        responses[record.key()]?.complete(response)
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
                            DataPayload("error", params = listOf("Processing error"))
                        )
                    }
                }
            }
        }
    }

    routing {
        route("/api/v1/clubs") {
            post("/create") {
                val request = call.receive<ClubCreateRequest>()
                val payload = DataPayload(
                    message = "create",
                    params = listOf(request.name, request.description, request.ownerId)
                )
                
                processClubRequest(payload, call, producer, responses, mutex, json)
            }

            post("/{clubId}/members") {
                val clubId = call.parameters["clubId"] ?: throw IllegalArgumentException("Missing club ID")
                val request = call.receive<ClubMemberRequest>()
                val payload = DataPayload(
                    message = "addMember",
                    params = listOf(clubId, request.userId)
                )
                processClubRequest(payload, call, producer, responses, mutex, json)
            }

            delete("/{clubId}/members/{userId}") {
                val clubId = call.parameters["clubId"] ?: throw IllegalArgumentException("Missing club ID")
                val userId = call.parameters["userId"] ?: throw IllegalArgumentException("Missing user ID")
                val payload = DataPayload(
                    message = "removeMember",
                    params = listOf(clubId, userId)
                )
                processClubRequest(payload, call, producer, responses, mutex, json)
            }

            get("/{clubId}") {
                val clubId = call.parameters["clubId"] ?: throw IllegalArgumentException("Missing club ID")
                val payload = DataPayload(
                    message = "getInfo",
                    params = listOf(clubId)
                )
                processClubRequest(payload, call, producer, responses, mutex, json)
            }
        }
    }
}



private suspend fun processClubRequest(
    payload: DataPayload,
    call: ApplicationCall,
    producer: KafkaProducer<String, String>,
    responses: ConcurrentHashMap<String, CompletableDeferred<DataPayload>>,
    mutex: Mutex,
    json: Json
) {
    val correlationId = UUID.randomUUID().toString()
    val responseDeferred = CompletableDeferred<DataPayload>()

    mutex.withLock {
        responses[correlationId] = responseDeferred
    }
    producer.send(ProducerRecord(
        "club-requests",
        correlationId,
        json.encodeToString(payload)
    ))
    try {
        val result = withTimeoutOrNull(5000) { responseDeferred.await() }

        when {
            result == null -> call.respond(
                HttpStatusCode.GatewayTimeout,
                mapOf("error" to "Club service timeout")
            )

            result.message == "error" -> call.respond(
                HttpStatusCode.BadRequest,
                mapOf<String, String>("error" to (result.params.firstOrNull() ?: "Unknown error"))
            )

            else -> handleClubResponse(result, call)
        }
    } finally {
        mutex.withLock { responses.remove(correlationId) }
    }
}

private suspend fun handleClubResponse(response: DataPayload, call: ApplicationCall) {
    when (response.message) {
        "created" -> call.respond(
            HttpStatusCode.Created,
            mapOf(
                "clubId" to response.params.getOrNull(0),
                "name" to response.params.getOrNull(1)
             )
        )

        "memberAdded", "memberRemoved" -> call.respond(
            HttpStatusCode.OK,
            mapOf(
                "message" to response.message,
                "userId" to response.params.getOrNull(0),
                "clubId" to response.params.getOrNull(1)
            )
        )

        "clubInfo" -> call.respond(
            HttpStatusCode.OK,
            mapOf(
                "id" to response.params.getOrNull(0),
                "name" to response.params.getOrNull(1),
                "description" to response.params.getOrNull(2),
                "ownerId" to response.params.getOrNull(3),
                "members" to response.params.getOrNull(4)
            )
        )

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unexpected response from club service")
        )
    }
}

private fun Application.createClubKafkaTopics() {
    val adminProps = Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("client.id", "club-gateway-admin")
    }

    AdminClient.create(adminProps).use { admin ->
        val topics = listOf(
            NewTopic("club-requests", 1, 3.toShort())
                .configs(mapOf("min.insync.replicas" to "2")),
            NewTopic("club-responses", 1, 3.toShort())
                .configs(mapOf("min.insync.replicas" to "2"))
        )

        try {
            admin.createTopics(topics).all().get()
            println("Club Kafka topics created")
        } catch (e: ExecutionException) {
            if (e.cause !is TopicExistsException) {
                println("Failed to create club topics: ${e.message}")
            }
        }
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