package ru.polyZoj.routing

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.polyZoj.models.LoginRequest
import ru.polyZoj.models.TokenResponse
import common.DataPayload
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.kafka.topics.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.concurrent.ExecutionException
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val redisCommands: RedisCommands<String, String> = RedisClient.create("redis://redis:6379").connect().sync()

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.configureRouting() {
    val log = logger<Application>()
    val producer = createKafkaProducer()
    val consumer = createKafkaConsumer("auth-consumer")
    val responses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    val reqProcessor = RequestProcessor()
    
    createKafkaTopics()

    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf(AUTH_RES))
        while (true) {
            val records = consumer.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        responses[record.key()]?.complete(record.value())
                    } catch (e: Exception) {
                        responses[record.key()]?.complete(
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

    routing {
        route("/api/v1/auth") {
            post("/register") {
                val text = call.receiveText()
                val json = Json
                    .parseToJsonElement(text)
                    .jsonObject

                val payload = DataPayload.build("register") {
                    param("username", json["username"]?.jsonPrimitive?.content)
                    param("password", json["password"]?.jsonPrimitive?.content)
                    param("first_name", json["first_name"]?.jsonPrimitive?.content)
                    param("last_name", json["last_name"]?.jsonPrimitive?.content)
                    param("email", json["email"]?.jsonPrimitive?.content)
                    param("avatar_url", json["avatar_url"]?.jsonPrimitive?.contentOrNull)
                    param("weight", json["weight"]?.jsonPrimitive?.float)
                    param("height", json["height"]?.jsonPrimitive?.int)
                    param("birth_date", json["birth_date"]?.jsonPrimitive?.content)
                    param("unit_system", json["unit_system"]?.jsonPrimitive?.content)
                    param("energy_system", json["energy_system"]?.jsonPrimitive?.content)
                    param("health_goal", json["health_goal"]?.jsonPrimitive?.contentOrNull)
                    param("daily_step_goal", json["daily_step_goal"]?.jsonPrimitive?.intOrNull)
                    param("water_intake_goal", json["water_intake_goal"]?.jsonPrimitive?.intOrNull)
                    param("calorie_goal", json["calorie_goal"]?.jsonPrimitive?.intOrNull)
                    param("sleep_goal", json["sleep_goal"]?.jsonPrimitive?.floatOrNull)
                    param("workouts_goal", json["workouts_goal"]?.jsonPrimitive?.intOrNull)
                }
                reqProcessor.processRequest(payload, AUTH_REQ, call, producer, responses, mutex, ::handleSuccessfulResponse)
            }

            post("/login") {
                val request = call.receive<LoginRequest>()
                val payload = DataPayload.build("login") {
                    param("username", request.username)
                    param("password", request.password)
                }
                reqProcessor.processRequest(payload, AUTH_REQ, call, producer, responses, mutex, ::handleSuccessfulResponse)
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
        "register" -> {
            val userId = result.getParam<String>("user_id") ?: ""
            call.respond(
                HttpStatusCode.OK,
                TokenResponse(id = userId ,token = result.getParam("token") ?: "")
            )
        }

        "login" -> {
            val token = result.getParam("token") ?: ""
            val userId = result.getParam("user_id") ?: ""
            redisCommands.setex(userId, 600, token)

            call.respond(
            HttpStatusCode.OK,
            TokenResponse(id = userId ,token = token)
            )
        }

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type")
        )
    }
}

private fun Application.createKafkaTopics() {
    val adminProps = Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("client.id", "auth-service-admin")
    }

    val admin = AdminClient.create(adminProps)
    
//    val topics = listOf(
//        NewTopic("auth-requests", 1, 3.toShort())
//            .configs(mapOf("min.insync.replicas" to "2")),
//        NewTopic("auth-responses", 1, 3.toShort())
//            .configs(mapOf("min.insync.replicas" to "2"))
//    )
    // TODO: set above for production
    val topics = listOf(
        NewTopic(AUTH_REQ, 1, 1.toShort())
            .configs(mapOf("min.insync.replicas" to "1")),
        NewTopic(AUTH_RES, 1, 1.toShort())
            .configs(mapOf("min.insync.replicas" to "1"))
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