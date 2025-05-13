package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.DataPayload
import common.Level
import common.LogSender
import ru.polyZoj.db.*
import common.exceptions.DuplicateFieldException
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.kafka.topics.*
import common.models.FriendshipStatus
import common.models.UserBasicInfo
import io.ktor.http.HttpStatusCode
import common.models.UserRegistration
import common.models.UserUpdatable
import ru.polyZoj.repositories.UserRepository


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

    val ds = DataSourceConfig()
    DatabaseFactory.init(ds)

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val logger = LogSender(kafkaProducer)
    fun logInfo(ctx: String, msg: String) = logger.log("user-interface", Level.INFO, msg, ctx)
    fun logError(ctx: String, msg: String) = logger.log("user-interface", Level.ERROR, msg, ctx)

    val kafkaConsumer = createKafkaConsumer("user-interface-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf(USER_SERVICE_REQ))

    val userRepository = UserRepository(logger)

    suspend fun friendshipAction(
        command: String,
        payload: DataPayload,
        status: FriendshipStatus?,
        conversationId: String,
        action: suspend (Int, Int, FriendshipStatus?) -> Boolean
    ) {
        val userId   = payload.getParam<String>("user_id")
        val friendId = payload.getParam<String>("friend_id")
        if (userId == null || friendId == null) {
            val err = DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID or friend ID")
            logError(command, "Validation failed – $err")
            logInfo(command, "Sending to $USER_SERVICE_RES: $err")
            producerService.send(USER_SERVICE_RES, conversationId, err)
            return
        }

        val success = action(userId.toInt(), friendId.toInt(), status)
        val resp = if (success) {
            DataPayload.build("success") { param("success", true) }
        } else {
            DataPayload.error(HttpStatusCode.NotFound, "User or friend not found")
        }

        if (resp.message == "error") logError(command, "Operation failed – $resp")
        logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
        producerService.send(USER_SERVICE_RES, conversationId, resp)
    }

    consumerService.startConsuming { conversationId, data ->
        val command = data.message
        logInfo(command, "Received: $data")

        when (command) {
            "login" -> {
                val username = data.getParam<String>("username")
                val password = data.getParam<String>("password")
                val response = if (username == null || password == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Invalid credentials")
                } else {
                    val userId = userRepository.login(username, password)
                    if (userId != null) {
                        DataPayload.build(userId.toString()) {
                            param("user_id", userId.toString())
                        }
                    } else {
                        DataPayload.error(HttpStatusCode.BadRequest, "Invalid credentials")
                    }
                }
                if (response.message == "error") logError(command, "Error response - $response")
                logInfo(command, "Sending to $USER_SERVICE_RES: $response")
                producerService.send(USER_SERVICE_RES, conversationId, response)
            }

            "findByUsername" -> {
                val username = data.getParam<String>("username")
                val resp = if (username == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing username")
                } else {
                    val id = userRepository.findByUsername(username)
                    if (id != null) DataPayload.build(id.toString()) { param("user_id", id.toString()) }
                    else DataPayload.error(HttpStatusCode.NotFound, "User not found")
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "register" -> {
                val reg = data.getParam<UserRegistration>("user_registration")
                val resp = if (reg == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Invalid registration data")
                } else try {
                    val id = userRepository.createUser(reg)
                    DataPayload.build(id.toString()) { param("user_id", id.toString()) }
                } catch (e: DuplicateFieldException) {
                    DataPayload.error(HttpStatusCode.Conflict, when (e.fieldName) {
                        "email" -> "Email already registered."
                        "username" -> "Username is already taken."
                        else -> "Duplicate field: ${e.fieldName}"
                    })
                } catch (e: Exception) {
                    DataPayload.error(HttpStatusCode.InternalServerError, "${e.message}")
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "userInfo" -> {
                val userId = data.getParam<String>("user_id")
                val selfId = data.getParam<String>("self_id")
                val resp = if (userId == null || selfId == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID or self ID")
                } else {
                    val dto = userRepository.getUserDTO(userId.toInt())
                    if (dto == null) {
                        DataPayload.error(HttpStatusCode.NotFound, "User not found")
                    } else {
                        val status = userRepository.getFriendshipStatus(selfId.toInt(), userId.toInt())
                        DataPayload.build(userId) {
                            with(dto.user) {
                                param("user_id", userId)
                                param("first_name", firstName)
                                param("last_name", lastName)
                                param("email", email)
                                param("avatar_url", avatarUrl)
                                param("is_admin", isAdmin)
                                param("club_id", clubId)
                            }
                            param("username", dto.username)
                            param("weight", dto.weight)
                            param("height", dto.height)
                            param("birth_date", dto.birthDate)
                            param("unit_system", dto.unitSystem)
                            param("energy_system", dto.energySystem)
                            param("health_goal", dto.healthGoal)
                            param("daily_step_goal", dto.dailyStepGoal)
                            param("water_intake_goal", dto.waterIntakeGoal)
                            param("calorie_goal", dto.calorieGoal)
                            param("sleep_goal", dto.sleepGoal)
                            param("workouts_goal", dto.workoutsGoal)
                            param("status", status)
                        }
                    }
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "updateUserInfo" -> {
                val userId = data.getParam<String>("user_id")
                val upd    = data.getParam<UserUpdatable>("user_data")
                val resp = if (userId == null || upd == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID or data")
                } else {
                    val ok = userRepository.updateUser(userId.toInt(), upd)
                    if (ok) DataPayload.build("success") { param("success", true) }
                    else DataPayload.error(HttpStatusCode.NotFound, "User not found")
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "deleteUser" -> {
                val userId = data.getParam<String>("user_id")
                val resp = if (userId == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID")
                } else {
                    val deleted = userRepository.deleteUser(userId.toInt())
                    if (deleted) DataPayload.build("success") { param("success", true) }
                    else DataPayload.error(HttpStatusCode.NotFound, "User not found")
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "getFriendRequests" -> {
                val userId = data.getParam<String>("user_id")
                val resp = if (userId == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID")
                } else {
                    val list = userRepository.getFriendshipRequests(userId.toInt()).mapNotNull {
                        val uname = userRepository.findUsernameById(it)
                        if (uname != null) UserBasicInfo(it, uname) else null
                    }
                    DataPayload.build(userId) { param("friend_requests", list) }
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "acceptFriendRequest" -> friendshipAction(command, data, FriendshipStatus.ACCEPTED, conversationId, userRepository::setFriendshipRequestStatus)
            "denyFriendRequest"   -> friendshipAction(command, data, FriendshipStatus.REJECTED, conversationId, userRepository::setFriendshipRequestStatus)
            "addFriendRequest"    -> friendshipAction(command, data, FriendshipStatus.PENDING,  conversationId, userRepository::setFriendshipRequestStatus)
            "removeFriend"        -> friendshipAction(command, data, null,                      conversationId, userRepository::removeFriendship)

            "getFriendsList" -> {
                val userId = data.getParam<String>("user_id")
                val resp = if (userId == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID")
                } else {
                    val ids = userRepository.getFriends(userId.toInt())
                    val info = userRepository.getUsersBasicInfo(ids)
                    DataPayload.build(userId) { param("friends", info) }
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "findFriend" -> {
                val userId = data.getParam<String>("user_id")
                val search = data.getParam<String>("find_username")
                val resp = if (userId == null || search == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID or search string")
                } else {
                    val allIds = userRepository.getAllIds() - userId.toInt()
                    val matched = userRepository.findUserIdsByUsernameSubstring(allIds, search)
                    val info = userRepository.getUsersBasicInfo(matched)
                    DataPayload.build(userId) { param("possible_friend", info) }
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            "updateClubId" -> {
                val userId = data.getParam<String>("user_id")
                val clubId = data.getParam<String>("club_id")
                val resp = if (userId == null || clubId == null) {
                    DataPayload.error(HttpStatusCode.BadRequest, "Missing user ID or club ID")
                } else {
                    val ok = userRepository.updateClubId(userId.toInt(), clubId.toInt())
                    if (ok) DataPayload.build("success") { param("success", true) }
                    else DataPayload.error(HttpStatusCode.NotFound, "User or club not found")
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $USER_SERVICE_RES: $resp")
                producerService.send(USER_SERVICE_RES, conversationId, resp)
            }

            else -> {
                val err = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command: $command"
                )
                logError(command,"Unknown command: $command")
                logInfo(command, "Sending to $USER_SERVICE_RES: $err")
                producerService.send(USER_SERVICE_RES, conversationId, err)
            }
        }
    }

}
