package ru.polyZoj.repositories

import common.Level
import common.LogSender
import common.models.Achievement
import common.models.AchievementStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import ru.polyZoj.cache.RedisFactory
import ru.polyZoj.cache.getJson
import ru.polyZoj.cache.setJson
import ru.polyZoj.db.AchievementsTable
import ru.polyZoj.db.DatabaseFactory
import java.time.LocalDate

class ChallengesRepository(private val logger: LogSender) {

    /**
     * Cache keys:
     * - achievementsOf:userId:<userId>
     */
    private val redis = RedisFactory.sync

    fun logInfo (ctx: String, msg: String) = logger.log("challenges-interface", Level.INFO,  msg, "ChallengesRepository: $ctx")
    fun logError(ctx: String, msg: String) = logger.log("challenges-interface", Level.ERROR, msg, "ChallengesRepository: $ctx")
    fun logDebug(ctx: String, msg: String) = logger.log("challenges-interface", Level.DEBUG, msg, "ChallengesRepository: $ctx")
    fun logWarn (ctx: String, msg: String) = logger.log("challenges-interface", Level.WARN,  msg, "ChallengesRepository: $ctx")

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
        val ctx = "getAllAchievements"
        logDebug(ctx, "Entering $ctx with userId=$userId")

        val cachedAchs = redis.getJson<List<Achievement>>("achievementsOf:userId:$userId")
        if (cachedAchs != null) {
            logInfo(ctx, "HIT: Found ${cachedAchs.size} achievements for userId=$userId in cache")
            return cachedAchs
        } else {
            logWarn(ctx, "MISS: Achievements for userId=$userId not found in cache")
        }

        val achievements: List<Achievement> = DatabaseFactory.read {
            AchievementsTable.selectAll()
                .where { AchievementsTable.userId eq userId }
                .mapNotNull { row ->
                    try {
                        row.rowToAchievement()
                    } catch (e: Exception) {
                        logError(ctx, "Failed to map ResultRow→Achievement for userId=$userId: ${e.message}")
                        null
                    }
                }
        }

        redis.setJson("achievementsOf:userId:$userId", achievements, 600)

        return achievements
    }

    /** Get all achievements for user by userId where given date is within [start_date, end_date] **/
    suspend fun getAchievementsByDate(userId: Int, date: LocalDate): List<Achievement> {
        val ctx = "getAchievementsByDate"
        logDebug(ctx, "Entering $ctx with userId=$userId, date=$date")

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
                        logError(ctx, "Failed to map ResultRow→Achievement for userId=$userId: ${e.message}")
                        null
                    }
                }
        }
        if (achievements.isNotEmpty()) {
            logInfo(ctx, "Fetched ${achievements.size} achievements active on $date for userId=$userId")
        } else {
            logError(ctx, "No achievements active on $date for userId=$userId")
        }
        return achievements
    }

    /** Set achievements as completed **/
    suspend fun setAchievementsAsCompleted(achievement: Achievement): Int {
        val ctx = "setAchievementsAsCompleted"
        logDebug(ctx, "Entering $ctx with achievementId=${achievement.id}")
        val rows = DatabaseFactory.write {
            AchievementsTable.update({
                (AchievementsTable.id eq achievement.id?.toInt())
            }) {
                it[status] = AchievementStatus.COMPLETED
            }
        }
        if (rows > 0) {
            logInfo(ctx, "Marked achievementId=${achievement.id} as COMPLETED (rows=$rows)")
        } else {
            logError(ctx, "Failed to mark achievementId=${achievement.id} as COMPLETED – no rows updated")
        }

        logDebug(ctx, "Deleting cache for achievementsOf:userId:${achievement.userId}")
        redis.del("achievementsOf:userId:${achievement.userId}")
        return rows
    }

    /** Add achievement **/
    suspend fun addAchievement(achievement: Achievement): Achievement? {
        val ctx = "addAchievement"
        logDebug(ctx, "Entering $ctx with achievement=${achievement.title}")

        val inserted: ResultRow? = try {
            DatabaseFactory.write {
                AchievementsTable.insert {
                    it[AchievementsTable.userId]      = achievement.userId.toInt()
                    it[AchievementsTable.icon]        = achievement.icon
                    it[AchievementsTable.description] = achievement.description
                    it[AchievementsTable.title]       = achievement.title
                    it[AchievementsTable.status]      = achievement.status
                    it[AchievementsTable.goal]        = achievement.goal
                    it[AchievementsTable.type]        = achievement.type
                    it[AchievementsTable.startDate]   = achievement.startDate
                    it[AchievementsTable.endDate]     = achievement.endDate
                }.resultedValues?.firstOrNull()
            }
        } catch (e: Exception) {
            logError(ctx, "Error inserting achievement for userId=${achievement.userId}: ${e.message}")
            null
        }

        if (inserted != null) redis.del("achievementsOf:userId:${achievement.userId}")
        return inserted?.rowToAchievement()
    }


    /** Get all week achievements in progress for user
     *  Checks for (date - start_date) <= 7 days and
     *  end_date >= date
     **/
    suspend fun getWeeklyAchievements(userId: Int, date: LocalDate): List<Achievement> {
        val ctx = "getWeeklyAchievements"
        logDebug(ctx, "Entering $ctx with userId=$userId, date=$date")

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
                        logError(ctx, "Failed to map ResultRow→Achievement for userId=$userId: ${e.message}")
                        null
                    }
                }
        }

        if (achievements.isNotEmpty()) {
            logInfo(ctx, "Fetched ${achievements.size} weekly achievements in progress for userId=$userId")
        } else {
            logError(ctx, "No weekly achievements in progress for userId=$userId")
        }
        return achievements
    }


    /** Get all day achievements in progress for user
     *  Checks for start_date = date = end_date
     */
    suspend fun getDailyAchievements(userId: Int, date: LocalDate): List<Achievement> {
        val ctx = "getDailyAchievements"
        logDebug(ctx, "Entering $ctx with userId=$userId, date=$date")

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
                        logError(ctx, "Failed to map ResultRow→Achievement for userId=$userId: ${e.message}")
                        null
                    }
                }
        }

        if (achievements.isNotEmpty()) {
            logInfo(ctx, "Fetched ${achievements.size} daily achievements for userId=$userId on $date")
        } else {
            logError(ctx, "No daily achievements for userId=$userId on $date")
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

