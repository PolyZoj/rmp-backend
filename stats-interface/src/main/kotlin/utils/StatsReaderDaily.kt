package ru.polyZoj.utils

import ru.polyZoj.models.DataPayload
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.sql.Connection
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import common.Level
import common.LogSender
import common.kafka.createKafkaProducer

object ReadEventsDaily {
    const val SELECT_DAILY_SQL = """
        SELECT event_type, SUM(value) as total 
        FROM ${WriteEvents.TABLE_NAME} 
        WHERE user_id = ? 
        AND parseDateTimeBestEffort(timestamp) >= parseDateTimeBestEffort(?)
        AND parseDateTimeBestEffort(timestamp) < parseDateTimeBestEffort(?) 
        GROUP BY event_type
    """
}

public class StatsReaderDaily(
    private val connection: Connection,
    private val producer: KafkaProducer<String, String>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    val kafkaProducer = createKafkaProducer()
    val logger = LogSender(kafkaProducer)

    fun log(level: Level, message: String, context: String) {
        logger.log("stats-interface", level, message, context)
    }

    fun logRequest(context: String) =
        log(Level.INFO, "Received request", context)

    fun logKafkaSend(context: String, payload: DataPayload, topic: String) =
        log(Level.INFO, "Sending request to $topic: $payload", context)

    fun processReadEventDaily(key: String, value: String) {
        try {

            logRequest("Stats read daily $key")

            val payload = json.decodeFromString<DataPayload>(value)
            
            if (payload.params.size != 2) {
                sendError(key, "Требуется user_id и дата в формате YYYY-MM-DD")
                return
            }

            val (userId, dateString) = payload.params

            val date = try {
                LocalDate.parse(dateString, DateTimeFormatter.ISO_DATE)
            } catch (e: Exception) {
                sendError(key, "Неверный формат даты. Используйте YYYY-MM-DD")
                return
            }

            val (startOfDayStr, endOfDayStr) = getFormattedTimestamps(date)

            val statsMap = connection.prepareStatement(ReadEventsDaily.SELECT_DAILY_SQL).use { stmt ->
                stmt.setString(1, userId)
                stmt.setString(2, startOfDayStr)
                stmt.setString(3, endOfDayStr)
                
                val rs = stmt.executeQuery()
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("event_type"), rs.getFloat("total"))
                    }
                }
            }

            val response = buildResponse(userId, dateString, statsMap)

            logKafkaSend("Stats read daily $key", response, "stats-resp-read-daily")

            producer.send(ProducerRecord("stats-resp-read-daily", key, json.encodeToString(response)))
            
        } catch (e: Exception) {
            sendError(key, "Внутренняя ошибка: ${e.message?.take(50)}")
        }
    }

    internal fun getFormattedTimestamps(date: LocalDate): Pair<String, String> {
        val startOfDay = date.atStartOfDay().format(dateTimeFormatter)
        val endOfDay = date.plusDays(1).atStartOfDay().format(dateTimeFormatter)
        return startOfDay to endOfDay
    }

    internal fun buildResponse(
        userId: String,
        dateString: String,
        statsMap: Map<String, Float>
    ): DataPayload = DataPayload(
        message = "success",
        params = listOf(
            userId,
            dateString,
            statsMap.getInt("level"),
            statsMap.getInt("xp"),
            statsMap.getInt("steps"),
            statsMap.getInt("calorie"),
            statsMap.getInt("water"),
            statsMap.getInt("workout"),
            statsMap.getInt("challenge")
        )
    )

    private fun Map<String, Float>.getInt(key: String) = (this[key]?.toInt() ?: 0).toString()

    private fun sendError(key: String, error: String) {
        producer.send(ProducerRecord(
            "stats-resp-read-daily",
            key,
            json.encodeToString(DataPayload("error", listOf(error))))
        )
    }
}