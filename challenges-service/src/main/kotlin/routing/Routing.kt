package ru.polyZoj.routing

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import ru.polyZoj.KafkaProducerWrapper
import ru.polyZoj.models.Challenge
import ru.polyZoj.repositories.ChallengeRepository
import ru.polyZoj.repositories.ChallengeRewardRepository
import ru.polyZoj.repositories.UserChallengeRepository

fun Application.configureRouting() {
    routing {
        route("/api/v1/challenges") {

            // GET /api/v1/challenges
            get {
                val challenges = ChallengeRepository.getAllChallenges()
                call.respond(challenges)
            }

            // GET /api/v1/challenges/{id}
            get("{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid challenge id")
                    return@get
                }
                val challenge = ChallengeRepository.getChallengeById(id)
                if (challenge == null) {
                    call.respond(HttpStatusCode.NotFound, "Challenge not found")
                } else {
                    call.respond(challenge)
                }
            }

            // GET /api/v1/challenges/users/{userId}
            get("/users/{userId}") {
                val userId = call.parameters["userId"]?.toIntOrNull()
                if (userId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid user id")
                    return@get
                }
                val progressList = UserChallengeRepository.getUserChallenges(userId)
                call.respond(progressList)
            }

            // POST /api/v1/challenges (create new challenge)
            post {
                val challenge = call.receive<Challenge>()
                // Insert into DB
                val createdChallenge = ChallengeRepository.createChallenge(challenge)

                // Optionally produce a message to Kafka
                KafkaProducerWrapper.send(
                    topic = "challenge.created",
                    key = createdChallenge.challengeId.toString(),
                    message = Json.encodeToString(Challenge.serializer(), createdChallenge)
                )

                call.respond(HttpStatusCode.Created, createdChallenge)
            }

            // GET /api/v1/challenges/clubs/{clubId}
            get("/clubs/{clubId}") {
                val clubId = call.parameters["clubId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    "Club ID required"
                )
                // This is a naive example. Real logic: you’d find all the users in that club,
                // sum their progress for the relevant challenge, etc.
                // We just respond with a dummy aggregated result.
                val aggregatedProgress = mapOf(
                    "clubId" to clubId,
                    "challenge" to "Team steps challenge",
                    "aggregatedProgress" to 15000 // for example
                )
                call.respond(aggregatedProgress)
            }

            // GET /api/v1/challenges/rewards
            get("/rewards") {
                val rewards = ChallengeRewardRepository.getRewards()
                call.respond(rewards)
            }
        }
    }
}
