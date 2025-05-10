package ru.polyZoj.repositories

import common.models.Achievement
import common.models.AchievementStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import ru.polyZoj.db.AchievementsTable
import ru.polyZoj.db.DatabaseFactory
import ru.polyZoj.logger
import java.time.LocalDate

class ChallengesRepository {
    private val log = logger<ChallengesRepository>()

    private fun ResultRow.rowToAchievement(): Achievement {
        return Achievement(
            id = this[AchievementsTable.id].value.toString(),
            userId = this[AchievementsTable.userId].toString(),
            icon = this[AchievementsTable.icon],
            description = this[AchievementsTable.description],
            title = this[AchievementsTable.title],
            status = this[AchievementsTable.status],
            goal = this[AchievementsTable.goal],
            type = this[AchievementsTable.type],
            startDate = this[AchievementsTable.startDate],
            endDate = this[AchievementsTable.endDate]
        )
    }

    /** Get all achievements for user by userId **/
    suspend fun getAllAchievements(userId: Int): List<Achievement> {
        log.debug("Entering getAllAchievements with user id: $userId")

        val achievements: List<Achievement> = DatabaseFactory.read {
            AchievementsTable.selectAll()
                .where { AchievementsTable.userId eq userId }
                .mapNotNull { row ->
                    try {
                        row.rowToAchievement()
                    } catch (e: Exception) {
                        log.error(
                            "Failed to map ResultRow → Achievement for userId={}",
                            userId,
                            e
                        )
                        null
                    }
                }
        }

        return achievements
    }

    /** Get all achievements for user by userId where given date is within [start_date, end_date] **/
    suspend fun getAchievementsByDate(userId: Int, date: LocalDate): List<Achievement> {
        log.debug("Entering getAchievementsByDate with user id: {}, date: {}", userId, date)

        val achievements: List<Achievement> = DatabaseFactory.read {
            AchievementsTable.selectAll()
                .where {
                    (AchievementsTable.userId eq userId) and
                    (AchievementsTable.startDate lessEq date) and
                    (AchievementsTable.endDate greaterEq date)
                }
                .mapNotNull { row ->
                    try {
                        row.rowToAchievement()
                    } catch (e: Exception) {
                        log.error(
                            "Failed to map ResultRow → Achievement for userId={}",
                            userId,
                            e
                        )
                        null
                    }
                }
        }

        return achievements
    }

    /** Set achievements as completed **/
    suspend fun setAchievementsAsCompleted(achievementId: String): Int {
        log.debug("Entering setAchievementsAsCompleted with achievementId: {}", achievementId)
        val rows = DatabaseFactory.write {
            AchievementsTable.update({
                (AchievementsTable.id eq achievementId.toInt())
            }) {
                it[status] = AchievementStatus.COMPLETED
            }
        }

        return rows
    }

    /** Add achievement **/
    suspend fun addAchievement(achievement: Achievement) {
        log.debug("Entering addAchievement with achievement: {}", achievement)

        DatabaseFactory.write {
            AchievementsTable.insert {
                it[AchievementsTable.userId] = achievement.userId.toInt()
                it[AchievementsTable.icon] = achievement.icon
                it[AchievementsTable.description] = achievement.description
                it[AchievementsTable.title] = achievement.title
                it[AchievementsTable.status] = achievement.status
                it[AchievementsTable.goal] = achievement.goal
                it[AchievementsTable.type] = achievement.type
                it[AchievementsTable.startDate] = achievement.startDate
                it[AchievementsTable.endDate] = achievement.endDate
            }
        }
    }


    /** Get all week achievements in progress for user
     *  Checks for (date - start_date) <= 7 days and
     *  end_date >= date
     **/
    suspend fun getWeeklyAchievements(userId: Int, date: LocalDate): List<Achievement> {
        log.debug("Entering getWeekAchievements with user id: {}, date: {}", userId, date)

        // compute the earliest start date we allow (no more than 7 days before `date`)
        val weekStart = date.minusDays(7)

        val achievements: List<Achievement> = DatabaseFactory.read {
            AchievementsTable
                .selectAll()
                .where {
                    (AchievementsTable.userId eq userId) and
                            (AchievementsTable.startDate greaterEq weekStart) and
                            (AchievementsTable.endDate greaterEq date)
                }
                .mapNotNull { row ->
                    try {
                        row.rowToAchievement()
                    } catch (e: Exception) {
                        log.error(
                            "Failed to map ResultRow → Achievement for userId={} in getWeeklyAchievements",
                            userId,
                            e
                        )
                        null
                    }
                }
        }

        return achievements
    }


    /** Get all day achievements in progress for user
     *  Checks for start_date = date = end_date
     */
    suspend fun getDailyAchievements(userId: Int, date: LocalDate): List<Achievement> {
        log.debug("Entering getDailyAchievements with user id: {}, date: {}", userId, date)

        val achievements: List<Achievement> = DatabaseFactory.read {
            AchievementsTable
                .selectAll()
                .where {
                    (AchievementsTable.userId eq userId) and
                            (AchievementsTable.startDate eq date) and
                            (AchievementsTable.endDate eq date)
                }
                .mapNotNull { row ->
                    try {
                        row.rowToAchievement()
                    } catch (e: Exception) {
                        log.error(
                            "Failed to map ResultRow → Achievement for userId={} in getDailyAchievements",
                            userId,
                            e
                        )
                        null
                    }
                }
        }

        return achievements
    }

}

private fun Achievement.matchesUser(userId: Int) = this.userId.toInt() == userId

/** Get all achievements for user by userId where given date is within [start_date, end_date] **/
fun List<Achievement>.getAchievementsByDate(userId: Int, date: LocalDate): List<Achievement> =
    this
        .asSequence()
        .filter { it.matchesUser(userId) }
        .filter { !it.startDate.isAfter(date) && !it.endDate.isBefore(date) }
        .toList()

/** Get all week achievements in progress for user:
 *  (date – start_date) ≤ 7 days and end_date ≥ date
 **/
fun List<Achievement>.getWeeklyAchievements(userId: Int, date: LocalDate): List<Achievement> {
    val weekStart = date.minusDays(7)
    return this
        .asSequence()
        .filter { it.matchesUser(userId) }
        .filter { !it.startDate.isBefore(weekStart) && !it.endDate.isBefore(date) }
        .toList()
}

/** Get all day achievements in progress for user:
 *  start_date = date = end_date
 **/
fun List<Achievement>.getDailyAchievements(userId: Int, date: LocalDate): List<Achievement> =
    this
        .asSequence()
        .filter { it.matchesUser(userId) }
        .filter { it.startDate == date && it.endDate == date }
        .toList()

