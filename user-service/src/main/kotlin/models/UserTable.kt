package ru.polyZoj.models

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

object UserTable : IntIdTable("user", "user_id") {
    val firstName: Column<String> = varchar("first_name", length = 50)
    val lastName: Column<String> = varchar("last_name", length = 50)
    val joinDate: Column<LocalDateTime?> = datetime("join_date").nullable()
}
