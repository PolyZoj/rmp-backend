package ru.polyZoj.utils

import ru.polyZoj.models.DataPayload
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.*
import java.sql.Timestamp
import java.time.Instant
import common.Level
import common.LogSender
import common.kafka.createKafkaProducer

object ReadEvents {
    const val SELECT_SQL = """
        SELECT event_type, SUM(value) as total 
        FROM ${WriteEvents.TABLE_NAME} 
        WHERE user_id = ?
        GROUP BY event_type
    """
}

public class StatsReader(
    private val connection: Connection,
    private val producer: KafkaProducer<String, String>
) {

    val kafkaProducer = createKafkaProducer()
    val logger = LogSender(kafkaProducer)

    fun log(level: Level, message: String, context: String) {
        logger.log("user", level, message, context)
    }

    fun logRequest(context: String) =
        log(Level.INFO, "Received request", context)

    fun logKafkaSend(context: String, payload: DataPayload, topic: String) =
        log(Level.INFO, "Sending request to $topic: $payload", context)

    private val json = Json { ignoreUnknownKeys = true }

    fun processReadEvent(key: String, value: String) {
        try {

            logRequest("Stats read $key")

            val payload = json.decodeFromString<DataPayload>(value)
            
            if (payload.params.size != 1) {
                sendError(key, "Invalid params count")
                return
            }

            val userId = payload.params[0]
            val statsMap = mutableMapOf<String, Float>()

            connection.prepareStatement(ReadEvents.SELECT_SQL).use { stmt ->
                stmt.setString(1, userId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val type = rs.getString("event_type")
                    val total = rs.getFloat("total")
                    statsMap[type] = total
                }
            }

            val response = DataPayload(
                "success",
                listOf(
                    userId,
                    (statsMap["level"]?.toInt() ?: 0).toString(),
                    (statsMap["xp"]?.toInt() ?: 0).toString(),
                    (statsMap["steps"]?.toInt() ?: 0).toString(),
                    (statsMap["calorie"]?.toInt() ?: 0).toString(),
                    (statsMap["water"]?.toInt() ?: 0).toString(),
                    (statsMap["workout"]?.toInt() ?: 0).toString(),
                    (statsMap["challenge"]?.toInt() ?: 0).toString()
                )
            )

            logKafkaSend("Stats read $key", response, "stats-resp-read")

            producer.send(ProducerRecord(
                "stats-resp-read",
                key,
                json.encodeToString(response)
            ))
            
        } catch (e: Exception) {
            sendError(key, "Read error: ${e.message}")
        }
    }

    private fun sendError(key: String, error: String) {
        producer.send(ProducerRecord(
            "stats-resp-read",
            key,
            json.encodeToString(DataPayload("error", listOf(error)))
        ))
    }
}