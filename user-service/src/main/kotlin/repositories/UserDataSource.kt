package ru.polyZoj.repositories

import ru.polyZoj.models.User
import java.util.*

object UserDataSource {
    private val users = mutableListOf<User>()

    fun addUser(user: User) {
        if (users.any { it.username == user.username }) {
            throw IllegalArgumentException("User already exists")
        }
        users.add(user)
    }

    fun findUserByUsername(username: String): User? {
        return users.find { it.username == username }
    }

    fun generateUserId() = UUID.randomUUID().toString()
}
