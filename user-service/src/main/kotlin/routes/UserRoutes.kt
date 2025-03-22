package ru.polyZoj.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import ru.polyZoj.repositories.UserReadService
import ru.polyZoj.repositories.UserWriteService
import java.time.LocalDateTime

fun Route.userRoutes() {

    val readService = UserReadService()
    val writeService = UserWriteService()

    route("/users") {

        // GET /users
        get {
            val list = readService.getAllUserTable()
            call.respond(list)
        }

        // GET /users/{id}
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respondText("Invalid user id", status = io.ktor.http.HttpStatusCode.BadRequest)
            val user = readService.getUserById(id)
            if (user == null) {
                call.respondText("User not found", status = io.ktor.http.HttpStatusCode.NotFound)
            } else {
                call.respond(user)
            }
        }

        // POST /users
        post {
            val body = call.receive<CreateUserDTO>()
            val newId = writeService.createUser(
                firstName = body.firstName,
                lastName = body.lastName,
                joinDate = body.joinDate
            )
            call.respondText("Created user with id=$newId")
        }

        // PUT /users/{id}/name
        put("/{id}/name") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respondText("Invalid user id", status = io.ktor.http.HttpStatusCode.BadRequest)
            val body = call.receive<UpdateUserNameDTO>()
            val updated = writeService.updateUserName(id, body.firstName, body.lastName)
            if (updated) {
                call.respondText("User $id is updated")
            } else {
                call.respondText("User $id not found", status = io.ktor.http.HttpStatusCode.NotFound)
            }
        }
    }
}

data class CreateUserDTO(
    val firstName: String,
    val lastName: String,
    val joinDate: LocalDateTime? = null
)

data class UpdateUserNameDTO(
    val firstName: String,
    val lastName: String
)
