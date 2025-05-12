package common

import common.kafka.topics.LOG_REQ
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class LogSender(
    private val producer: KafkaProducer<String, DataPayload>,
    private val topic: String = LOG_REQ,
) {
    private val logger: Logger = LoggerFactory.getLogger(LogSender::class.java)

    fun log(serviceName: String, level: Level, logMessage: String, context: String) {
        val payload = DataPayload.build(logMessage) {
            param("serviceName", serviceName)
            param("level", level.label)
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

enum class Level(val label: String) {
    TRACE("TRACE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    FATAL("FATAL");

    override fun toString(): String = label

    companion object {
        fun fromString(level: String): Level =
            entries.firstOrNull { it.label.equals(level, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown log level: '$level'")
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
