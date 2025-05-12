package org.example.simulator.action

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.example.simulator.model.UserCredentials

class RegisterAction(private val client: HttpClient) {
    suspend fun perform(credentials: UserCredentials): Boolean {
        val response = client.post("http://127.0.0.1:8081/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(credentials.toRegisterPayload())
        }
        return response.status == HttpStatusCode.Created
    }
}
