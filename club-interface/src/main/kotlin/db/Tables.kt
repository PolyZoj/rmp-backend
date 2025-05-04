package ru.polyZoj.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.dao.id.IntIdTable



/** "clubs" **/
object ClubsTable : IntIdTable("clubs", "club_id") {
    val name = varchar("name", 100)
    val description = varchar("description", 500)
    val ownerId = integer("owner_id")
    val createdAt = timestamp("created_at")
}

/** "club_members" **/
object ClubMembersTable : Table("club_members") {
    val clubId = integer("club_id").references(ClubsTable.id)
    val userId = integer("user_id")
    val joinedAt = timestamp("joined_at")
    override val primaryKey = PrimaryKey(arrayOf(clubId, userId), name = "pk_club_members")
}
