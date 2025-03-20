package ru.polyZoj.models

import kotlinx.serialization.Serializable
import java.time.ZonedDateTime

@Serializable
data class Challenge(
    val challengeId: Long,
    val challengeType: String, // daily, weekly, seasonal, team, user_created
    val title: String,
    val description: String,
    val metricType: String,    // steps, sleep, питание, гидратация и т.д.
    val targetValue: Int,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val startTime: ZonedDateTime,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val endTime: ZonedDateTime,
    val createdBy: String,     // "system" или id пользователя
    val status: String         // active, completed, cancelled и т.д.
)

@Serializable
data class UserChallengeProgress(
    val challengeId: Long,
    val userId: Long,
    val progressValue: Int,
    @Serializable(with = ZonedDateTimeSerializer::class)
    val completedAt: ZonedDateTime? = null,
    val rewardGranted: Boolean = false
)

@Serializable
data class ChallengeReward(
    val rewardId: Long,
    val challengeId: Long,
    val rewardType: String,    // XP, медаль и т.п.
    val rewardValue: Int
)


