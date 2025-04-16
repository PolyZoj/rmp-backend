package ru.polyZog.kafka

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.time.Duration
import java.util.Properties


class KafkaConsumerService(
    private val consumer: KafkaConsumer<String, String>,
    private val topics: List<String>,
    private val pollDuration: Duration = Duration.ofMillis(1000)
) {
    private var running = true

    /**
     * Starts the consumer loop in a coroutine, processing messages using the provided handler.
     */
    fun startConsuming(handler: suspend (conversationId: String, message: String) -> Unit) {
        consumer.subscribe(topics)
        CoroutineScope(Dispatchers.IO).launch {
            while (running) {
                val records = consumer.poll(pollDuration)
                records.forEach { record ->
                    // Launch a coroutine for each record to allow parallel processing
                    launch {
                        handler(record.key(), record.value())
                    }
                }
                consumer.commitSync()
            }
        }
    }

    /**
     * Stops the consumer and closes the underlying consumer connection.
     */
    fun stop() {
        running = false
        consumer.close()
    }
}

fun createKafkaConsumer(): KafkaConsumer<String, String> {
    val props = Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("group.id", "ktor-microservice-consumer-group")
        put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("auto.offset.reset", "earliest")
        put("enable.auto.commit", "false")
        // Additional tuning parameters can be added here.
    }
    return KafkaConsumer(props)
}