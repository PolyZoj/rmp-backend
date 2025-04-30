package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.DataPayload
import ru.polyZoj.db.*
import ru.polyZoj.exceptions.DuplicateFieldException
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.polyZoj.models.UserRegistration
import ru.polyZoj.repositories.UserRepository
import java.time.LocalDate

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.module() {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    val log = logger<Application>()

    val ds = DataSourceConfig()
    DatabaseFactory.init(ds)

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val kafkaConsumer = createKafkaConsumer("club-interface-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf("club-requests"))

    val userRepository = UserRepository()

    consumerService.startConsuming { conversationId, message ->
        log.info("Received message: $message")
        val data = Json.decodeFromString<DataPayload>(message)
        val command = data.message
        val args = data.params
        when (command) {
            "create" -> {
                //TODO
            }

            "listclubs" -> {
                //TODO
            }

            "addmember" -> {
                //TODO
            }

            "removemember" -> {
                //TODO
            }

            "getinfo" -> {
                //TODO
            }

            else -> {
                val err = DataPayload("error", listOf("Unknown command"))
                log.error("Unknown command: $command")
                producerService.send("club-responses", conversationId, Json.encodeToString(err))
            }
        }
    }

}
