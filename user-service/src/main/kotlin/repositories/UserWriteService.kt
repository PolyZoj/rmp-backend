package ru.polyZoj.repositories

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.dao.id.EntityID
import ru.polyZoj.db.MasterTransaction
import ru.polyZoj.models.UserTable
import java.time.LocalDateTime

class UserWriteService {

    suspend fun createUser(
        firstName: String,
        lastName: String,
        joinDate: LocalDateTime?
    ): Int = MasterTransaction.dbQuery {
        UserTable.insert { table ->
            table[UserTable.firstName] = firstName
            table[UserTable.lastName] = lastName
            table[UserTable.joinDate] = joinDate
        } get UserTable.id
    }.value

    suspend fun updateUserName(
        userId: Int,
        firstName: String,
        lastName: String
    ): Boolean = MasterTransaction.dbQuery {
        val rowsUpdated = UserTable.update({ UserTable.id eq EntityID(userId, UserTable) }) { table ->
            table[UserTable.firstName] = firstName
            table[UserTable.lastName] = lastName
        }
        rowsUpdated > 0
    }
}
