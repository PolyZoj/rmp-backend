package ru.polyZog.routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.lettuce.core.RedisClient
import io.lettuce.core.api.sync.RedisCommands
import ru.polyZog.models.LoginRequest
import ru.polyZog.models.RegisterRequest
import ru.polyZog.models.TokenResponse
import ru.polyZog.models.User
import ru.polyZog.repositories.UserDataSource
import java.util.*
import kotlin.random.Random

val redisCommands: RedisCommands<String, String> = RedisClient.create("redis://redis:6379").connect().sync()

fun Routing.configureRoutes() {
    post("/register") {
        val request = call.receive<RegisterRequest>()
        try {
            val newUser = User(
                id = UserDataSource.generateUserId(),
                username = request.username,
                password = request.password
            )
            UserDataSource.addUser(newUser)
            call.respond(HttpStatusCode.Created, mapOf("message" to "User created"))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
        }
    }

    post("/login") {
        val request = call.receive<LoginRequest>()
        val user = UserDataSource.findUserByUsername(request.username)

        if (user == null || user.password != request.password) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
            return@post
        }

        val token = JWT.create()
            .withAudience("jwt-audience")
            .withIssuer("https://jwt-provider-domain/")
            .withClaim("userId", user.id)
            .withExpiresAt(Date(System.currentTimeMillis() + 600000))
            .sign(Algorithm.HMAC256("secret"))

        redisCommands.setex("user:${user.id}:token", 600, token)

        call.respond(HttpStatusCode.OK, TokenResponse(token))
    }

}
