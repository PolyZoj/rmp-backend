package org.example.simulator

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.simulator.action.*
import org.example.simulator.model.UserCredentials
import org.example.simulator.model.UserSession
import org.example.simulator.util.HttpClientFactory
import org.example.simulator.util.DataGenerator

class AppSimulator {
    private val client = HttpClientFactory.create()
    private val users = mutableListOf<UserSession>()

    suspend fun runSimulation(count: Int) = coroutineScope {
        val registrationJobs = (51..60).map { i ->
            async {
                val credentials = DataGenerator.generateCredentials(i)
                val userId = RegisterAction(client).perform(credentials)
                println("Registered user ${credentials.username} at ${System.currentTimeMillis()}")
                credentials to userId
            }
        }

        val credsAndIds: List<Pair<UserCredentials, String?>> = registrationJobs.awaitAll()

        repeat(count) {
            launch {
                val (credentials, userId) = credsAndIds.random()

//                val userId2 = GetUserRequest(client).performByUsername(credentials)
//                if (userId == userId2) {
//                    println("Successfully simulated user $userId2")
//                } else {
//                    println("Error when simulated user $userId2")
//                }

                val token = LoginAction(client).perform(credentials)
                if (token != null) {
                    users += UserSession(credentials.username, token)
                }
            }
        }

//        repeat(10_000) {
//            launch {
//                val user = users.random()
//                when ((1..4).random()) {
//                    1 -> SendFriendRequestAction(client).perform(user)
//                    2 -> AcceptFriendRequestAction(client).perform(user)
//                    3 -> DenyFriendRequestAction(client).perform(user)
//                    4 -> {}
//                }
//            }
//        }
    }
}
