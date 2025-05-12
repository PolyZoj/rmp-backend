package common

import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LogSender(
    private val producer: KafkaProducer<String, String>,
    private val topic: String,
    private val json: Json
) {
    private val logger: Logger = LoggerFactory.getLogger(LogSender::class.java)

    fun sendLog(serviceName: String, level: String, logMessage: String, context: String) {
        val logPayload = LogPayload(
            serviceName = serviceName,
            level = level,
            logMessage = logMessage,
            context = context
        )

        try {
            val message = json.encodeToString(LogPayload.serializer(), logPayload)
            producer.send(ProducerRecord(topic, null, message))
            logger.info("Log sent to topic '$topic': $message")
        } catch (e: Exception) {
            logger.error("Failed to send log to topic '$topic'", e)
        }
    }
}

// Usage example
//
// val logSender = LogSender(producer, "log-requests", Json { prettyPrint = true })
//
// logSender.sendLog(
//     serviceName = "user-service",
//     level = "INFO",
//     logMessage = "successfully registered user",
//     context = "registration request"
// )
