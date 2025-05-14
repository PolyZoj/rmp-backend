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
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.kafka.topics.*
import common.models.Achievement
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.polyZoj.repositories.AchievementFactory
import ru.polyZoj.repositories.ChallengesRepository
import ru.polyZoj.repositories.getDailyAchievements
import ru.polyZoj.repositories.getWeeklyAchievements
import java.time.LocalDate

object AppScopes {
    /** Detached from individual requests, cancelled only on shutdown. */
    val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

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

    fun log(level: Level, message: String, context: String) {
        AppScopes.loggerScope.launch(Dispatchers.IO) {
            logger.log("challenges-interface", level, message, context)
        }
    }

    fun logInfo(ctx: String, msg: String) =  log( Level.INFO, msg, ctx)
    fun logError(ctx: String, msg: String) = log( Level.ERROR, msg, ctx)

    val kafkaConsumer = createKafkaConsumer("challenges-interface-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf(CHALLENGES_SERVICE_REQ))

    val challengesRepository = ChallengesRepository(logger)

    fun populateAchievements(userId: String, achievements: List<Achievement>): List<Achievement> {
        val newAchs: MutableList<Achievement> = emptyList<Achievement>().toMutableList()
        val noWeeklyAch = achievements.getWeeklyAchievements(userId.toInt(), LocalDate.now()).isEmpty()
        val noDailyAch = achievements.getDailyAchievements(userId.toInt(), LocalDate.now()).isEmpty()

        if (noWeeklyAch) {
            for (i in 1..3) {
                newAchs.add(AchievementFactory.createAchievementHelper(
                    userId,
                    startDate = LocalDate.now(),
                    endDate = LocalDate.now().plusDays(6)
                ))
            }
        }
        if (noDailyAch) {
            for (i in 1..3) {
                newAchs.add(AchievementFactory.createAchievementHelper(
                    userId,
                    startDate = LocalDate.now(),
                    endDate = LocalDate.now()
                ))
            }
        }

        return newAchs
    }

    consumerService.startConsuming { conversationId, data ->
        val command = data.message
        logInfo(command, "Received: $data")
        when (command) {

            "achievementsAll" -> {
                val userId = data.getParam<String>("user_id")
                val resp = if (userId == null) {
                    DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing required user ID"
                    )
                } else {
                    val achievements = challengesRepository.getAllAchievements(userId.toInt())
                    val newAchs = populateAchievements(userId, achievements)
                    newAchs.map { achievement ->
                        challengesRepository.addAchievement(achievement)
                    }
                    val finalList = achievements + newAchs

                    DataPayload.build("success") {
                        param("achievements", finalList)
                    }
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $CHALLENGES_SERVICE_RES: $resp")
                producerService.send(CHALLENGES_SERVICE_RES, conversationId, resp)
            }

            "achievementsDay" -> {
                val userId  = data.getParam<String>("user_id")
                val date    = data.getParam<LocalDate>("date") ?: LocalDate.now()
                val resp = if (userId == null) {
                    DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing required user ID"
                    )
                } else {
                    val list = challengesRepository.getAchievementsByDate(userId.toInt(), date)

                    DataPayload.build("success") {
                        param("achievements", list)
                    }
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $CHALLENGES_SERVICE_RES: $resp")
                producerService.send(CHALLENGES_SERVICE_RES, conversationId, resp)
            }

            "completeAchievement" -> {
                val achievement = data.getParam<Achievement>("achievement")

                val resp = if (achievement == null) {
                    DataPayload.error(
                        status      = HttpStatusCode.BadRequest,
                        description = "Missing required achievement"
                    )
                } else {
                    val rowsUpdated = challengesRepository.setAchievementsAsCompleted(achievement)

                    if (rowsUpdated > 0) {
                        DataPayload.build("success") {
                            param("achievement_id", achievement.id)
                            param("status", "completed")
                        }
                    } else {
                        DataPayload.error(
                            status      = HttpStatusCode.NotFound,
                            description = "Achievement with id=${achievement.id} not found"
                        )
                    }

                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $CHALLENGES_SERVICE_RES: $resp")
                producerService.send(CHALLENGES_SERVICE_RES, conversationId, resp)
            }

            "createAchievement" -> {
                val achievement = data.getParam<Achievement>("achievement")

                val resp = if (achievement == null) {
                    DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing required achievement"
                    )
                } else {
                    val ach = challengesRepository.addAchievement(achievement)
                    if (ach != null) {
                        DataPayload.build("success") {
                            param("achievement", ach)
                        }
                    } else {
                        DataPayload.error(
                            status      = HttpStatusCode.InternalServerError,
                            description = "Could not create achievement"
                        )
                    }
                }
                if (resp.message == "error") logError(command, "Error response – $resp")
                logInfo(command, "Sending to $CHALLENGES_SERVICE_RES: $resp")
                producerService.send(CHALLENGES_SERVICE_RES, conversationId, resp)
            }

            else -> {
                val err = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command: $command"
                )
                logError(command, "Error response - $err")
                logInfo(command, "Sending to $CHALLENGES_SERVICE_RES: $err")
                producerService.send(CHALLENGES_SERVICE_RES, conversationId, err)
            }
        }
    }

}
