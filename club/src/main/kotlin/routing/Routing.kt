package ru.polyZoj.routing

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
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import ru.polyZoj.models.ClubCreateRequest
import ru.polyZoj.models.ClubMemberRequest
import ru.polyZoj.models.Club
import ru.polyZoj.models.ClubInfoResponse
import ru.polyZoj.models.ClubCreateResponse
import ru.polyZoj.models.ClubMemberResponse
import io.ktor.server.plugins.openapi.*
import org.apache.kafka.clients.producer.KafkaProducer
import common.DataPayload
import common.kafka.KafkaConfig
import common.kafka.KafkaProducerService
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer

fun Application.configureRouting() {
    val json = Json { ignoreUnknownKeys = true }

    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists("club-gateway-requests", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("club-gateway-responses", 1, 3.toShort())

    val responses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    
    val kafkaProducer = createKafkaProducer()
    val producer = KafkaProducerService(kafkaProducer)

    val consumer = createKafkaConsumer("club-gateway-consumer")
    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf("club-gateway-responses"))
        while (true) {
            val records = consumer.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val response = record.value()
                        responses[record.key()]?.complete(response)
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
                            DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Processing error"
                            )
                        )
                    }
                }
            }
        }
    }

    routing {
        openAPI(path="openapi")
        route("/api/v1/clubs") {
            post("/create") {
                val request = call.receive<ClubCreateRequest>()
                val payload = DataPayload.build("create") {
                    param("name", request.name)
                    param("description", request.description)
                    param("ownerId", request.ownerId)
                }
                
                processClubRequest(payload, call, producer, responses, mutex, json)
            }

            get("/list") {     
                val limit = call.parameters["limit"] ?: "10"
                val offset = call.parameters["offset"] ?: "0"
                val payload = DataPayload.build("listClubs") {
                    param("limit", limit.toInt())
                    param("offset", offset.toInt())
                }
                processClubRequest(payload, call, producer, responses, mutex, json)
            }

            post("/{clubId}/members") {
                val clubId = call.parameters["clubId"] ?: throw IllegalArgumentException("Missing club ID")
                val request = call.receive<ClubMemberRequest>()
                val payload = DataPayload.build("addMember") {
                    param("clubId", clubId)
                    param("userId", request.userId)
                }
                processClubRequest(payload, call, producer, responses, mutex, json)
            }

            delete("/{clubId}/members/{userId}") {
                val clubId = call.parameters["clubId"] ?: throw IllegalArgumentException("Missing club ID")
                val userId = call.parameters["userId"] ?: throw IllegalArgumentException("Missing user ID")
                val payload = DataPayload.build("removeMember") {
                    param("clubId", clubId)
                    param("userId", userId)
                }
                processClubRequest(payload, call, producer, responses, mutex, json)
            }

            get("/{clubId}") {
                val clubId = call.parameters["clubId"] ?: throw IllegalArgumentException("Missing club ID")
                val payload = DataPayload.build("getInfo") {
                    param("clubId", clubId)
                }
                processClubRequest(payload, call, producer, responses, mutex, json)
            }
        }
    }
}

private suspend fun processClubRequest(
    payload: DataPayload,
    call: ApplicationCall,
    producer: KafkaProducerService,
    responses: ConcurrentHashMap<String, CompletableDeferred<DataPayload>>,
    mutex: Mutex,
    json: Json
) {
    val correlationId = UUID.randomUUID().toString()
    val responseDeferred = CompletableDeferred<DataPayload>()

    mutex.withLock {
        responses[correlationId] = responseDeferred
    }
    producer.send(
        "club-gateway-requests",
        correlationId,
        payload
    )
    try {
        val result = withTimeoutOrNull(5000) { responseDeferred.await() }

        when {
            result == null -> call.respond(
                HttpStatusCode.GatewayTimeout,
                mapOf("error" to "Club service timeout")
            )

            result.message == "error" -> {
                val status = result.getParam<Int>("status") ?: 400
                val description = result.getParam<String>("description") ?: "Unknown error"
                call.respond(
                    HttpStatusCode.fromValue(status),
                    mapOf("error" to description)
                )
            }

            else -> handleClubResponse(result, call, json)
        }
    } finally {
        mutex.withLock { responses.remove(correlationId) }
    }
}

private suspend fun handleClubResponse(response: DataPayload, call: ApplicationCall, json: Json) {
    when (response.message) {
        "created" -> call.respond(
            HttpStatusCode.Created,
            ClubCreateResponse(
                response.getParam("id") ?: "",
                response.getParam("name") ?: ""
            )
        )

        "clubsList" -> {
            val clubs = response.getParam<List<Club>>("clubs") ?: emptyList()
            call.respond(
                HttpStatusCode.OK,
                mapOf("data" to clubs)
            )
        }

        "memberAdded", "memberRemoved" -> call.respond(
            HttpStatusCode.OK,
            ClubMemberResponse(
                response.message,
                response.getParam("userId") ?: "",
                response.getParam("clubId") ?: ""
            )
        )

        "clubInfo" -> {
            val club = Club(
                response.getParam("id") ?: "",
                response.getParam("name") ?: "",
                response.getParam("description") ?: "",
                response.getParam("ownerId") ?: "",
                response.getParam<MutableSet<String>>("members") ?: mutableSetOf()
            )
            call.respond(
                HttpStatusCode.OK,
                ClubInfoResponse(club)
            )
        }

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unexpected response from club service")
        )
    }
}

