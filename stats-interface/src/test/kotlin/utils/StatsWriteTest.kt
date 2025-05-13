package ru.polyZoj.utils

import io.mockk.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.polyZoj.models.DataPayload
import ru.polyZoj.utils.StatsWriter
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsWriteTest {
    private lateinit var connection: Connection
    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var statsWriter: StatsWriter
    private lateinit var preparedStatement: PreparedStatement
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        connection = mockk()
        producer = mockk(relaxed = true)
        preparedStatement = mockk(relaxed = true)
        
        every { connection.prepareStatement(any()) } returns preparedStatement
        every { preparedStatement.clearParameters() } just Runs
        every { preparedStatement.addBatch() } just Runs
        every { preparedStatement.executeBatch() } returns intArrayOf(1, 1)

        statsWriter = StatsWriter(connection, producer)
    }

    @Test
    fun `processWriteEvent with valid workout event should insert records`() {
        val payload = DataPayload(
            "add_workout",
            listOf("user123", "medium", "30.5")
        )

        statsWriter.processWriteEvent("key1", json.encodeToString(payload))

        verify(exactly = 1) {
            producer.send(match { record ->
                record.value().contains("success") &&
                record.topic() == "stats-resp-write"
            })
        }
    }

    @Test
    fun `processWriteEvent with steps should add calorie event`() {
        val payload = DataPayload(
            "add_stats",
            listOf("user456", "steps", "1000")
        )

        statsWriter.processWriteEvent("key2", json.encodeToString(payload))

        verify(exactly = 1) {
            preparedStatement.apply {
                setString(3, "steps")
                setDouble(4, 1000.0)
                setString(3, "calorie")
                setDouble(4, 1000.0 * StatsWriter.CALORIES_PER_STEP)
            }
            preparedStatement.executeBatch()
        }
    }

    @Test
    fun `processWriteEvent with invalid params count should send error`() {
        val payload = DataPayload(
            "add_stats",
            listOf("user123", "type-only")
        )

        statsWriter.processWriteEvent("key3", json.encodeToString(payload))

        verify(exactly = 1) {
            producer.send(match { record ->
                record.value().contains("Invalid params count") &&
                record.topic() == "stats-resp-write"
            })
        }
    }

    @Test
    fun `processWriteEvent with invalid workout type should send error`() {
        val payload = DataPayload(
            "add_workout",
            listOf("user789", "invalid-type", "30")
        )

        statsWriter.processWriteEvent("key4", json.encodeToString(payload))

        verify(exactly = 1) {
            producer.send(match { record ->
                record.value().contains("Unknown workout type") &&
                record.topic() == "stats-resp-write"
            })
        }
    }

    @Test
    fun `processWriteEvent with database error should send error`() {
        val payload = DataPayload(
            "add_workout",
            listOf("user999", "hard", "45")
        )
        
        every { preparedStatement.executeBatch() } throws Exception("DB error")

        statsWriter.processWriteEvent("key5", json.encodeToString(payload))

        verify(exactly = 1) {
            producer.send(match { record ->
                record.value().contains("Database error") &&
                record.topic() == "stats-resp-write"
            })
        }
    }

    @Test
    fun `parseEvents for easy workout should calculate correct calories`() {
        val result = statsWriter.parseEvents(
            "user1",
            "add_workout",
            "easy",
            "60",
            "key6"
        )

        assertEquals(2, result?.size)
        assertEquals(60.0 * StatsWriter.CALORIES_PER_MINUTE_EASY, result?.get(1)?.value)
    }

    @Test
    fun `parseEvents for medium workout should calculate correct calories`() {
        val result = statsWriter.parseEvents(
            "user2",
            "add_workout",
            "medium",
            "45",
            "key7"
        )

        assertEquals(2, result?.size)
        assertEquals(45.0 * StatsWriter.CALORIES_PER_MINUTE_MEDIUM, result?.get(1)?.value)
    }

    @Test
    fun `parseEvents for water should create single event`() {
        val result = statsWriter.parseEvents(
            "user3",
            "add_stats",
            "water",
            "2.5",
            "key8"
        )

        assertEquals(1, result?.size)
        assertEquals("water", result?.get(0)?.type)
        assertEquals(2.5, result?.get(0)?.value)
    }

    @Test
    fun `parseEvents with invalid value should return null`() {
        val result = statsWriter.parseEvents(
            "user4",
            "add_stats",
            "steps",
            "invalid",
            "key9"
        )

        assertTrue(result == null)
    }
}