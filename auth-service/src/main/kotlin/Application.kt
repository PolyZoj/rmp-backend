package ru.polyZog

import io.ktor.server.application.*
import ru.polyZog.configs.configureHTTP
import ru.polyZog.configs.configureSecurity


fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}



fun Application.module() {
    configureHTTP()
    configureSecurity()
}
