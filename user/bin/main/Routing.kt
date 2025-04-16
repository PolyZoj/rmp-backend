package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.http.*
import ru.polyZoj.models.UserDTO
import ru.polyZoj.models.Friends
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime

fun Application.configureRouting() {
    routing {
        route("/api/v1/users") {
            // GET /users - получение списка всех пользователей
            get {
                val users = listOf(
                        UserDTO(
                                id = 1,
                                email = "user1@example.com",
                                avatar_url = "https://example.com/avatar1.jpg",
                                password = "hashed_password1",
                                is_admin = false,
                                username = "user1",
                                first_name = "Иван",
                                last_name = "Иванов",
                                date_of_birth = ZonedDateTime.now().minusYears(25),
                                weight = 75.5f,
                                height = 180,
                                primary_health_goal = PrimaryHealthGoal.LOSE_WEIGHT,
                                daily_step_goal = 10000,
                                water_intake_goal = 2000,
                                calorie_goal = 2000,
                                workouts_count = 3,
                                clubs = listOf(1, 2),
                                created_at = ZonedDateTime.now().minusDays(30),
                                updated_at = ZonedDateTime.now()
                        ),
                        UserDTO(
                                id = 2,
                                email = "user2@example.com",
                                avatar_url = "https://example.com/avatar2.jpg",
                                password = "hashed_password2",
                                is_admin = true,
                                username = "user2",
                                first_name = "Петр",
                                last_name = "Петров",
                                date_of_birth = ZonedDateTime.now().minusYears(30),
                                weight = 80.0f,
                                height = 175,
                                primary_health_goal = PrimaryHealthGoal.GAIN_MUSCLE,
                                daily_step_goal = 8000,
                                water_intake_goal = 2500,
                                calorie_goal = 2500,
                                workouts_count = 5,
                                clubs = listOf(1),
                                created_at = ZonedDateTime.now().minusDays(60),
                                updated_at = ZonedDateTime.now().minusDays(1)
                        )
                )
                call.respond(users)
            }
        }
    }
}