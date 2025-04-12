package ru.polyZoj

import ru.polyZoj.models.StatsRequest
import ru.polyZoj.models.StatsResponse
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.time.Duration
import java.util.Properties
import java.util.UUID

object KafkaRequestResponseClient {

    /**
     * Sends a stats request and waits for a reply.
     *
     * @param request The stats request (userId, metricType).
     * @param timeout How long to wait for a reply.
     * @return The matching StatsResponse if received, otherwise null.
     */
    fun requestStats(request: StatsRequest, timeout: Duration = Duration.ofSeconds(5)): StatsResponse? {
        // Generate a unique correlation id.
        val correlationId = UUID.randomUUID().toString()
        // Create a new request with the correlation id included.
        val requestWithMeta = request.copy(correlationId = correlationId)
        val jsonRequest = Json.encodeToString(requestWithMeta)

        // Produce the request message to Kafka.
        KafkaProducerWrapper.send(
            topic = "stats.request",
            key = correlationId,
            message = jsonRequest
        )

        // Create a temporary Kafka consumer to wait for the reply.
        val consumer = createReplyConsumer()
        try {
            // Subscribe to the reply topic.
            consumer.subscribe(listOf("stats.response"))
            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline) {
                val records = consumer.poll(Duration.ofMillis(100))
                for (record in records) {
                    // Parse the record's value as a StatsResponse.
                    val response = Json.decodeFromString<StatsResponse>(record.value())
                    if (response.correlationId == correlationId) {
                        // Found our matching reply.
                        return response
                    }
                }
            }
        } finally {
            consumer.close()
        }
        // If no reply was received in time, return null.
        return null
    }

    /**
     * Creates a Kafka consumer configured for consuming reply messages.
     */
    private fun createReplyConsumer(): KafkaConsumer<String, String> {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
            // Create a unique group id to avoid interference between replicas.
            put(ConsumerConfig.GROUP_ID_CONFIG, "challenge-service-reply-${UUID.randomUUID()}")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        }
        return KafkaConsumer<String, String>(props)
    }
}
