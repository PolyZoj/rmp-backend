package ru.polyZoj.routing

import common.DataPayload
import common.kafka.KafkaConfig
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.models.UserUpdatable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.configureRouting() {
    val log = logger<Application>()

    val kafkaConfig = KafkaConfig()
    kafkaConfig.createTopicIfNotExists("user-gateway-requests", 1, 3.toShort())
    kafkaConfig.createTopicIfNotExists("user-gateway-responses", 1, 3.toShort())

    val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()
    val reqProcessor = RequestProcessor()

    val kafkaProducer = createKafkaProducer()

    val consumer = createKafkaConsumer("user-gateway-consumer")
    CoroutineScope(Dispatchers.IO).launch {
        consumer.subscribe(listOf("user-responses"))
        while (true) {
            val records = consumer.poll(java.time.Duration.ofMillis(100))
            records.forEach { record ->
                mutex.withLock {
                    try {
                        val responsePayload = record.value()
                        pendingResponses[record.key()]?.complete(responsePayload)
                    } catch (e: Exception) {
                        pendingResponses[record.key()]?.complete(
                            DataPayload.error(
                                status = HttpStatusCode.InternalServerError,
                                description = "Failed to process response: ${e.message}"
                            )
                        )
                    }
                }
            }
        }
    }


    routing {
        route("/api/v1/users") {

            // GET /users/{id} - получение информации о конкретном пользователе
            get("/{id}") {
                val id = call.parameters["id"]
                log.info("GET /api/v1/users/{}", id)
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                    return@get
                }

                val requestPayload = DataPayload.build("userInfo") {
                    param("user_id", id)
                }
                log.info("sending request to user-gateway-requests: {}", requestPayload)
                reqProcessor.processRequest(
                    requestPayload,
                    "user-gateway-requests",
                    call,
                    kafkaProducer,
                    pendingResponses,
                    mutex,
                    ::handleSuccessfulResponse
                )
            }

            // GET /users/username/{username} - получение id пользователя по username
            get("/username/{username}") {
                val username = call.parameters["username"]
                log.info("GET /api/v1/users/username/{}", username)
                if (username == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный username пользователя")
                    return@get
                }

                val requestPayload = DataPayload.build("findByUsername") {
                    param("username", username)
                }
                log.info("sending request to user-gateway-requests: {}", requestPayload)
                reqProcessor.processRequest(
                    requestPayload,
                    "user-gateway-requests",
                    call,
                    kafkaProducer,
                    pendingResponses,
                    mutex,
                    ::handleSuccessfulResponse
                )
            }

            // PUT /users/{id} - обновление информации о пользователе
            put("/{id}") {
                log.info("PUT /api/v1/users/{id}")
                val id = call.parameters["id"]?.toLongOrNull()
                val request = call.receive<UserUpdatable>()
                if (id == null) {
                    log.debug("PUT /api/v1/users/{id}, id is null")
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                    return@put
                }

                // TODO: Получить данные из тела запроса, а не просто id
                val requestPayload = DataPayload.build("updateUserInfo") {
                    param("id", id)
                }
                reqProcessor.processRequest(
                    requestPayload,
                    "user-gateway-requests",
                    call,
                    kafkaProducer,
                    pendingResponses,
                    mutex,
                    ::handleSuccessfulResponse
                )
            }

            // DELETE /users/{id} - удаление пользователя
            delete("/{id}") {
                log.info("DELETE /api/v1/users/{id}")
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    log.debug("DELETE /api/v1/users/{id}")
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                    return@delete
                }

                val requestPayload = DataPayload.build("updateUserInfo") {
                    param("id", id)
                }
                reqProcessor.processRequest(
                    requestPayload,
                    "user-gateway-requests",
                    call,
                    kafkaProducer,
                    pendingResponses,
                    mutex,
                    ::handleSuccessfulResponse
                )

            }
//
//            // Друзья пользователя
//            route("/{userId}/friends") {
//                // GET /users/{userId}/friends - получение списка друзей
//                get {
//                    val userId = call.parameters["userId"]?.toLongOrNull()
//                    if (userId == null) {
//                        call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
//                        return@get
//                    }
//
//                    val friends =
//                            listOf(
//                                    Friends(id_1 = userId, id_2 = userId + 1),
//                                    Friends(id_1 = userId, id_2 = userId + 2)
//                            )
//                    call.respond(friends)
//                }
//
//                // POST /users/{userId}/friends - добавление друга
//                post {
//                    val userId = call.parameters["userId"]?.toLongOrNull()
//                    if (userId == null) {
//                        call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
//                        return@post
//                    }
//
//                    val friend = call.receive<Friends>()
//                    call.respond(HttpStatusCode.Created, friend)
//                }
//
//                // DELETE /users/{userId}/friends/{friendId} - удаление друга
//                delete("/{friendId}") {
//                    val userId = call.parameters["userId"]?.toLongOrNull()
//                    val friendId = call.parameters["friendId"]?.toLongOrNull()
//
//                    if (userId == null || friendId == null) {
//                        call.respond(HttpStatusCode.BadRequest, "Некорректные id пользователей")
//                        return@delete
//                    }
//
//                    call.respond(HttpStatusCode.OK, "Пользователь $friendId удален из друзей")
//                }
        }
    }
}

private suspend fun handleSuccessfulResponse(
    operation: String,
    result: DataPayload,
    call: ApplicationCall
) {
    when (operation) {
        "userInfo" -> call.respond(
            HttpStatusCode.OK,
            Json.encodeToString(DataPayload.serializer(), result)
            )

        "findByUsername" -> call.respond(
            HttpStatusCode.OK,
            Json.encodeToString(DataPayload.serializer(), result)
            )

        "updateUserInfo" -> call.respond(
            HttpStatusCode.OK,
            )

        "deleteUser" -> call.respond(
            HttpStatusCode.OK,
            )

        "else" -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type")
        )
    }
}
