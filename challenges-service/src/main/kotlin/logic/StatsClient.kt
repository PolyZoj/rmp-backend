package ru.polyZoj.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class DataPayloadForRetards(
    val message: String,
    val params: List<String>
)

fun KafkaConsumer<String, String>.startConsuming(handler: suspend (conversationId: String, message: String) -> Unit) {
    this.subscribe(listOf("stats-resp-read-daily","stats-resp-write", "stats-resp-read"))
    CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            val records = this@startConsuming.poll(Duration.ofMillis(100))
            records.forEach { record ->
                launch {
                    handler(record.key(), record.value())
                }
            }
            this@startConsuming.commitSync()
        }
    }
}

fun KafkaProducer<String, String>.send(topic: String, conversationId: String, message: String) {
    val record = ProducerRecord(topic, conversationId, message)
    this.send(record)
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

// matches param names in stats-interface's `StatsReader`
object EventTypes {
    const val LEVEL                 = "level"
    const val XP                    = "xp"
    const val CALORIE_COUNT         = "calorie"
    const val WATER_COUNT           = "water"
    const val WORKOUTS_COUNT        = "workouts"
    const val COMPLETED_CHALLENGES  = "challenge"
}

class StatsClient(
    private val producer: KafkaProducer<String, String>,
    private val consumer: KafkaConsumer<String, String>
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()

    init {
        consumer.startConsuming { key, payload ->
            pending.remove(key)?.complete(payload)
        }
    }

    fun readDaily(userId: String, date: String): DataPayloadForRetards {
        val key = UUID.randomUUID().toString()
        val future = CompletableFuture<String>()
        pending[key] = future

        producer.send(
            "stats-req-read-daily",
            key,
            json.encodeToString(DataPayloadForRetards("read_daily", listOf(userId, date)))
        )

        return json.decodeFromString<DataPayloadForRetards>(future.get(5, TimeUnit.SECONDS))
    }

    fun incrementCompletedChallenges(userId: String) {
        producer.send(
            "stats-req-write",
            UUID.randomUUID().toString(),
            json.encodeToString(DataPayloadForRetards("write", listOf(userId, EventTypes.COMPLETED_CHALLENGES, "1")))
        )
    }
}