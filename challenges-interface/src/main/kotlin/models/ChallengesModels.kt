package ru.polyZoj.models

import common.serializers.LocalDateSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
enum class AchievementStatus {
    @SerialName("IN_PROGRESS")       IN_PROGRESS,
    @SerialName("COMPLETED")         COMPLETED
}

@Serializable
enum class AchievementType {
    @SerialName("level")             LEVEL,
    @SerialName("xp")                XP,
    @SerialName("calorie_count")     CALORIE_COUNT,
    @SerialName("water_count")       WATER_COUNT,
    @SerialName("workouts_count")    WORKOUTS_COUNT
}

/** Table achievements **/
@Serializable
data class Achievement(
    val icon: String,
    val description: String,
    val title: String,
    val status: AchievementStatus,
    val goal: Double,
    val type: AchievementType,
    @SerialName("start_date") @Serializable(with = LocalDateSerializer::class)   val startDate: LocalDate,
    @SerialName("end_date")   @Serializable(with = LocalDateSerializer::class)   val endDate: LocalDate
)
