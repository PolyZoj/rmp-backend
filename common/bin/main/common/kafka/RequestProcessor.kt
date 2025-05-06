package common.kafka

import common.DataPayload
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class RequestProcessor {
    suspend fun processAuthRequest(
        payload: DataPayload,
        topic: String,
        call: ApplicationCall,
        producer: KafkaProducer<String, String>,
        responses: ConcurrentHashMap<String, CompletableDeferred<DataPayload>>,
        mutex: Mutex,
        json: Json,
        handleSuccessfulResponse: suspend (String, DataPayload, ApplicationCall) -> Unit
    ) {
        val correlationId = UUID.randomUUID().toString()
        val responseDeferred = CompletableDeferred<DataPayload>()

        mutex.withLock {
            responses[correlationId] = responseDeferred
        }

        producer.send(ProducerRecord(
            topic,
            correlationId,
            json.encodeToString(payload)
        ))

        try {
            val result = withTimeoutOrNull(5000) { responseDeferred.await() }

            when {
                result == null -> call.respond(
                    HttpStatusCode.GatewayTimeout,
                    mapOf("error" to "Service timeout")
                )

                result.message.startsWith("error:") -> call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to result.message.removePrefix("error:"))
                )

                else -> handleSuccessfulResponse(payload.message, result, call)
            }
        } finally {
            mutex.withLock { responses.remove(correlationId) }
        }
    }
}