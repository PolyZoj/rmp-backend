package ru.polyZoj.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import ru.polyZoj.exceptions.*
import ru.polyZoj.models.*
import ru.polyZoj.repositories.UserRepository
import ru.polyZoj.utils.extractUserId
import ru.polyZoj.utils.generateJwtToken

private val logger = LoggerFactory.getLogger("Routing")

/**
 * Конфигурирует маршрутизацию приложения
 */
fun configureRouting(userRepository: UserRepository, routing: Route) {
    // Маршруты, не требующие аутентификации
    routing.route("/api/v1") {
        // Регистрация нового пользователя
        post("/register") {
            try {
                // Получаем и валидируем данные регистрации
                val registration = call.receive<UserRegistration>()
                
                // Проверяем обязательные поля
                validateRegistration(registration)
                
                // Создаем пользователя
                val user = userRepository.registerUser(registration)
                
                // Возвращаем успешный ответ
                call.respond(HttpStatusCode.Created, SuccessResponse(
                    success = true,
                    message = "Пользователь успешно зарегистрирован",
                    data = mapOf("user_id" to user.toString())
                ))
            } catch (e: Exception) {
                handleException(call, e)
            }
        }
        
        // Аутентификация пользователя
        post("/login") {
            try {
                // Получаем учетные данные
                val credentials = call.receive<UserCredentials>()
                
                // Аутентифицируем пользователя и получаем ID
                val userId = userRepository.checkCredentials(credentials)
                    ?: throw AuthenticationException("Неверный email или пароль")
                    
                // Получаем email пользователя
                val userEmail = userRepository.getUserById(userId)?.email
                    ?: throw UserNotFoundException("Пользователь не найден")
                    
                // Генерируем токен
                val token = generateJwtToken(userId, userEmail)
                
                // Возвращаем токен
                call.respond(HttpStatusCode.OK, SuccessResponse(
                    success = true,
                    data = mapOf("token" to token)
                ))
            } catch (e: Exception) {
                handleException(call, e)
            }
        }
        
        // Получение справочников
        route("/reference") {
            // Получение всех систем единиц измерения
            get("/unit-systems") {
                try {
                    val unitSystems = userRepository.getUnitSystems()
                    call.respond(HttpStatusCode.OK, unitSystems)
                } catch (e: Exception) {
                    handleException(call, e)
                }
            }
            
            // Получение всех систем измерения энергии
            get("/energy-systems") {
                try {
                    val energySystems = userRepository.getEnergySystems()
                    call.respond(HttpStatusCode.OK, energySystems)
                } catch (e: Exception) {
                    handleException(call, e)
                }
            }
            
            // Получение всех целей по здоровью
            get("/health-goals") {
                try {
                    val healthGoals = userRepository.getHealthGoals()
                    call.respond(HttpStatusCode.OK, healthGoals)
                } catch (e: Exception) {
                    handleException(call, e)
                }
            }
        }
    }
    
    // Маршруты, требующие аутентификации
    routing.authenticate("jwt") {
        route("/api/v1/user") {
            // Получение информации о пользователе
            get {
                try {
                    val userId = call.principal<UserIdPrincipal>()?.id?.toInt()
                        ?: throw AuthenticationException("Неверный токен")
                    
                    val userInfo = userRepository.getUserById(userId)
                        ?: throw UserNotFoundException("Пользователь не найден")
                        
                    call.respond(HttpStatusCode.OK, userInfo)
                } catch (e: Exception) {
                    handleException(call, e)
                }
            }
            
            // Изменение пароля
            post("/change-password") {
                try {
                    val userId = call.principal<UserIdPrincipal>()?.id?.toInt()
                        ?: throw AuthenticationException("Неверный токен")
                    
                    val request = call.receive<PasswordChangeRequest>()
                    val result = userRepository.changePassword(userId, request.oldPassword, request.newPassword)
                    
                    if (result) {
                        call.respond(HttpStatusCode.OK, SuccessResponse(
                            success = true,
                            message = "Пароль успешно изменен"
                        ))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(
                            status = HttpStatusCode.InternalServerError.value,
                            message = "Не удалось изменить пароль"
                        ))
                    }
                } catch (e: Exception) {
                    handleException(call, e)
                }
            }
            
            // Обновление физических параметров пользователя
            put("/parameters") {
                try {
                    val userId = call.principal<UserIdPrincipal>()?.id?.toInt()
                        ?: throw AuthenticationException("Неверный токен")
                    
                    val parameters = call.receive<UserParametersDTO>()
                    
                    // Проверяем, что обновляемый пользователь - тот же, что и аутентифицированный
                    if (parameters.userId != userId) {
                        throw AccessDeniedException("Вы не можете изменять параметры другого пользователя")
                    }
                    
                    val updated = userRepository.updateUserParameters(userId, parameters)
                    if (updated) {
                        val updatedParams = userRepository.getUserById(userId)?.parameters
                        call.respond(HttpStatusCode.OK, updatedParams ?: parameters)
                    } else {
                        throw DatabaseException("Не удалось обновить параметры пользователя")
                    }
                } catch (e: Exception) {
                    handleException(call, e)
                }
            }
            
            // Обновление предпочтений пользователя
            put("/preferences") {
                try {
                    val userId = call.principal<UserIdPrincipal>()?.id?.toInt()
                        ?: throw AuthenticationException("Неверный токен")
                    
                    val preferences = call.receive<UserPreferences>()
                    val updated = userRepository.updateUserPreferences(userId, preferences)
                    
                    if (updated) {
                        val updatedPrefs = userRepository.getUserById(userId)?.preferences
                        call.respond(HttpStatusCode.OK, updatedPrefs ?: preferences)
                    } else {
                        throw DatabaseException("Не удалось обновить предпочтения пользователя")
                    }
                } catch (e: Exception) {
                    handleException(call, e)
                }
            }
        }
    }
}

