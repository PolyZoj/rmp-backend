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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.configureRouting() {
    val log = logger<Application>()

    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists("user-gateway-requests", 1, 3.toShort()) // TODO CHANGE
    kafkaConfig.createTopicIfNotExists("user-gateway-responses", 1, 3.toShort()) // TODO CHANGE

    val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    val reqProcessor = RequestProcessor()

    val kafkaProducer = createKafkaProducer()

    val consumer = createKafkaConsumer("user-gateway-consumer") // TODO CHANGE
    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf("user-responses")) // TODO CHANGE
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
            route("/api/v1/users") { // TODO CHANGE

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
        "userInfo" -> call.respond( // TODO CHANGE
            HttpStatusCode.OK,
            result.params
        )

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type")
        )
    }
}
