package ru.polyZoj

import io.ktor.server.application.*
import ru.polyZoj.db.DatabaseFactory

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    DatabaseFactory.init()
    configureRouting()
}
