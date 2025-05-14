package common.kafka

import common.DataPayload
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

open class RequestProcessor {
    open suspend fun processRequest(
        payload: DataPayload,
        topic: String,
        call: ApplicationCall,
        producer: KafkaProducer<String, DataPayload>,
        responses: ConcurrentHashMap<String, CompletableDeferred<DataPayload>>,
        mutex: Mutex,
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
            payload
        ))

        try {
            val resultPayload = withTimeoutOrNull(5000) { responseDeferred.await() }

            when {
                resultPayload == null -> call.respond(
                    HttpStatusCode.GatewayTimeout,
                    mapOf("error" to "Service timeout")
                )

                /** See `DataPayload.error` */
                resultPayload.message.startsWith("error") -> {
                    val httpStatusCode = resultPayload.getParam<Int>("status")
                        ?: 500
                    val errorMessage = resultPayload.getParam<String>("description") ?: "Unknown error"
                    call.respond(
                        HttpStatusCode.fromValue(httpStatusCode),
                        mapOf("error" to errorMessage)
                    )
                }


                else -> handleSuccessfulResponse(payload.message, resultPayload, call)
            }
        } finally {
            mutex.withLock { responses.remove(correlationId) }
        }
    }
}