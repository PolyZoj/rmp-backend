package ru.polyZoj.exceptions

/**
 * Исключение, выбрасываемое при ошибке в базе данных
 */
class DatabaseException(message: String) : RuntimeException(message)

/**
 * Исключение, выбрасываемое когда пользователь не найден
 */
class UserNotFoundException(message: String) : RuntimeException(message)

/**
 * Исключение, выбрасываемое когда пользователь уже существует
 */
class UserAlreadyExistsException(message: String) : RuntimeException(message)

/**
 * Исключение, выбрасываемое при неверной аутентификации
 */
class AuthenticationException(message: String) : RuntimeException(message)

/**
 * Исключение, выбрасываемое при ошибке валидации
 */
class ValidationException(message: String) : RuntimeException(message)

/**
 * Исключение, выбрасываемое при ошибке доступа
 */
class AccessDeniedException(message: String) : RuntimeException(message)

/**
 * Исключение, выбрасываемое при ошибке соединения
 */
class ConnectionException(message: String) : RuntimeException(message) 