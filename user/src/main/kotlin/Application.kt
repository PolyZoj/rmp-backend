package ru.polyZoj

import common.kafka.KafkaConfig
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.kafka.topics.USER_GATEWAY_REQ
import common.kafka.topics.USER_GATEWAY_RES
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import ru.polyZoj.configs.configureSecurity
import ru.polyZoj.routing.configureRouting


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

    configureSecurity()

    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists(USER_GATEWAY_REQ, 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists(USER_GATEWAY_RES, 1, 3.toShort())
    val reqProcessor = RequestProcessor()
    val kafkaProducer = createKafkaProducer()
    val consumer = createKafkaConsumer("user-gateway-consumer")


    configureRouting(reqProcessor, kafkaProducer, consumer)
}