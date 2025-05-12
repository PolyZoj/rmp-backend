package org.example.simulator.action

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.example.simulator.model.UserSession

class AcceptFriendRequestAction(private val client: HttpClient) {
    suspend fun perform(session: UserSession) {
        val requesterUsername = "user" + (1..10_000).random()

        if (requesterUsername == session.username) return

        try {
            val response = client.post("http://127.0.0.1:8081/api/v1/friends/accept") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
                setBody(mapOf("friend_username" to requesterUsername))
            }

            println("[${session.username}] Accepted request from $requesterUsername: ${response.status}")
        } catch (e: Exception) {
            println("[${session.username}] Failed to accept friend request: ${e.message}")
        }
    }
}
