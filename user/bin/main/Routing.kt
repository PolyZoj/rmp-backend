package ru.polyZoj

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import ru.polyZoj.models.Friends
import ru.polyZoj.models.PrimaryHealthGoal
import ru.polyZoj.models.UserDTO

fun Application.configureRouting() {
    routing {
        route("/api/v1/users") {
            // GET /users - получение списка всех пользователей
            get {
                val users =
                        listOf(
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

            // GET /users/{id} - получение информации о конкретном пользователе
            get("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                    return@get
                }

                val user =
                        UserDTO(
                                id = id,
                                email = "user$id@example.com",
                                avatar_url = "https://example.com/avatar$id.jpg",
                                password = "hashed_password$id",
                                is_admin = id == 1L,
                                username = "user$id",
                                first_name = if (id == 1L) "Иван" else "Петр",
                                last_name = if (id == 1L) "Иванов" else "Петров",
                                date_of_birth = ZonedDateTime.now().minusYears(25L + id.toInt()),
                                weight = 70f + id * 5,
                                height = (175 + id).toShort(),
                                primary_health_goal =
                                        if (id == 1L) PrimaryHealthGoal.LOSE_WEIGHT
                                        else PrimaryHealthGoal.GAIN_MUSCLE,
                                daily_step_goal = 8000 + id.toInt() * 1000,
                                water_intake_goal = 2000 + id.toInt() * 500,
                                calorie_goal = (2000 + id.toInt() * 500).toShort(),
                                workouts_count = (3 + id.toInt()).toShort(),
                                clubs = listOf(id.toInt()),
                                created_at = ZonedDateTime.now().minusDays(30L + id.toInt() * 30),
                                updated_at = ZonedDateTime.now().minusDays(id.toLong())
                        )

                call.respond(user)
            }

            // POST /users - создание нового пользователя
            post {
                val user = call.receive<UserDTO>()
                call.respond(HttpStatusCode.Created, user)
            }

            // PUT /users/{id} - обновление информации о пользователе
            put("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                    return@put
                }

                val updatedUser = call.receive<UserDTO>()
                call.respond(HttpStatusCode.OK, updatedUser)
            }

            // DELETE /users/{id} - удаление пользователя
            delete("/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                    return@delete
                }

                call.respond(HttpStatusCode.OK, "Пользователь $id удален")
            }

            // Друзья пользователя
            route("/{userId}/friends") {
                // GET /users/{userId}/friends - получение списка друзей
                get {
                    val userId = call.parameters["userId"]?.toLongOrNull()
                    if (userId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                        return@get
                    }

                    val friends =
                            listOf(
                                    Friends(id_1 = userId, id_2 = userId + 1),
                                    Friends(id_1 = userId, id_2 = userId + 2)
                            )
                    call.respond(friends)
                }

                // POST /users/{userId}/friends - добавление друга
                post {
                    val userId = call.parameters["userId"]?.toLongOrNull()
                    if (userId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Некорректный id пользователя")
                        return@post
                    }

                    val friend = call.receive<Friends>()
                    call.respond(HttpStatusCode.Created, friend)
                }

                // DELETE /users/{userId}/friends/{friendId} - удаление друга
                delete("/{friendId}") {
                    val userId = call.parameters["userId"]?.toLongOrNull()
                    val friendId = call.parameters["friendId"]?.toLongOrNull()

                    if (userId == null || friendId == null) {
                        call.respond(HttpStatusCode.BadRequest, "Некорректные id пользователей")
                        return@delete
                    }

                    call.respond(HttpStatusCode.OK, "Пользователь $friendId удален из друзей")
                }
            }
        }
    }
}
