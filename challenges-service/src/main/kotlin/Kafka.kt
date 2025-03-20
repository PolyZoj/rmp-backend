package ru.polyZoj

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.Properties

/**
 * Обёртка для работы с Kafka Producer.
 * Здесь реализована базовая инициализация и метод для отправки сообщений.
 */
object KafkaProducerWrapper {
    private lateinit var producer: KafkaProducer<String, String>

    fun initialize() {
        val props = Properties().apply {
            put("bootstrap.servers", "localhost:9092")
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        }
        producer = KafkaProducer(props)
    }

    fun send(topic: String, key: String, message: String) {
        val record = ProducerRecord(topic, key, message)
        producer.send(record)
    }
}
