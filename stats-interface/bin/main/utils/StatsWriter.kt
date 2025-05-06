package ru.polyZoj.utils

import common.DataPayload
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

object WriteEvents {
    const val TABLE_NAME = "default.user_stats"
    const val INSERT_SQL = """
        INSERT INTO $TABLE_NAME 
        (id, user_id, event_type, value, timestamp)
        VALUES (?, ?, ?, ?, ?)
    """
}

class StatsWriter(
    private val connection: Connection,
    private val producer: KafkaProducer<String, String>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val insertStmt: PreparedStatement by lazy {
        connection.prepareStatement(WriteEvents.INSERT_SQL)
    }

    fun processWriteEvent(key: String, value: String) {
        try {
            val payload = json.decodeFromString<DataPayload>(value)

            if (payload.params.size != 3) {
                sendError(key, "Invalid params count")
                return
            }

            val (userId, type, valueStr) = payload.params
            val eventValue = valueStr.toDoubleOrNull() ?: run {
                sendError(key, "Invalid value format")
                return
            }

            insertStmt.apply {
                setString(1, UUID.randomUUID().toString())
                setString(2, userId)
                setString(3, type)
                setFloat(4, eventValue.toFloat())
                setString(5, Timestamp.from(Instant.now()).toString())
                executeUpdate()
            }

            sendSuccess(key)
        } catch (e: SerializationException) {
            sendError(key, "Invalid JSON format: ${e.message}")
        } catch (e: Exception) {
            sendError(key, "Database error: ${e.message}")
        }
    }

    private fun sendSuccess(key: String) {
        producer.send(ProducerRecord(
            "stats-resp-write",
            key,
            json.encodeToString(DataPayload("success", emptyList()))
        ))
    }

    private fun sendError(key: String, error: String) {
        producer.send(ProducerRecord(
            "stats-resp-write",
            key,
            json.encodeToString(DataPayload("error", listOf(error))))
        )
    }
}