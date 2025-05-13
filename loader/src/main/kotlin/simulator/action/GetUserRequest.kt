package org.example.simulator.action

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.example.simulator.model.UserCredentials

class GetUserRequest(private val client: HttpClient) {
    suspend fun performByUsername(credentials: UserCredentials) : String? {
        val response = client.get("http://127.0.0.1:8081/api/v1/users/username/${credentials.username}")

        return if (response.status.isSuccess()) {
            response.bodyAsText().let { Json.decodeFromString<Map<String, String>>(it)["user_id"] }
        } else null
    }
}
