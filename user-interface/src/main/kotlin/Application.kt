package ru.polyZoj

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import ru.polyZoj.db.DatabaseFactory
import ru.polyZoj.models.AppSerializersModule
import ru.polyZoj.plugins.configureAuthentication
import ru.polyZoj.plugins.configureRouting
import ru.polyZoj.repositories.ReferenceRepository
import ru.polyZoj.repositories.UserRepository
import ru.polyZoj.routes.healthRoutes
import ru.polyZoj.routes.referenceRoutes
import kotlinx.serialization.json.Json

// Логгер для корня приложения
private val logger = LoggerFactory.getLogger("Application")

/**
 * Точка входа в приложение
 */
fun main() {
    // Запускаем сервер с Netty движком
    embeddedServer(Netty, port = 8081, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * Конфигурация модуля приложения
 */
fun Application.module() {
    try {
        logger.info("Initializing application...")
        
        // Инициализация базы данных
        logger.info("Setting up database...")
        DatabaseFactory.init(environment.config)
        
        // Инициализация репозиториев
        logger.info("Creating repositories...")
        val userRepository = UserRepository()
        val referenceRepository = ReferenceRepository()
        
        // Устанавливаем плагины
        install(CallLogging) {
            level = Level.INFO
        }
        
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
                serializersModule = AppSerializersModule
            })
        }
        
        install(StatusPages) {
            // Обработка исключений будет в маршрутизации
        }
        
        // Настройка аутентификации
        configureAuthentication()
        
        // Настройка маршрутизации в едином блоке
        install(Routing) {
            // Основные маршруты
            configureRouting(userRepository, this)
            
            // Дополнительные маршруты
            healthRoutes()
            referenceRoutes(referenceRepository)
        }
        
        logger.info("Application started successfully")
        
        // Обработка остановки приложения
        environment.monitor.subscribe(ApplicationStopping) {
            logger.info("Application is shutting down...")
            // Код для очистки ресурсов при остановке
            logger.info("Application stopped successfully")
        }
    } catch (e: Exception) {
        logger.error("Failed to start application: ${e.message}", e)
        throw e
    }
}
