package ru.polyZog

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import ru.polyZog.kafka.KafkaConsumerService
import ru.polyZog.kafka.KafkaProducerService
import ru.polyZog.kafka.createKafkaConsumer
import ru.polyZog.kafka.createKafkaProducer
import ru.polyZog.models.DataPayload
import ru.polyZog.models.User
import ru.polyZog.repositories.UserDataSource

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }
//    configureRouting()

//    val kafkaConfig = KafkaConfig()
//    kafkaConfig.createTopicIfNotExists(
//        topicName = "example-topic",
//        numPartitions = 3,
//        replicationFactor = 1
//    )

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val kafkaConsumer = createKafkaConsumer()
    // List topics you want to consume from. (The topic should have multiple partitions if needed.)
    val consumerTopics = listOf("auth-requests")
    val consumerService = KafkaConsumerService(kafkaConsumer, consumerTopics)

    // Start Kafka Consumer and define how each message should be processed.
    consumerService.startConsuming { conversationId, message ->
        println("Consumed message -> ConversationID: $conversationId, Message: $message")

        val data = Json.decodeFromString<DataPayload>(message)
        val user = UserDataSource.findUserByUsername(data.params.firstOrNull() ?: "")

        if (user != null) {
            val message = DataPayload(user.id, listOf(""))
            producerService.send("auth-responses", conversationId, Json.encodeToString(message))
        } else {
            val newUser = User(UserDataSource.generateUserId(), data.params.firstOrNull() ?: "", data.params.getOrNull(1)
                ?: "")
            UserDataSource.addUser(newUser)
            val message = DataPayload(newUser.id, listOf(""))
            producerService.send("auth-responses", conversationId, Json.encodeToString(message))
        }

    }

//    routing {
//        route("/api/v1/users") {
//            get("/") {
//                call.respondText("Hello World!")
//            }
//
//            post("/produce") {
//                val conversationId = call.request.queryParameters["conversationId"]
//                    ?: return@post call.respondText("Missing conversationId query parameter", status = HttpStatusCode.BadRequest)
//                val message = call.request.queryParameters["message"]
//                    ?: return@post call.respondText("Missing message query parameter", status = HttpStatusCode.BadRequest)
//                producerService.send("example-topic", conversationId, message)
//                call.respondText("Message sent for conversationId = $conversationId", status = HttpStatusCode.OK)
//            }
//        }
//    }
}
