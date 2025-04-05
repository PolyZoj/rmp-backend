package ru.polyZoj

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json


fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    // 1) Initialize DB
    DatabaseFactory.init()

    // 2) Install ContentNegotiation for JSON
    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }

    // 3) Initialize Kafka Producer
    KafkaProducerWrapper.initialize()

    // 4) Start Kafka Consumer as a background worker (or as a separate service)
    install(KafkaConsumerWorker) {
        bootstrapServers = "localhost:9092"
        groupId = "challenge-service-group"
        topic = "stats.user-activity"  // The stats service publishes user activity here
    }

    // 5) Configure HTTP Routing
    configureRouting()
}
