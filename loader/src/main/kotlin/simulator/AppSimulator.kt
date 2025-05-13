package org.example.simulator

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.example.simulator.action.*
import org.example.simulator.model.UserSession
import org.example.simulator.util.HttpClientFactory
import org.example.simulator.util.DataGenerator

class AppSimulator {
    private val client = HttpClientFactory.create()
    private val users = mutableListOf<UserSession>()

    suspend fun runSimulation(userCount: Int) = coroutineScope {
        (1..userCount).map { i ->
            launch {
                val credentials = DataGenerator.generateCredentials(i)
                val userId = RegisterAction(client).perform(credentials)

                val userId2 = GetUserRequest(client).performByUsername(credentials)
                if (userId == userId2) {
                    println("Successfully simulated user $userId2")
                } else {
                    println("Error when simulated user $userId2")
                }

                val token = LoginAction(client).perform(credentials)
                if (token != null) {
                    users += UserSession(credentials.username, token)
                }
            }
        }.joinAll()

        repeat(10000) {
            launch {
                val user = users.random()
                when ((1..4).random()) {
                    1 -> SendFriendRequestAction(client).perform(user)
                    2 -> AcceptFriendRequestAction(client).perform(user)
                    3 -> DenyFriendRequestAction(client).perform(user)
                    4 -> {} // другие действия
                }
            }
        }
    }
}
