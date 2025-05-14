package org.example.simulator.action

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.example.simulator.model.FriendRequestDTO
import org.example.simulator.model.UserSession

class SendFriendRequestAction(private val client: HttpClient) {
    suspend fun perform(session: UserSession) {
        val friendUsername = "user" + (1..10_000).random()

        if (friendUsername == session.username) return

        try {
            val response = client.post("http://127.0.0.1:8081/api/v1/friends/send") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
                setBody(FriendRequestDTO(friendUsername))
            }

            println("[${session.username}] Friend request to $friendUsername: ${response.status}")
        } catch (e: Exception) {
            println("[${session.username}] Failed to send friend request: ${e.message}")
        }
    }
}
