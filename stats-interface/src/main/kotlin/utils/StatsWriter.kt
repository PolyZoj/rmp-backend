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

object WriteEvents {
    const val TABLE_NAME = "default.user_stats"
    const val INSERT_SQL = """
        INSERT INTO $TABLE_NAME 
        (id, user_id, event_type, value, timestamp)
        VALUES (?, ?, ?, ?, ?)
    """
}

public class StatsWriter(
    private val connection: Connection,
    private val producer: KafkaProducer<String, String>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val insertStmt: PreparedStatement by lazy {
        connection.prepareStatement(WriteEvents.INSERT_SQL)
    }

    val kafkaProducer = createKafkaProducer()
    val logger = LogSender(kafkaProducer)

    fun log(level: Level, message: String, context: String) {
        logger.log("stats-interface", level, message, context)
    }

    fun logRequest(context: String) =
        log(Level.INFO, "Received request", context)

    fun logKafkaSend(context: String, payload: DataPayload, topic: String) =
        log(Level.INFO, "Sending request to $topic: $payload", context)

    companion object {
        const val CALORIES_PER_MINUTE_EASY = 5.0
        const val CALORIES_PER_MINUTE_MEDIUM = 7.0
        const val CALORIES_PER_MINUTE_HARD = 10.0
        const val XP_PER_CHALLENGES = 100.0
        const val CALORIES_PER_STEP = 0.04
    }

    fun processWriteEvent(key: String, value: String) {
        try {

            logRequest("Stats write $key")

            val payload = json.decodeFromString<DataPayload>(value)

            if (payload.params.size != 3) {
                sendError(key, "Invalid params count")
                return
            }

            val (userId, type, valueStr) = payload.params
            
            val events = parseEvents(userId, payload.message, type, valueStr, key) 
                ?: return

            insertStmt.apply {
                events.forEach { event ->
                    clearParameters()
                    setString(1, UUID.randomUUID().toString())
                    setString(2, event.userId)
                    setString(3, event.type)
                    setDouble(4, event.value)
                    setTimestamp(5, Timestamp.from(Instant.now()))
                    addBatch()
                }
                executeBatch()
            }

            sendSuccess(key)
        } catch (e: SerializationException) {
            sendError(key, "Invalid JSON format: ${e.message}")
        } catch (e: Exception) {
            sendError(key, "Database error: ${e.message}")
        }
    }

    internal fun parseEvents(
        userId: String,
        messageType: String,
        type: String,
        valueStr: String,
        key: String
    ): List<Event>? {
        return when (messageType) {
            "add_workout" -> {
                val duration = valueStr.toDoubleOrNull() ?: run {
                    sendError(key, "Invalid duration format")
                    return null
                }
                
                val caloriesPerMinute = when (type) {
                    "easy" -> CALORIES_PER_MINUTE_EASY
                    "medium" -> CALORIES_PER_MINUTE_MEDIUM
                    "hard" -> CALORIES_PER_MINUTE_HARD
                    else -> {
                        sendError(key, "Unknown workout type: $type")
                        return null
                    }
                }

                listOf(
                    Event(userId, "workout", 1.0),
                    Event(userId, "calorie", duration * caloriesPerMinute)
                )
            }
            else -> {
                val value = valueStr.toDoubleOrNull() ?: run {
                    sendError(key, "Invalid value format")
                    return null
                }
                
                if (type=="steps"){

                    val count_steps = valueStr.toDoubleOrNull() ?: run {
                        sendError(key, "Invalid duration format")
                        return null
                    }

                    listOf(Event(userId, type, value), Event(userId, "calorie", count_steps*CALORIES_PER_STEP))
                }
                else{
                    if (type=="challenge"){
                        listOf(Event(userId, type, 1.0), Event(userId, "xp", XP_PER_CHALLENGES))
                    }
                    else{
                        listOf(Event(userId, type, value))
                    }
                }
            }
        }
    }

    private fun insertEvent(event: Event) {
        insertStmt.apply {
            clearParameters()
            setString(1, UUID.randomUUID().toString())
            setString(2, event.userId)
            setString(3, event.type)
            setDouble(4, event.value)
            setTimestamp(5, Timestamp.from(Instant.now()))
            addBatch()
        }
    }

    internal data class Event(
        val userId: String,
        val type: String,
        val value: Double
    )

    private fun sendSuccess(key: String) {
        producer.send(ProducerRecord(
            "stats-resp-write",
            key,
            json.encodeToString(DataPayload("success", emptyList()))
        ))
    }

    internal fun sendError(key: String, error: String) {
        producer.send(ProducerRecord(
            "stats-resp-write",
            key,
            json.encodeToString(DataPayload("error", listOf(error))))
        )
    }
}