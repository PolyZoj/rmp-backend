package ru.polyZoj.configs

import java.util.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.concurrent.ExecutionException
import io.ktor.server.application.*

fun producerConfig(): Properties {
    return Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
        put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

        put("acks", "all")
        put("enable.idempotence", "true")
        put("max.in.flight.requests.per.connection", "1")

        put("retries", "5")
        put("linger.ms", "1")
        put("delivery.timeout.ms", "120000")
    }
}

fun consumerConfig(groupId: String): Properties {
    return Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")

        put("group.id", groupId)
        put("auto.offset.reset", "earliest")
        put("enable.auto.commit", "false")

        put("isolation.level", "read_committed")
        put("max.poll.records", "50")

        put("session.timeout.ms", "15000")
        put("heartbeat.interval.ms", "5000")
        put("max.poll.interval.ms", "300000")

    }
}

fun Application.createKafkaTopics() {
    val adminProps = Properties().apply {
        put("bootstrap.servers", "kafka:9092")
        put("client.id", "stats-service-admin")
    }

    
    val i :Int = 1




    val admin = AdminClient.create(adminProps)
    
//    val topics = listOf(
//        NewTopic("auth-requests", 1, 3.toShort())
//            .configs(mapOf("min.insync.replicas" to "2")),
//        NewTopic("auth-responses", 1, 3.toShort())
//            .configs(mapOf("min.insync.replicas" to "2"))
//    )
    // TODO: set above for production
    val topics = listOf(
        NewTopic("stats-req-write", 1, 1.toShort())
            .configs(mapOf("min.insync.replicas" to "1")),
        NewTopic("stats-resp-write", 1, 1.toShort())
            .configs(mapOf("min.insync.replicas" to "1")),
        NewTopic("stats-req-read", 1, 1.toShort())
            .configs(mapOf("min.insync.replicas" to "1")),
        NewTopic("stats-resp-read", 1, 1.toShort())
            .configs(mapOf("min.insync.replicas" to "1"))
    )


    try {
        admin.createTopics(topics).all().get()
        log.info("Successfully created Kafka topics")
    } catch (e: ExecutionException) {
        if (e.cause is TopicExistsException) {
            log.info("Kafka topics already exist")
        } else {
            log.error("Failed to create Kafka topics: ${e.message}")
        }
    } finally {
        admin.close()
    }
}
