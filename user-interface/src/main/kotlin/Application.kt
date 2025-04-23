package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import ru.polyZoj.kafka.KafkaConsumerService
import ru.polyZoj.kafka.KafkaProducerService
import ru.polyZoj.kafka.createKafkaConsumer
import ru.polyZoj.kafka.createKafkaProducer
import ru.polyZoj.models.DataPayload
import ru.polyZoj.models.UserRegistration
import ru.polyZoj.repositories.UserRepository
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap


val pendingResponses = ConcurrentHashMap<String, CompletableFuture<DataPayload>>()

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val kafkaConsumer = createKafkaConsumer()
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf("user-requests"))

    val userRepository = UserRepository()

    consumerService.startConsuming { conversationId, message ->
        val data = Json.decodeFromString<DataPayload>(message)

        when (data.params.firstOrNull()) {
            "findByUsername" -> {
                val username = data.params.getOrNull(1) ?: return@startConsuming
                val user = userRepository.findByUsername(username)

                // TODO what data to send back?
                val responsePayload = DataPayload(user?.userId.toString(), listOf(user?.userId.toString()))

                producerService.send("user-responses", conversationId, Json.encodeToString(responsePayload))
            }


            "createUser" -> {
                val newUserId = userRepository.registerUser(
                    TODO()
                )

                val responsePayload = DataPayload(newUserId.toString(), listOf(newUserId.toString()))
                producerService.send("user-responses", conversationId, Json.encodeToString(responsePayload))
            }

            else -> {
                val responsePayload = DataPayload("error", listOf("Unknown command"))
                producerService.send("user-responses", conversationId, Json.encodeToString(responsePayload))
            }
        }
    }

}
