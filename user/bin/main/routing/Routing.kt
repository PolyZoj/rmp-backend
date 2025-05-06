package ru.polyZoj.routing

import com.auth0.jwt.interfaces.Claim
import common.DataPayload
import common.kafka.KafkaConfig
import common.kafka.RequestProcessor
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
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

    fun verifyJWTandGetUserId(call: ApplicationCall): String? {
        val principal = call.principal<JWTPrincipal>()
            ?: return null

        val userIdClaim: Claim = principal.payload.getClaim("userId")
        return userIdClaim.asString()
    }

    suspend fun handleFriendAction(
        call: ApplicationCall,
        command: String,
        bodyKey: String?,
    ) {
        // 1) Auth
        val userId = verifyJWTandGetUserId(call)
            ?: return call.respond(HttpStatusCode.Unauthorized, "Not authenticated")

        // 2) Parse optional body param
        val params = mutableMapOf("user_id" to userId)
        if (bodyKey != null) {
            val text = call.receiveText().takeIf { it.isNotBlank() }
                ?: return call.respond(HttpStatusCode.BadRequest, "$bodyKey is required")

            val json = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: SerializationException) {
                return call.respond(HttpStatusCode.BadRequest, "Malformed JSON")
            }

            val value = json[bodyKey]?.jsonPrimitive?.content
                ?: return call.respond(HttpStatusCode.BadRequest, "$bodyKey is required")

            params[bodyKey] = value
        }

        // 3) Build payload and dispatch
        val requestPayload = DataPayload.build(command) {
            params.forEach { (k, v) -> param(k, v) }
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


    routing {
        authenticate("auth-jwt"){
            route("/api/v1/users") {

                // GET /users/{id} - получение информации о конкретном пользователе
                get("/{id}") {
                    val userId = verifyJWTandGetUserId(call)
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                    val id = call.parameters["id"]
                    log.info("GET /api/v1/users/{}", id)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                        return@get
                    }

                    val requestPayload = DataPayload.build("userInfo") {
                        param("user_id", id)
                        param("self_id", userId)
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

                // PUT /users/goals/update - обновление информации о пользователе
                put("/goals/update") {
                    log.info("PUT /api/v1/users/goals/update")
                    val userId = verifyJWTandGetUserId(call)
                        ?: return@put call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                    val text = call.receiveText()
                    val json = Json
                        .parseToJsonElement(text)
                        .jsonObject

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

                // DELETE /users/ - удаление пользователя
                delete {
                    log.info("DELETE /api/v1/users/")
                    val userId = verifyJWTandGetUserId(call)
                        ?: return@delete call.respond(HttpStatusCode.Unauthorized, "Not authenticated")

                    val requestPayload = DataPayload.build("deleteUser") {
                        param("user_id", userId)
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

                route("/friends") {

                    route("/notifications") {

                        get {
                            log.info("GET /api/v1/users/friends/notifications")
                            // "getFriendRequests
                            val userId = verifyJWTandGetUserId(call)
                                ?: return@get call.respond(HttpStatusCode.Unauthorized, "Not authenticated")
                            val requestPayload = DataPayload.build("getFriendRequests") {
                                param("user_id", userId)
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

                        post("/accept") {
                            log.info("POST /api/v1/users/friends/notifications/accept")
                            // "acceptFriendRequest"
                            handleFriendAction(
                                call,
                                "acceptFriendRequest",
                                "friend_id"
                            )
                        }

                        post("/deny") {
                            log.info("POST /api/v1/users/friends/notifications/deny")
                            // "denyFriendRequest"
                            handleFriendAction(
                                call,
                                "denyFriendRequest",
                                "friend_id"
                            )
                        }
                    }

                    post("/request") {
                        log.info("POST /api/v1/users/friends/request")
                        // "addFriendRequest"
                        handleFriendAction(
                            call,
                            "addFriendRequest",
                            "friend_id"
                        )
                    }

                    post("/remove") {
                        log.info("POST /api/v1/users/friends/remove")
                        // "removeFriend"
                        handleFriendAction(
                            call,
                            "removeFriend",
                            "friend_id"
                        )
                    }

                    get("/list") {
                        log.info("GET /api/v1/users/friends/list")
                        // "getFriendsList
                        handleFriendAction(
                            call,
                            "getFriendsList",
                            null
                        )
                    }

                    post("/find") {
                        log.info("POST /api/v1/users/friends/find")
                        // "findFriend"
                        handleFriendAction(
                            call,
                            "findFriend",
                            "find-username"
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
        "userInfo" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "findByUsername" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "updateUserInfo" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "deleteUser" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "getFriendRequests" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "acceptFriendRequest" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "denyFriendRequest" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "addFriendRequest" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "removeFriend" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "getFriendsList" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        "findFriend" -> call.respond(
            HttpStatusCode.OK,
            result.params
        )

        else -> call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("error" to "Unknown operation type")
        )
    }
}
