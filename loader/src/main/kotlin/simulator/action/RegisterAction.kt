package org.example.simulator.action

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.example.simulator.model.UserCredentials

class RegisterAction(private val client: HttpClient) {
    suspend fun perform(credentials: UserCredentials): String? {
        val response = client.post("http://127.0.0.1:8081/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(credentials.toRegisterPayload())
        }

        return if (response.status.isSuccess()) {
            response.bodyAsText().let { Json.decodeFromString<Map<String, String>>(it)["user_id"] }
        } else null
    }
}
