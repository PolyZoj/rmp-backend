package ru.polyZoj.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import ru.polyZoj.models.ErrorResponse
import ru.polyZoj.utils.verifyToken

// Секретный ключ для подписи JWT
private const val SECRET = "z9SyJJLLgZWPDFzBJ3Krksd82H7z9Sav"

// Алгоритм подписи
private val algorithm = Algorithm.HMAC256(SECRET)

/**
 * Класс для аутентификации пользователя по ID
 */
data class UserIdPrincipal(val id: String) : Principal

/**
 * Конфигурирует аутентификацию в приложении
 */
fun Application.configureAuthentication() {
    authentication {
        jwt("jwt") {
            realm = "User Service"
            
            verifier { 
                // Создаем JWT верификатор
                try {
                    JWT.require(algorithm)
                        .withIssuer("user-service")
                        .build()
                } catch (e: Exception) {
                    // Если возникла ошибка при создании верификатора, возвращаем null
                    null
                }
            }
            
            validate { credential ->
                try {
                    // Получаем данные из токена
                    val decodedJWT = credential.payload
                    val userId = decodedJWT.getClaim("userId").asInt()
                    
                    // Создаем principal с ID пользователя
                    UserIdPrincipal(userId.toString())
                } catch (e: Exception) {
                    // Если возникла ошибка при извлечении ID пользователя, возвращаем null
                    null
                }
            }
            
            challenge { _, _ ->
                // Обработка ошибки аутентификации
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(
                        status = HttpStatusCode.Unauthorized.value,
                        message = "Требуется аутентификация"
                    )
                )
            }
        }
    }
} 