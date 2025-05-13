package ru.polyZoj.utils

import io.mockk.*
import org.apache.kafka.clients.producer.KafkaProducer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.polyZoj.models.DataPayload
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class StatsReaderTest {
    private lateinit var connection: Connection
    private lateinit var producer: KafkaProducer<String, String>
    private lateinit var statsReader: StatsReader
    private lateinit var preparedStatement: PreparedStatement
    private lateinit var resultSet: ResultSet

    @BeforeEach
    fun setup() {
        connection = mockk()
        producer = mockk(relaxed = true)
        preparedStatement = mockk()
        resultSet = mockk()
        
        every { connection.prepareStatement(any()) } returns preparedStatement
        every { preparedStatement.setString(any(), any()) } just Runs
        every { preparedStatement.executeQuery() } returns resultSet
        
        statsReader = StatsReader(connection, producer)
    }

    @Test
    fun `processReadEvent with invalid params count should send error`() {
        val payload = DataPayload("get_stats", emptyList())
        statsReader.processReadEvent("error-key", Json.encodeToString(payload))

        verify {
            producer.send(match { record ->
                record.value().contains("Invalid params count") &&
                record.topic() == "stats-resp-read"
            })
        }
    }

    @Test
    fun `processReadEvent with database error should send error`() {
        every { preparedStatement.executeQuery() } throws Exception("DB connection failed")
        
        val payload = DataPayload("get_stats", listOf("user123"))
        statsReader.processReadEvent("error-key", Json.encodeToString(payload))

        verify {
            producer.send(match { record ->
                record.value().contains("Read error: DB connection failed") &&
                record.topic() == "stats-resp-read"
            })
        }
    }


}