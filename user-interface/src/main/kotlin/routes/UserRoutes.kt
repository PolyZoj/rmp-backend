package ru.polyZoj.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.polyZoj.models.*
import ru.polyZoj.repositories.UserRepository
import java.time.LocalDateTime

fun Route.userRoutes(userRepository: UserRepository) {
    val logger = LoggerFactory.getLogger("UserRoutes")
    
    // Маршруты для работы с пользователями
    route("/users") {
        // Получение списка всех пользователей
        get {
            try {
                logger.debug("Запрос на получение списка пользователей")
                val users = userRepository.getAllUsers()
                call.respond(users)
            } catch (e: Exception) {
                logger.error("Ошибка при получении списка пользователей", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(500, "Ошибка при получении списка пользователей: ${e.message}")
                )
            }
        }
        
        // Регистрация нового пользователя
        post {
            try {
                val userReg = call.receive<UserRegistration>()
                logger.info("Запрос на регистрацию пользователя: ${userReg.email}")
                
                // Валидация на JDK 21
                validateUserRegistration(userReg)
                
                val userId = userRepository.registerUser(userReg)
                call.respond(
                    HttpStatusCode.Created, 
                    SuccessResponse(
                        message = "Пользователь успешно зарегистрирован",
                        data = mapOf("user_id" to userId.toString())
                    )
                )
            } catch (e: IllegalArgumentException) {
                logger.warn("Ошибка валидации при регистрации пользователя: ${e.message}")
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(400, e.message ?: "Ошибка валидации при регистрации пользователя")
                )
            } catch (e: Exception) {
                logger.error("Ошибка при регистрации пользователя", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(500, "Ошибка при регистрации пользователя: ${e.message}")
                )
            }
        }
        
        // Маршруты для конкретного пользователя
        route("/{id}") {
            // Получение информации о пользователе
            get {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(400, "Некорректный ID пользователя")
                        )
                    
                    logger.debug("Запрос на получение информации о пользователе: $id")
                    
                    val user = userRepository.getUserById(id)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(404, "Пользователь не найден")
                        )
                    
                    call.respond(user)
                } catch (e: Exception) {
                    logger.error("Ошибка при получении информации о пользователе", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(500, "Ошибка при получении информации о пользователе: ${e.message}")
                    )
                }
            }
            
            // Обновление информации о пользователе
            put {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(400, "Некорректный ID пользователя")
                        )
                    
                    val user = call.receive<User>()
                    logger.info("Запрос на обновление пользователя: $id")
                    
                    val success = userRepository.updateUser(id, user)
                    
                    if (success) {
                        call.respond(
                            HttpStatusCode.OK, 
                            SuccessResponse(message = "Информация о пользователе обновлена")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(404, "Пользователь не найден")
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    logger.warn("Ошибка валидации при обновлении пользователя: ${e.message}")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(400, e.message ?: "Ошибка валидации")
                    )
                } catch (e: Exception) {
                    logger.error("Ошибка при обновлении информации о пользователе", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(500, "Ошибка при обновлении информации о пользователе: ${e.message}")
                    )
                }
            }
            
            // Удаление пользователя
            delete {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(400, "Некорректный ID пользователя")
                        )
                    
                    logger.info("Запрос на удаление пользователя: $id")
                    
                    val success = userRepository.deleteUser(id)
                    
                    if (success) {
                        call.respond(
                            HttpStatusCode.OK, 
                            SuccessResponse(message = "Пользователь успешно удален")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(404, "Пользователь не найден")
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Ошибка при удалении пользователя", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(500, "Ошибка при удалении пользователя: ${e.message}")
                    )
                }
            }
            
            // Обновление параметров пользователя
            put("/parameters") {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(400, "Некорректный ID пользователя")
                        )
                    
                    val params = call.receive<UserParametersDTO>()
                    logger.info("Запрос на обновление параметров пользователя: $id")
                    
                    val success = userRepository.updateUserParameters(id, params)
                    
                    if (success) {
                        call.respond(
                            HttpStatusCode.OK, 
                            SuccessResponse(message = "Параметры пользователя обновлены")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(404, "Пользователь не найден")
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Ошибка при обновлении параметров пользователя", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(500, "Ошибка при обновлении параметров пользователя: ${e.message}")
                    )
                }
            }
            
            // Обновление предпочтений пользователя
            put("/preferences") {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(400, "Некорректный ID пользователя")
                        )
                    
                    val prefs = call.receive<UserPreferences>()
                    logger.info("Запрос на обновление предпочтений пользователя: $id")
                    
                    val success = userRepository.updateUserPreferences(id, prefs)
                    
                    if (success) {
                        call.respond(
                            HttpStatusCode.OK, 
                            SuccessResponse(message = "Предпочтения пользователя обновлены")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(404, "Пользователь не найден")
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Ошибка при обновлении предпочтений пользователя", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(500, "Ошибка при обновлении предпочтений пользователя: ${e.message}")
                    )
                }
            }
            
            // Смена пароля пользователя
            put("/password") {
                try {
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(400, "Некорректный ID пользователя")
                        )
                    
                    val passwordRequest = call.receive<PasswordChangeRequest>()
                    logger.info("Запрос на смену пароля пользователя: $id")
                    
                    // Валидация нового пароля
                    if (passwordRequest.newPassword.length < 8) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse(400, "Новый пароль должен содержать минимум 8 символов")
                        )
                    }
                    
                    val success = userRepository.changePassword(
                        id, 
                        passwordRequest.oldPassword,
                        passwordRequest.newPassword
                    )
                    
                    if (success) {
                        call.respond(
                            HttpStatusCode.OK, 
                            SuccessResponse(message = "Пароль успешно изменен")
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse(404, "Пользователь не найден")
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    logger.warn("Ошибка при смене пароля: ${e.message}")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(400, e.message ?: "Ошибка при смене пароля")
                    )
                } catch (e: Exception) {
                    logger.error("Ошибка при смене пароля", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(500, "Ошибка при смене пароля: ${e.message}")
                    )
                }
            }
        }
    }
    
    // Маршрут для проверки учетных данных (для авторизации)
    post("/auth/login") {
        try {
            val credentials = call.receive<UserCredentials>()
            logger.info("Запрос на авторизацию пользователя: ${credentials.email}")
            
            val userId = userRepository.checkCredentials(credentials)
            
            if (userId != null) {
                call.respond(
                    HttpStatusCode.OK, 
                    SuccessResponse(
                        message = "Авторизация успешна",
                        data = mapOf("user_id" to userId.toString())
                    )
                )
            } else {
                logger.warn("Неудачная попытка авторизации для: ${credentials.email}")
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(401, "Неверный email или пароль")
                )
            }
        } catch (e: Exception) {
            logger.error("Ошибка при авторизации", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(500, "Ошибка при авторизации: ${e.message}")
            )
        }
    }
}

