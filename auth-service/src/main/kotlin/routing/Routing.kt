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
import ru.polyZoj.models.RegisterRequest
import ru.polyZoj.models.TokenResponse
import common.DataPayload
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
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
    val producer = createKafkaProducer()
    val consumer = createKafkaConsumer("auth-consumer")
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
                val request = call.receive<RegisterRequest>()
                // TODO: Нужны все данные для регистрации, не только user pass
                val payload = DataPayload.build("register") {
                    param("username", request.username)
                    param("password", request.password)
                }
                reqProcessor.processRequest(payload, "auth-requests", call, producer, responses, mutex, ::handleSuccessfulResponse)
            }

            post("/login") {
                val request = call.receive<LoginRequest>()
                val payload = DataPayload.build("login") {
                    param("username", request.username)
                    param("password", request.password)
                }
                reqProcessor.processRequest(payload, "auth-requests", call, producer, responses, mutex, ::handleSuccessfulResponse)
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
            TokenResponse(id = result.message ,token = result.getParam("token") ?: "")
        )

        "login" -> {
            val token = result.getParam("token") ?: ""
            redisCommands.setex(result.message, 600, token)

            call.respond(
            HttpStatusCode.OK,
            TokenResponse(id = result.message ,token = token)
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
        NewTopic("auth-requests", 1, 1.toShort())
            .configs(mapOf("min.insync.replicas" to "1")),
        NewTopic("auth-responses", 1, 1.toShort())
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