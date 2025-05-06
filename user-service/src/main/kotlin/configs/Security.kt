package ru.polyZoj.configs

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.ApplicationConfig
import ru.polyZoj.models.User
import java.util.Date

data class JwtConfig(
    val secret: String,
    val domain: String,
    val audience: String,
    val realm: String,
    val expiresIn: Long = 3_600_000
) {
    companion object {
        fun fromConfig(config: ApplicationConfig): JwtConfig {
            return JwtConfig(
                secret = config.property("jwt.secret").getString(),
                domain = config.property("jwt.domain").getString(),
                audience = config.property("jwt.audience").getString(),
                realm = config.property("jwt.realm").getString()
            )
        }
    }
}

fun generateToken(user: User, config: JwtConfig): String {
    return JWT.create()
        .withSubject(user.id)
        .withIssuer(config.domain)
        .withAudience(config.audience)
        .withClaim("username", user.username)
        .withClaim("userId", user.id)
        .withExpiresAt(Date(System.currentTimeMillis() + config.expiresIn))
        .sign(Algorithm.HMAC256(config.secret))
}