/**
 * Валидирует данные регистрации
 * @param registration данные регистрации
 * @throws ValidationException если данные невалидны
 */
private fun validateRegistration(registration: UserRegistration) {
    val errors = mutableListOf<String>()
    
    // Проверка email
    if (!registration.email.matches(Regex("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$"))) {
        errors.add("Некорректный email")
    }
    
    // Проверка пароля (минимум 8 символов, хотя бы 1 цифра и 1 буква)
    if (registration.password.length < 8 || 
        !registration.password.any { it.isDigit() } || 
        !registration.password.any { it.isLetter() }) {
        errors.add("Пароль должен содержать минимум 8 символов, хотя бы 1 цифру и 1 букву")
    }
    
    // Проверка имени и фамилии
    if (registration.firstName.isBlank()) {
        errors.add("Имя не может быть пустым")
    }
    
    if (registration.lastName.isBlank()) {
        errors.add("Фамилия не может быть пустой")
    }
    
    // Проверка физических параметров
    if (registration.weight <= 0) {
        errors.add("Вес должен быть положительным числом")
    }
    
    if (registration.height <= 0) {
        errors.add("Рост должен быть положительным числом")
    }
    
    if (errors.isNotEmpty()) {
        throw ValidationException(errors.joinToString(", "))
    }
}

/**
 * Обрабатывает исключения и отправляет соответствующий ответ клиенту
 * @param call контекст вызова
 * @param e исключение
 */
private suspend fun handleException(call: ApplicationCall, e: Exception) {
    val (status, message) = when (e) {
        is UserNotFoundException -> HttpStatusCode.NotFound to e.message
        is UserAlreadyExistsException -> HttpStatusCode.Conflict to e.message
        is AuthenticationException -> HttpStatusCode.Unauthorized to e.message
        is AccessDeniedException -> HttpStatusCode.Forbidden to e.message
        is ValidationException -> HttpStatusCode.BadRequest to e.message
        is DatabaseException -> HttpStatusCode.InternalServerError to e.message
        is ConnectionException -> HttpStatusCode.ServiceUnavailable to e.message
        is BadRequestException -> HttpStatusCode.BadRequest to e.message
        is SerializationException -> HttpStatusCode.BadRequest to "Некорректный формат данных"
        else -> {
            logger.error("Непредвиденная ошибка: ${e.message}", e)
            HttpStatusCode.InternalServerError to "Внутренняя ошибка сервера"
        }
    }
    
    call.respond(status, ErrorResponse(
        status = status.value,
        message = message ?: "Ошибка"
    ))
} 