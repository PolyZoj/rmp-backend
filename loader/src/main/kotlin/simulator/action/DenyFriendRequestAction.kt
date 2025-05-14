package org.example.simulator.action

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.example.simulator.model.FriendRequestDTO
import org.example.simulator.model.UserSession

class DenyFriendRequestAction(private val client: HttpClient) {
    suspend fun perform(session: UserSession) {
        val requesterUsername = "user" + (1..10_000).random()

        if (requesterUsername == session.username) return

        try {
            val response = client.post("http://127.0.0.1:8081/api/v1/friends/deny") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
                setBody(FriendRequestDTO(requesterUsername))
            }

            println("[${session.username}] Denied request from $requesterUsername: ${response.status}")
        } catch (e: Exception) {
            println("[${session.username}] Failed to deny friend request: ${e.message}")
        }
    }
}
