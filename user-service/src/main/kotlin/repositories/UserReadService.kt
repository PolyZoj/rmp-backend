package ru.polyZoj.repositories

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.dao.id.EntityID
import ru.polyZoj.db.ReplicaTransaction
import ru.polyZoj.models.User
import ru.polyZoj.models.UserTable

class UserReadService {
    suspend fun getAllUserTable(): List<User> = ReplicaTransaction.dbQuery {
        UserTable.selectAll().map { it.toUser() }
    }

    suspend fun getUserById(id: Int): User? = ReplicaTransaction.dbQuery {
        UserTable
            .select { UserTable.id eq EntityID(id, UserTable) }
            .singleOrNull()
            ?.toUser()
    }

    private fun ResultRow.toUser(): User = User(
        userId = this[UserTable.id].value,
        firstName = this[UserTable.firstName],
        lastName = this[UserTable.lastName],
        joinDate = this[UserTable.joinDate]
    )
}
