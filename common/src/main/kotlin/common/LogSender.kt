package common

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LogSender(
    private val producer: KafkaProducer<String, DataPayload>,
    private val topic: String,
) {
    private val logger: Logger = LoggerFactory.getLogger(LogSender::class.java)

    fun sendLog(serviceName: String, level: String, logMessage: String, context: String) {
        val payload = DataPayload.build(logMessage) {
            param("serviceName", serviceName)
            param("level", level)
            param("logMessage", logMessage)
            param("context", context)

        }

        try {
            producer.send(ProducerRecord(topic, null, payload))
            logger.info("Log sent to topic '$topic': $payload")
        } catch (e: Exception) {
            logger.error("Failed to send log to topic '$topic'", e)
        }
    }
}

// Usage example
//
// val logSender = LogSender(producer, "log-requests")
//
// logSender.sendLog(
//     serviceName = "user-service",
//     level = "INFO",
//     logMessage = "successfully registered user",
//     context = "registration request"
// )
