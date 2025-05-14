package org.example.simulator.action

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.example.simulator.model.UserCredentials

class LoginAction(private val client: HttpClient) {
    suspend fun perform(credentials: UserCredentials): String? {
        println("Time: ${System.currentTimeMillis()}, username: ${credentials.username}, password: ${credentials.password}")
        val response = client.post("http://127.0.0.1:8081/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(credentials)
        }
        return if (response.status.isSuccess()) {
            println(response.bodyAsText())
            response.bodyAsText().let { Json.decodeFromString<Map<String, String>>(it)["token"] }
        } else null
    }
}