// Функция валидации данных для регистрации пользователя
private suspend fun validateUserRegistration(userReg: UserRegistration) {
    // Используем структурированный подход с JDK 21 и корутинами для параллельной валидации
    withContext(Dispatchers.Default) {
        // Проверка email
        if (!userReg.email.matches(Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$"))) {
            throw IllegalArgumentException("Некорректный формат email")
        }
        
        // Проверка пароля
        if (userReg.password.length < 8) {
            throw IllegalArgumentException("Пароль должен содержать минимум 8 символов")
        }
        
        // Проверка имени и фамилии
        if (userReg.firstName.isBlank() || userReg.lastName.isBlank()) {
            throw IllegalArgumentException("Имя и фамилия не должны быть пустыми")
        }
        
        // Проверка физических параметров
        if (userReg.weight <= 0 || userReg.weight > 300) {
            throw IllegalArgumentException("Некорректное значение веса")
        }
        
        if (userReg.height <= 0 || userReg.height > 250) {
            throw IllegalArgumentException("Некорректное значение роста")
        }
        
        // Проверка даты рождения
        try {
            val birthDate = LocalDateTime.parse(userReg.birthDate)
            val now = LocalDateTime.now()
            if (birthDate.isAfter(now)) {
                throw IllegalArgumentException("Дата рождения не может быть в будущем")
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Неверный формат даты рождения")
        }
    }
}
