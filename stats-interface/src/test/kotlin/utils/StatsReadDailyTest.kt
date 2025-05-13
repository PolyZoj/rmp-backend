package ru.polyZoj.utils

import io.mockk.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.polyZoj.models.DataPayload
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import ru.polyZoj.utils.StatsReaderDaily
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StatsReaderDailyTest {
    private lateinit var connection: Connection
    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var statsReader: StatsReaderDaily
    private lateinit var preparedStatement: PreparedStatement
    private lateinit var resultSet: ResultSet
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        connection = mockk()
        producer = mockk(relaxed = true)
        preparedStatement = mockk()
        resultSet = mockk()

        every { connection.prepareStatement(any()) } returns preparedStatement
        every { preparedStatement.setString(any(), any()) } just Runs
        every { preparedStatement.executeQuery() } returns resultSet
        every { resultSet.next() } returnsMany listOf(true, true, false)
        every { resultSet.getString("event_type") } returnsMany listOf("level", "steps", "calorie")
        every { resultSet.getFloat("total") } returnsMany listOf(5.0f, 10000.0f, 2500.0f)

        statsReader = StatsReaderDaily(connection, producer)
    }

    @Test
    fun `processReadEventDaily with invalid date format should send error`() {
        val payload = DataPayload(
            "get_daily_stats",
            listOf("user123", "2023/10/01")
        )

        statsReader.processReadEventDaily("error-key", json.encodeToString(payload))

        verify {
            producer.send(match { record ->
                record.value().contains("Неверный формат даты") &&
                record.value().contains("error")
            })
        }
    }

    @Test
    fun `processReadEventDaily with missing parameters should send error`() {
        val payload = DataPayload(
            "get_daily_stats",
            listOf("user123")
        )

        statsReader.processReadEventDaily("error-key", json.encodeToString(payload))

        verify {
            producer.send(match { record ->
                record.value().contains("Требуется user_id и дата") &&
                record.value().contains("error")
            })
        }
    }

    @Test
    fun `processReadEventDaily with database error should send error`() {
        val payload = DataPayload(
            "get_daily_stats",
            listOf("user123", "2023-10-01")
        )

        every { preparedStatement.executeQuery() } throws Exception("Database connection failed")

        statsReader.processReadEventDaily("error-key", json.encodeToString(payload))

        verify {
            producer.send(match { record ->
                record.value().contains("Внутренняя ошибка") &&
                record.value().contains("error")
            })
        }
    }

    @Test
    fun `buildResponse should map all fields correctly`() {
        val statsMap = mapOf(
            "level" to 5.0f,
            "xp" to 1000.0f,
            "steps" to 15000.0f,
            "calorie" to 2500.0f,
            "water" to 8.0f,
            "workout" to 3.0f,
            "challenge" to 2.0f
        )

        val response = statsReader.buildResponse("user123", "2023-10-01", statsMap)

        assertEquals(
            listOf(
                "user123",
                "2023-10-01",
                "5",   // level
                "1000",// xp
                "15000",// steps
                "2500",// calorie
                "8",   // water
                "3",   // workout
                "2"    // challenge
            ),
            response.params
        )
    }

    @Test
    fun `buildResponse should handle missing fields`() {
        val statsMap = mapOf(
            "steps" to 5000.0f,
            "calorie" to 1200.0f
        )

        val response = statsReader.buildResponse("user123", "2023-10-01", statsMap)

        assertEquals(
            listOf(
                "user123",
                "2023-10-01",
                "0",   // level
                "0",   // xp
                "5000",// steps
                "1200",// calorie
                "0",   // water
                "0",   // workout
                "0"    // challenge
            ),
            response.params
        )
    }

    @Test
    fun `getFormattedTimestamps should return correct time range`() {
        val date = LocalDate.of(2023, 10, 1)
        val (start, end) = statsReader.getFormattedTimestamps(date)

        assertEquals("2023-10-01 00:00:00", start)
        assertEquals("2023-10-02 00:00:00", end)
    }
}