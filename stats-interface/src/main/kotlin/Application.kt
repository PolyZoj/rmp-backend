package ru.polyZoj

import io.ktor.server.application.*
import common.DataPayload
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import java.util.*
import java.time.Duration
import java.sql.DriverManager
import ru.polyZoj.utils.StatsWriter
import ru.polyZoj.utils.WriteEvents
import ru.polyZoj.utils.StatsReader
import io.ktor.server.application.log
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger("StatsService")

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

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

fun Application.module() {
    val connection = DriverManager.getConnection(
        "jdbc:clickhouse://clickhouse:8123/default",
        "default",
        ""
    )

    val writeProducer = KafkaProducer<String, String>(producerConfig())
    val readProducer = KafkaProducer<String, String>(producerConfig())
    
    val writeConsumer = KafkaConsumer<String, String>(consumerConfig("stats-write-group"))
    val readConsumer = KafkaConsumer<String, String>(consumerConfig("stats-read-group"))
    val writer = StatsWriter(connection, writeProducer)
    val reader = StatsReader(connection, readProducer)

    writeConsumer.subscribe(listOf("stats-req-write"))
    readConsumer.subscribe(listOf("stats-req-read"))

    Runtime.getRuntime().addShutdownHook(Thread {
        writeConsumer.close()
        readConsumer.close()
        writeProducer.close()
        readProducer.close()
        connection.close()
    })

    launchConsumerLoop(writeConsumer, writer::processWriteEvent)
    launchConsumerLoop(readConsumer, reader::processReadEvent)
}

private fun Application.launchConsumerLoop(
    consumer: KafkaConsumer<String, String>,
    handler: (String, String) -> Unit
) {
    val logger = LoggerFactory.getLogger("ConsumerLoop")
    
    Thread {
        while (true) {
            val records = consumer.poll(Duration.ofMillis(100))
            records.forEach { record ->
                try {
                    handler(record.key(), record.value())
                } catch (e: Exception) {
                    logger.error("Error processing record", e)
                }
            }
            consumer.commitSync()
        }
    }.start()
}