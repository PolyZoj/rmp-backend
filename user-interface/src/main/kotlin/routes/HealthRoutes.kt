package ru.polyZoj.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.slf4j.LoggerFactory
import ru.polyZoj.db.DatabaseFactory

fun Route.healthRoutes() {
    val logger = LoggerFactory.getLogger("HealthRoutes")
    
    get("/health") {
        try {
            // Проверяем подключение к БД
            logger.debug("Проверка соединения с БД")
            val isReaderConnected = DatabaseFactory.testReaderConnection()
            val isWriterConnected = DatabaseFactory.testWriterConnection()
            
            if (isReaderConnected && isWriterConnected) {
                logger.debug("Все соединения работают")
                call.respond(HttpStatusCode.OK, mapOf("status" to "OK"))
            } else {
                logger.warn("Проблемы с соединениями к БД: writer=$isWriterConnected, reader=$isReaderConnected")
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf(
                        "status" to "ERROR",
                        "writerDb" to isWriterConnected,
                        "readerDb" to isReaderConnected
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Исключение при проверке здоровья", e)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("status" to "ERROR", "message" to (e.message ?: "Unknown error"))
            )
        }
    }
}