package ru.polyZoj.models

import java.time.ZonedDateTime
import kotlinx.serialization.Serializable
import ru.polyZoj.common.ZonedDateTimeSerializer

@Serializable
enum class PrimaryHealthGoal {
    LOSE_WEIGHT,
    GAIN_MUSCLE,
    MAINTAIN_FITNESS
}

@Serializable
data class UserDTO(
        val id: Long, // [serial]
        val email: String, // [varchar(128)]
        val avatar_url: String, // [varchar(512)]
        val password: String, // [varchar(256)](encrypted)
        val is_admin: Boolean, // [boolean]
        val username: String, // [varchar(128)]
        val first_name: String, // [varchar(64)]
        val last_name: String, // [varchar(64)]
        @Serializable(with = ZonedDateTimeSerializer::class)
        val date_of_birth: ZonedDateTime, // [timestamp with time zone]
        val weight: Float, // (kg) [float]
        val height: Short, // (cm) [smallint]
        val primary_health_goal: PrimaryHealthGoal, // enum
        val daily_step_goal: Int, // (steps count) [integer]
        val water_intake_goal: Int, // (millilitres) [integer]
        val calorie_goal: Short, // (calories) [smallint]
        val workouts_count: Short, // [smallint]
        val clubs: List<Int>, // (ids) [integer[]]
        @Serializable(with = ZonedDateTimeSerializer::class)
        val created_at: ZonedDateTime, // [timestamp with timezone]
        @Serializable(with = ZonedDateTimeSerializer::class)
        val updated_at: ZonedDateTime // [timestamp with timezone]
)

@Serializable
data class Friends(
        val id_1: Long, // serial
        val id_2: Long, // serial
)
