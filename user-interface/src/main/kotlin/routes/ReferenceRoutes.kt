package ru.polyZoj.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import ru.polyZoj.models.ErrorResponse
import ru.polyZoj.repositories.ReferenceRepository

fun Route.referenceRoutes(referenceRepository: ReferenceRepository) {
    // Маршруты для справочных данных
    route("/references") {
        // Получение списка единиц измерения
        get("/unit-systems") {
            try {
                val unitSystems = referenceRepository.getUnitSystems()
                call.respond(unitSystems)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(500, "Ошибка при получении списка единиц измерения: ${e.message}")
                )
            }
        }
        
        // Получение списка энергетических систем
        get("/energy-systems") {
            try {
                val energySystems = referenceRepository.getEnergySystems()
                call.respond(energySystems)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(500, "Ошибка при получении списка энергетических систем: ${e.message}")
                )
            }
        }
        
        // Получение списка целей здоровья
        get("/health-goals") {
            try {
                val healthGoals = referenceRepository.getHealthGoals()
                call.respond(healthGoals)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(500, "Ошибка при получении списка целей здоровья: ${e.message}")
                )
            }
        }
    }
}