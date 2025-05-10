package ru.polyZoj.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import common.models.AchievementStatus
import common.models.AchievementType

object AchievementsTable : IntIdTable("achievements") {
    val userId      = integer("user_id")
    val icon        = varchar("icon", length = 255)
    val description = text("description")
    val title       = varchar("title", length = 255)
    val status      = enumerationByName<AchievementStatus>(name = "status", length = 50)
    val goal        = double("goal")
    val type        = enumerationByName<AchievementType>(name = "type", length = 50)
    val startDate   = date("start_date")
    val endDate     = date("end_date")
}

