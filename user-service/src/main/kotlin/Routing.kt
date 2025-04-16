package ru.polyZog

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        route("/api/v1/users") {
            get("/") {
                call.respondText("Hello World!")
            }
        }
    }
}
