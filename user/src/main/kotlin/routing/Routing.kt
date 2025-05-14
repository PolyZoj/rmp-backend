package ru.polyZoj.routing

import common.DataPayload
import common.Level
import common.LogSender
import common.kafka.RequestProcessor
import common.kafka.topics.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.producer.KafkaProducer
import java.util.concurrent.ConcurrentHashMap


fun Application.configureRouting(
    reqProcessor: RequestProcessor,
    kafkaProducer: KafkaProducer<String, DataPayload>,
    kafkaConsumer: Consumer<String, DataPayload>
) {

    val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<DataPayload>>()
    val mutex = Mutex()

    val logger = LogSender(kafkaProducer)

    fun log(level: Level, message: String, context: String) {
        logger.log("user", level, message, context)
    }

    fun logRequest(context: String) =
        log(Level.INFO, "Received request", context)

    fun logKafkaSend(context: String, payload: DataPayload) =
        log(Level.INFO, "Sending request to $USER_GATEWAY_REQ: $payload", context)

    suspend fun respondError(
        call: ApplicationCall,
        status: HttpStatusCode,
        message: String,
        context: String
    ) {
        log(Level.ERROR, "Responding ${status.value}: $message", context)
        call.respond(status, message)
    }

    CoroutineScope(Dispatchers.IO).launch {
        kafkaConsumer.subscribe(listOf("user-responses"))
        while (true) {
            val records = kafkaConsumer.poll(java.time.Duration.ofMillis(100))
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

    fun verifyJWTandGetUserId(call: ApplicationCall): String? {
        return call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asString()
    }

    suspend fun handleFriendAction(
        call: ApplicationCall,
        command: String,
        path: String,
        bodyKey: String?,
    ) {
        val userId = verifyJWTandGetUserId(call)
            ?: return respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", path)
        logRequest(path)
        val params = mutableMapOf("user_id" to userId)
        if (bodyKey != null) {
            val text = call.receiveText().takeIf { it.isNotBlank() }
                ?: return call.respond(HttpStatusCode.BadRequest, "$bodyKey is required")

            val json = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (_: SerializationException) {
                return call.respond(HttpStatusCode.BadRequest, "Malformed JSON")
            }

            val value = json[bodyKey]?.jsonPrimitive?.content
                ?: return call.respond(HttpStatusCode.BadRequest, "$bodyKey is required")

            params[bodyKey] = value
        }

        val requestPayload = DataPayload.build(command) {
            params.forEach { (k, v) -> param(k, v) }
        }
        logKafkaSend(path, requestPayload)
        reqProcessor.processRequest(
            requestPayload,
            USER_GATEWAY_REQ,
            call,
            kafkaProducer,
            pendingResponses,
            mutex,
            ::handleSuccessfulResponse
        )
    }


    routing {
        authenticate("auth-jwt"){
            route("/api/v1/users") {

                // GET /users/{id} - получение информации о конкретном пользователе
                get("/{id}") {
                    val staticPath = "GET /api/v1/users/{id}"
                    val userId = verifyJWTandGetUserId(call)
                        ?: return@get respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)
                    logRequest(staticPath)
                    val id = call.parameters["id"]
                        ?: return@get respondError(call, HttpStatusCode.BadRequest, "Некорректный id пользователя", staticPath)

                    val requestPayload = DataPayload.build("userInfo") {
                        param("user_id", id)
                        param("self_id", userId)
                    }
                    logKafkaSend(staticPath, requestPayload)
                    reqProcessor.processRequest(
                        requestPayload,
                        USER_GATEWAY_REQ,
                        call,
                        kafkaProducer,
                        pendingResponses,
                        mutex,
                        ::handleSuccessfulResponse
                    )
                }

                // GET /users/username/{username} - получение id пользователя по username
                get("/username/{username}") {
                    val staticPath = "GET /api/v1/users/username/{username}"
                    logRequest(staticPath)

                    val username = call.parameters["username"]
                        ?: return@get respondError(call, HttpStatusCode.BadRequest, "Некорректный username пользователя", staticPath)

                    val requestPayload = DataPayload.build("findByUsername") {
                        param("username", username)
                    }
                    logKafkaSend(staticPath, requestPayload)
                    reqProcessor.processRequest(
                        requestPayload,
                        USER_GATEWAY_REQ,
                        call,
                        kafkaProducer,
                        pendingResponses,
                        mutex,
                        ::handleSuccessfulResponse
                    )
                }

                // PUT /users/goals/update - обновление информации о пользователе
                put("/goals/update") {
                    val staticPath = "PUT /api/v1/users/goals/update"
                    val userId = verifyJWTandGetUserId(call)
                        ?: return@put respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)

                    logRequest(staticPath)

                    val text = call.receiveText()
                    val json = try {
                        Json.parseToJsonElement(text).jsonObject
                    } catch (_: SerializationException) {
                        return@put respondError(call, HttpStatusCode.BadRequest, "Malformed JSON", staticPath)
                    }

                    val requestPayload = DataPayload.build("updateUserInfo") {
                        param("user_id", userId)
                        param("avatar_url", json["avatar_url"]?.jsonPrimitive?.contentOrNull)
                        param("weight", json["weight"]?.jsonPrimitive?.floatOrNull)
                        param("height", json["height"]?.jsonPrimitive?.intOrNull)
                        param("health_goal", json["health_goal"]?.jsonPrimitive?.contentOrNull)
                        param("daily_step_goal", json["daily_step_goal"]?.jsonPrimitive?.intOrNull)
                        param("water_intake_goal", json["water_intake_goal"]?.jsonPrimitive?.intOrNull)
                        param("calorie_goal", json["calorie_goal"]?.jsonPrimitive?.intOrNull)
                        param("sleep_goal", json["sleep_goal"]?.jsonPrimitive?.floatOrNull)
                        param("workouts_goal", json["workouts_goal"]?.jsonPrimitive?.intOrNull)
                    }
                    logKafkaSend(staticPath, requestPayload)
                    reqProcessor.processRequest(
                        requestPayload,
                        USER_GATEWAY_REQ,
                        call,
                        kafkaProducer,
                        pendingResponses,
                        mutex,
                        ::handleSuccessfulResponse
                    )
                }

                // DELETE /users/ - удаление пользователя
                delete {
                    val staticPath = "DELETE /api/v1/users"
                    val userId = verifyJWTandGetUserId(call)
                        ?: return@delete respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)

                    logRequest(staticPath)

                    val requestPayload = DataPayload.build("deleteUser") {
                        param("user_id", userId)
                    }

                    logKafkaSend(staticPath, requestPayload)
                    reqProcessor.processRequest(
                        requestPayload,
                        USER_GATEWAY_REQ,
                        call,
                        kafkaProducer,
                        pendingResponses,
                        mutex,
                        ::handleSuccessfulResponse
                    )

                }

                route("/friends") {

                    route("/notifications") {

                        get {
                            // "getFriendRequests
                            val staticPath = "GET /api/v1/users/friends/notifications"
                            val userId = verifyJWTandGetUserId(call)
                                ?: return@get respondError(call, HttpStatusCode.Unauthorized, "Not authenticated", staticPath)

                            logRequest(staticPath)

                            val requestPayload = DataPayload.build("getFriendRequests") {
                                param("user_id", userId)
                            }

                            logKafkaSend(staticPath, requestPayload)
                            reqProcessor.processRequest(
                                requestPayload,
                                USER_GATEWAY_REQ,
                                call,
                                kafkaProducer,
                                pendingResponses,
                                mutex,
                                ::handleSuccessfulResponse
                            )
                        }

                        post("/accept") {
                            // "acceptFriendRequest"
                            handleFriendAction(
                                call,
                                "acceptFriendRequest",
                                "POST /api/v1/users/friends/notifications/accept/",
                                "friend_id"
                            )
                        }

                        post("/deny") {
                            // "denyFriendRequest"
                            handleFriendAction(
                                call,
                                "denyFriendRequest",
                                "POST /api/v1/users/friends/notifications/deny/",
                                "friend_id"
                            )
                        }
                    }

                    post("/request") {
                        // "addFriendRequest"
                        handleFriendAction(
                            call,
                            "addFriendRequest",
                            "POST /api/v1/users/friends/request/",
                            "friend_id"
                        )
                    }

                    post("/remove") {
                        // "removeFriend"
                        handleFriendAction(
                            call,
                            "removeFriend",
                            "POST /api/v1/users/friends/remove/",
                            "friend_id"
                        )
                    }

                    get("/list") {
                        // "getFriendsList
                        handleFriendAction(
                            call,
                            "getFriendsList",
                            "GET /api/v1/users/friends/list/",
                            null
                        )
                    }

                    post("/find") {
                        // "findFriend"
                        handleFriendAction(
                            call,
                            "findFriend",
                            "POST /api/v1/users/friends/find/",
                            "find_username"
                        )
                    }

                }
            }
        }

    }
}

private suspend fun handleSuccessfulResponse(
    operation: String,
    result: DataPayload,
    call: ApplicationCall
) {
    when (operation) {
        "userInfo",
        "findByUsername",
        "updateUserInfo",
        "deleteUser",
        "getFriendRequests",
        "acceptFriendRequest",
        "denyFriendRequest",
        "addFriendRequest",
        "removeFriend",
        "getFriendsList",
        "findFriend" -> call.respond(HttpStatusCode.OK, result.params)

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type")
        )
    }
}
