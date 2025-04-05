package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.http.*
import ru.polyZoj.models.Challenge
import ru.polyZoj.models.UserChallengeProgress
import ru.polyZoj.models.ChallengeReward
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime

fun Application.configureRouting() {
    routing {
        route("/api/v1/challenges") {
            // GET /api/v1/challenges - получение списка доступных челленджей
            get {
                val challenges = listOf(
                    Challenge(
                        challengeId = 1,
                        challengeType = "daily",
                        title = "8000 шагов",
                        description = "Пройти 8000 шагов сегодня",
                        metricType = "steps",
                        targetValue = 8000,
                        startTime = ZonedDateTime.now(),
                        endTime = ZonedDateTime.now().plusDays(1),
                        createdBy = "system",
                        status = "active"
                    ),
                    Challenge(
                        challengeId = 2,
                        challengeType = "weekly",
                        title = "3 тренировки",
                        description = "Сделать 3 тренировки за неделю",
                        metricType = "workout",
                        targetValue = 3,
                        startTime = ZonedDateTime.now(),
                        endTime = ZonedDateTime.now().plusDays(7),
                        createdBy = "system",
                        status = "active"
                    )
                )
                call.respond(challenges)
            }

            // GET /api/v1/challenges/{id} - получение детальной информации по челленджу
            get("{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id челленджа")
                    return@get
                }
                val challenge = Challenge(
                    challengeId = id,
                    challengeType = "daily",
                    title = "8000 шагов",
                    description = "Пройти 8000 шагов сегодня",
                    metricType = "steps",
                    targetValue = 8000,
                    startTime = ZonedDateTime.now(),
                    endTime = ZonedDateTime.now().plusDays(1),
                    createdBy = "system",
                    status = "active"
                )
                call.respond(challenge)
            }

            // GET /api/v1/challenges/users/{userId} - получение активных челленджей пользователя
            get("/users/{userId}/") {
                val userId = call.parameters["userId"]?.toLongOrNull()
                if (userId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                    return@get
                }
                val progressList = listOf(
                    UserChallengeProgress(
                        challengeId = 1,
                        userId = userId,
                        progressValue = 4000,
                        completedAt = null,
                        rewardGranted = false
                    ),
                    UserChallengeProgress(
                        challengeId = 2,
                        userId = userId,
                        progressValue = 1,
                        completedAt = null,
                        rewardGranted = false
                    )
                )
                call.respond(progressList)
            }

            // POST /api/v1/challenges - создание нового (пользовательского) челленджа
            post {
                val challenge = call.receive<Challenge>()
                KafkaProducerWrapper.send(
                    topic = "challenge.created",
                    key = challenge.challengeId.toString(),
                    message = Json.encodeToString(challenge)
                )
                call.respond(HttpStatusCode.Created, challenge)
            }

            // POST /api/v1/challenges/{id}/progress - обновление прогресса по челленджу
            // Возможно не нужен, а будем слушать топик прогресса из другого сервиса
            post("/{id}/progress") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id челленджа")
                    return@post
                }
                val progress = call.receive<UserChallengeProgress>()
                KafkaProducerWrapper.send(
                    topic = "challenge.progress.updated",
                    key = id.toString(),
                    message = Json.encodeToString(progress)
                )
                call.respond(HttpStatusCode.OK, progress)
            }

            // GET /api/v1/challenges/clubs/{clubId} - получение информации по командным челленджам
            get("/clubs/{clubId}") {
                val clubId = call.parameters["clubId"] ?: ""
                // Здесь будет агрегированный результата для команды
                val teamChallenge = mapOf(
                    "clubId" to clubId,
                    "challenge" to "Командный челлендж: шаги",
                    "aggregatedProgress" to 15000
                )
                call.respond(teamChallenge)
            }

            // GET /api/v1/challenges/rewards - получение информации о типах наград
            // хз надо ли
            get("/rewards") {
                val rewards = listOf(
                    ChallengeReward(
                        rewardId = 1,
                        challengeId = 1,
                        rewardType = "XP",
                        rewardValue = 100
                    ),
                    ChallengeReward(
                        rewardId = 2,
                        challengeId = 2,
                        rewardType = "medal",
                        rewardValue = 1
                    )
                )
                call.respond(rewards)
            }
        }
    }
}
