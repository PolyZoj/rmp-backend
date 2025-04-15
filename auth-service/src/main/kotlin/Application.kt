package ru.polyZog

import io.ktor.server.application.*
import ru.polyZog.configs.configureHTTP
import ru.polyZog.routing.configureRouting
import ru.polyZog.configs.configureSecurity
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json


fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
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


    configureHTTP()
    configureSecurity()
    configureRouting()
}
