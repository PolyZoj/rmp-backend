package org.example.simulator

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.example.simulator.action.*
import org.example.simulator.model.UserSession
import org.example.simulator.util.HttpClientFactory
import org.example.simulator.util.DataGenerator
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AppSimulator {
    private val client = HttpClientFactory.create()
    private val users = mutableListOf<UserSession>()
    inline fun <reified T> logger(): Logger = LoggerFactory.getLogger(T::class.java)
    val log = logger<AppSimulator>()

    suspend fun runRegistration(userCount: Int) = coroutineScope {
        (1..userCount).map { i ->
            launch {
                try {
                    val credentials = DataGenerator.generateCredentials(i)
                    val userId = RegisterAction(client).perform(credentials)

                    val userId2 = GetUserRequest(client).performByUsername(credentials)
                    if (userId == userId2) {
                        log.info("Successfully registered user $userId2")
                    } else {
                        log.error("Error when registering user $userId2")
                    }

                    val token = LoginAction(client).perform(credentials)
                    if (token != null) {
                        users.add(UserSession(credentials.username, token))
                    }
                } catch (e: Exception) {
                    log.error("Error registering user", e)
                }

            }
        }.joinAll()
    }

    suspend fun runActions() = coroutineScope {
        repeat(10000) {
            launch {
                try {
                    val user = users.random()
                    when ((1..4).random()) {
                        1 -> SendFriendRequestAction(client).perform(user)
                        2 -> AcceptFriendRequestAction(client).perform(user)
                        3 -> DenyFriendRequestAction(client).perform(user)
                        4 -> {}
                    }
                    log.info("Successfully completed action for $user")
                } catch (e: Exception) {
                    log.error("Error while action", e)
                }
            }
        }
    }
}
