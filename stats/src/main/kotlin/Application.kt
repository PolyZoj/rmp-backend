package ru.polyZoj

import io.ktor.server.application.*
import io.ktor.server.application.*
import ru.polyZoj.configs.configureHTTP
import ru.polyZoj.routing.configureRouting
import ru.polyZoj.configs.configureSecurity
import ru.polyZoj.configs.configureHTTP
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            }
        )
    }

    configureSecurity()

    configureHTTP()

    configureRouting()
}
