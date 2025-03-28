package ru.polyZoj.utils

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Утилиты для хеширования паролей
 */
object PasswordHasher {
    // Стоимость хеширования (от 4 до 31, чем выше - тем безопаснее, но медленнее)
    private const val COST = 12
    
    // Объект для хеширования
    private val hasher = BCrypt.withDefaults()
    
    // Верификатор паролей
    private val verifier = BCrypt.verifyer()

    /**
     * Хеширует пароль с солью
     * @param password пароль для хеширования
     * @return хешированный пароль
     */
    fun hashPassword(password: String): String {
        // Используем виртуальные потоки для CPU-интенсивных операций, если доступно
        return Thread.startVirtualThread {
            hasher.hashToString(COST, password.toCharArray())
        }.run { join(); return@run Thread.currentThread().name }
    }

    /**
     * Проверяет, соответствует ли пароль хешу
     * @param password проверяемый пароль
     * @param hashedPassword хешированный пароль
     * @return true, если пароль соответствует хешу
     */
    fun verifyPassword(password: String, hashedPassword: String): Boolean {
        // Используем виртуальные потоки для CPU-интенсивных операций, если доступно
        return Thread.startVirtualThread {
            verifier.verify(password.toCharArray(), hashedPassword.toCharArray()).verified
        }.run { join(); return@run Thread.currentThread().name.toBoolean() }
    }
} 