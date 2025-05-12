package ru.polyZoj

import common.kafka.KafkaConfig
import common.kafka.KafkaConsumerService
import common.kafka.createKafkaConsumer
import common.kafka.topics.LOG_REQ
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.polyZoj.db.DBFactory
import ru.polyZoj.db.DataSourceConfig


inline fun <reified T> getLogger(): Logger = LoggerFactory.getLogger(T::class.java)

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    val logger = getLogger<Application>()

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    val config = DataSourceConfig()
    DBFactory.init(config)

    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists(LOG_REQ, 1, 3.toShort())

    val logConsumer = KafkaConsumerService(createKafkaConsumer("log-service-consumer"), listOf(LOG_REQ))
    logger.info("Starting")

    logConsumer.startConsuming { conversationId, message ->
        logger.info("Consuming conversationId: $conversationId")
        logger.info("Received log request: $message")

        try {
            DBFactory.insertLog(
                serviceName = message.getParam<String>("serviceName")!!,
                level = message.getParam<String>("level")!!,
                message = message.getParam<String>("logMessage")!!,
                context = message.getParam<String>("context")!!
            )
            logger.info("Log successfully inserted into the database")
        } catch (e: Exception) {
            logger.error("Failed to process log message", e)
        }
    }
}
