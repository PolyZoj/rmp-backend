package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import common.DataPayload
import ru.polyZoj.db.*
import common.kafka.KafkaConsumerService
import common.kafka.KafkaProducerService
import common.kafka.createKafkaConsumer
import common.kafka.createKafkaProducer
import common.models.Achievement
import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.polyZoj.repositories.AchievementFactory
import ru.polyZoj.repositories.ChallengesRepository
import ru.polyZoj.repositories.getDailyAchievements
import ru.polyZoj.repositories.getWeeklyAchievements
import java.time.LocalDate


fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)

fun Application.module() {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    val log = logger<Application>()

    val ds = DataSourceConfig()
    DatabaseFactory.init(ds)

    val kafkaProducer = createKafkaProducer()
    val producerService = KafkaProducerService(kafkaProducer)

    val kafkaConsumer = createKafkaConsumer("challenges-interface-consumer")
    val consumerService = KafkaConsumerService(kafkaConsumer, listOf("challenges-requests"))

    val challengesRepository = ChallengesRepository()

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
        log.info("Received message: $data")
        val command = data.message
        when (command) {

            "achievementsAll" -> {
                log.info("achievementsAll command received, data: $data")
                val userId = data.getParam<String>("user_id")
                if (userId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing required user ID"
                    )
                    producerService.send("challenges-responses", conversationId, err)
                } else {
                    val achievements = challengesRepository.getAllAchievements(userId.toInt())
                    val newAchs = populateAchievements(userId, achievements)
                    val finalList = achievements + newAchs

                    val resp = DataPayload.build("success") {
                        param("achievements", finalList)
                    }

                    producerService.send("challenges-responses", conversationId, resp)
                }

            }

            "achievementsDay" -> {
                log.info("achievementsDay command received, data: $data")
                val userId  = data.getParam<String>("user_id")
                val date    = data.getParam<LocalDate>("date") ?: LocalDate.now()

                if (userId == null) {
                    val err = DataPayload.error(
                        status = HttpStatusCode.BadRequest,
                        description = "Missing required user ID"
                    )
                    producerService.send("challenges-responses", conversationId, err)
                } else {
                    val list = challengesRepository.getAchievementsByDate(userId.toInt(), date)

                    val resp = DataPayload.build("success") {
                        param("achievements", list)
                    }
                    producerService.send("challenges-responses", conversationId, resp)
                }


            }

            "completeAchievement" -> {
                log.info("completeAchievement command received, data: $data")
                val achievementId = data.getParam<String>("achievement_id")

                if (achievementId == null) {
                    val err = DataPayload.error(
                        status      = HttpStatusCode.BadRequest,
                        description = "Missing required achievement ID"
                    )
                    producerService.send("challenges-responses", conversationId, err)
                } else {
                    val rowsUpdated = challengesRepository.setAchievementsAsCompleted(achievementId)

                    val payload = if (rowsUpdated > 0) {
                        DataPayload.build("success") {
                            param("achievement_id", achievementId)
                            param("status", "completed")
                        }
                    } else {
                        DataPayload.error(
                            status      = HttpStatusCode.NotFound,
                            description = "Achievement with id=$achievementId not found"
                        )
                    }

                    producerService.send("challenges-responses", conversationId, payload)
                }
            }

            // not needed for now
//            "createAchievement" -> {
//                log.info("createAchievement command received, data: $data")
//                val userId = data.getParam<String>("user_id")
//                val achievement = data.getParam<Achievement>("achievement")
//            }

            else -> {
                val err = DataPayload.error(
                    status = HttpStatusCode.BadRequest,
                    description = "Unknown command: $command"
                )
                log.error("Unknown command: $command")
                producerService.send("challenges-responses", conversationId, err)
            }
        }
    }

}
