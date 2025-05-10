package ru.polyZoj.logic

import common.models.Achievement
import common.models.AchievementStatus
import common.models.AchievementType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class DailyStats(
    val level: Int,
    val xp: Int,
    val steps: Int,
    val calorie: Int,
    val water: Int,
    val workouts: Int,
    val challenges: Int
)

class AchievementCalculator(private val statsClient: StatsClient) {

    fun evaluateAndUpdate(userId: String, achievement: Achievement): Achievement {
        if (achievement.status == AchievementStatus.COMPLETED) return achievement

        val totals = accumulate(userId, achievement.startDate, achievement.endDate)

        val achieved = when (achievement.type) {
            AchievementType.LEVEL          -> totals.sumOf { it.level     }.toDouble() >= achievement.goal
            AchievementType.XP             -> totals.sumOf { it.xp        }.toDouble() >= achievement.goal
            AchievementType.STEPS_COUNT    -> totals.sumOf { it.steps     }.toDouble() >= achievement.goal
            AchievementType.CALORIE_COUNT  -> totals.sumOf { it.calorie   }.toDouble() >= achievement.goal
            AchievementType.WATER_COUNT    -> totals.sumOf { it.water     }.toDouble() >= achievement.goal
            AchievementType.WORKOUTS_COUNT -> totals.sumOf { it.workouts  }.toDouble() >= achievement.goal
        }

        if (achieved) {
            statsClient.incrementCompletedChallenges(userId)
            return achievement.copy(status = AchievementStatus.COMPLETED)
        }
        return achievement
    }

    private fun accumulate(userId: String, start: LocalDate, end: LocalDate): List<DailyStats> {
        val days = ChronoUnit.DAYS.between(start, end).toInt()
        // TODO needs cache
        return (0..days).map { offset ->
            val payload = statsClient.readDaily(userId, start.plusDays(offset.toLong()).toString())
            DailyStats(
                level      = payload.params[2].toInt(),
                xp         = payload.params[3].toInt(),
                steps      = payload.params[4].toInt(),
                calorie    = payload.params[5].toInt(),
                water      = payload.params[6].toInt(),
                workouts   = payload.params[7].toInt(),
                challenges = payload.params[8].toInt()
            )
        }
    }
}