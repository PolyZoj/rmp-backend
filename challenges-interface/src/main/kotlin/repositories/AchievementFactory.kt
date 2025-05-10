package ru.polyZoj.repositories

import common.models.Achievement
import common.models.AchievementStatus
import common.models.AchievementType
import java.time.LocalDate
import kotlin.random.Random

/**
 * Builds an [Achievement] whose missing fields are filled with random defaults.
 *
 * Always returns an object in [AchievementStatus.IN_PROGRESS].
 * If you supply `startDate` and `endDate` their distance must be `0` or `6` days
 * (i.e. 1-day or 7-day). Otherwise, they are ignored and the helper decides.
 */
object AchievementFactory {

    fun createAchievementHelper(
        userId: String,
        icon: String?              = null,
        description: String?       = null,
        title: String?             = null,
        goal: Double?              = null,
        type: AchievementType?     = null,
        startDate: LocalDate?      = null,
        endDate: LocalDate?        = null,
        rng: Random = Random.Default
    ): Achievement {

        val (start, end, days) = pickDates(startDate, endDate, rng)

        val finalType = type ?: AchievementType.entries.toTypedArray().random(rng)

        val finalGoal = goal ?: randomGoalFor(finalType, days, rng)

        val finalIcon  = icon  ?: defaultIcons[finalType]!!.random(rng)
        val finalTitle = title ?: titles[finalType]!!.random(rng)

        val finalDescription = description ?: buildString {
            append("Hit ")
            append(trimTrailingZero(finalGoal))
            append(' ')
            append(
                when (finalType) {
                    AchievementType.LEVEL          -> "level(s)"
                    AchievementType.XP             -> "XP"
                    AchievementType.STEPS_COUNT    -> "steps"
                    AchievementType.CALORIE_COUNT  -> "kcal"
                    AchievementType.WATER_COUNT    -> "litres of water"
                    AchievementType.WORKOUTS_COUNT -> "workout(s)"
                }
            )
            append(" in $days day${if (days > 1) "s" else ""}.")
        }

        return Achievement(
            userId        = userId,
            icon          = finalIcon,
            description   = finalDescription,
            title         = finalTitle,
            status        = AchievementStatus.IN_PROGRESS, // always
            goal          = finalGoal,
            type          = finalType,
            startDate     = start,
            endDate       = end
        )
    }

    private fun pickDates(
        sd: LocalDate?,
        ed: LocalDate?,
        rng: Random
    ): Triple<LocalDate, LocalDate, Int> {

        val today = LocalDate.now()

        return when {
            sd != null && ed != null -> {
                val diff = java.time.temporal.ChronoUnit.DAYS.between(sd, ed).toInt()
                require(diff == 0 || diff == 6) {
                    "Only 1-day or 7-day spans are allowed; got ${diff + 1} days."
                }
                Triple(sd, ed, diff + 1)
            }
            else -> {
                val days = if (rng.nextBoolean()) 1 else 7
                val start = sd ?: today
                val end   = when (days) {
                    1 -> start
                    7 -> start.plusDays(6)
                    else -> error("Unexpected span")
                }
                Triple(start, end, days)
            }
        }
    }

    private fun randomGoalFor(
        type: AchievementType,
        days: Int,
        rng: Random
    ): Double {
        val range = goalRanges[type]!!
        val picked = if (days == 1) range.first.random(rng) else range.second.random(rng)
        return picked.toDouble()
    }

    // Strip ".0" for a cleaner description when goal is an integer
    private fun trimTrailingZero(d: Double) =
        if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}

private val goalRanges: Map<AchievementType, Pair<IntRange, IntRange>> = mapOf(
    AchievementType.LEVEL           to (1..1      to 1..3),
    AchievementType.XP              to (100..1_000  to 500..5_000),
    AchievementType.STEPS_COUNT     to (3_000..20_000  to 20_000..120_000),
    AchievementType.CALORIE_COUNT   to (1_000..4_000   to 7_000..28_000),
    AchievementType.WATER_COUNT     to (1..3           to 7..21),
    AchievementType.WORKOUTS_COUNT  to (1..2           to 3..14)
)

private val titles: Map<AchievementType, List<String>> = mapOf(
    AchievementType.LEVEL           to listOf("Level Up!", "Next Tier", "Rank Raiser"),
    AchievementType.XP              to listOf("XP Junkie", "Experience Explosion", "XP Express"),
    AchievementType.STEPS_COUNT     to listOf("Step Master", "Walkathon", "Stride Star"),
    AchievementType.CALORIE_COUNT   to listOf("Calorie Crusher", "Burn Blitz", "Heat Wave"),
    AchievementType.WATER_COUNT     to listOf("Hydration Hero", "Aqua Ace", "Water Warrior"),
    AchievementType.WORKOUTS_COUNT  to listOf("Workout Wizard", "Gym Gladiator", "Training Titan")
)

private val defaultIcons: Map<AchievementType, List<String>> = mapOf(
    AchievementType.LEVEL           to listOf("icon_level_1", "icon_level_2"),
    AchievementType.XP              to listOf("icon_xp_1", "icon_xp_2"),
    AchievementType.STEPS_COUNT     to listOf("icon_steps_1", "icon_steps_2"),
    AchievementType.CALORIE_COUNT   to listOf("icon_calories_1", "icon_calories_2"),
    AchievementType.WATER_COUNT     to listOf("icon_water_1", "icon_water_2"),
    AchievementType.WORKOUTS_COUNT  to listOf("icon_workout_1", "icon_workout_2")
)