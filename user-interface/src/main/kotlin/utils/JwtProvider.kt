package ru.polyZoj.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import ru.polyZoj.exceptions.AuthenticationException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

// Секретный ключ для подписи JWT
private const val SECRET = "z9SyJJLLgZWPDFzBJ3Krksd82H7z9Sav"

// Время жизни токена - 7 дней
private const val TOKEN_LIFETIME_DAYS = 7L

// Алгоритм подписи
private val algorithm = Algorithm.HMAC256(SECRET)

/**
 * Генерирует JWT токен для пользователя
 * @param userId ID пользователя
 * @param email Email пользователя
 * @return JWT токен
 */
fun generateJwtToken(userId: Int, email: String): String {
    return JWT.create()
        .withIssuer("user-service")
        .withIssuedAt(Date.from(Instant.now()))
        .withExpiresAt(Date.from(Instant.now().plus(TOKEN_LIFETIME_DAYS, ChronoUnit.DAYS)))
        .withClaim("userId", userId)
        .withClaim("email", email)
        .sign(algorithm)
}

/**
 * Верифицирует JWT токен
 * @param token JWT токен
 * @return декодированный JWT токен
 * @throws AuthenticationException если токен невалидный
 */
fun verifyToken(token: String): DecodedJWT {
    try {
        val verifier = JWT.require(algorithm)
            .withIssuer("user-service")
            .build()
        
        return verifier.verify(token)
    } catch (e: JWTVerificationException) {
        throw AuthenticationException("Недействительный токен: ${e.message}")
    }
}

/**
 * Извлекает ID пользователя из JWT токена
 * @param token JWT токен
 * @return ID пользователя
 */
fun extractUserId(token: String): Int {
    val decoded = verifyToken(token)
    return decoded.getClaim("userId").asInt()
}

/**
 * Извлекает email пользователя из JWT токена
 * @param token JWT токен
 * @return email пользователя
 */
fun extractEmail(token: String): String {
    val decoded = verifyToken(token)
    return decoded.getClaim("email").asString()
} 