package ru.polyZoj.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StatsResponse(
    val level: Int,
    val xp: Int,
    val calorie_count: Int,
    val water_count: Int,
    val workouts_count: Int,
    val completed_challenges: Int
)

@Serializable
data class AddStatsRequest(
    val id: String,
    val type: String,
    val add: String
)

@Serializable
data class DataPayload(
    val message: String,
    val params: List<String>
)

@Serializable
data class StatusResponse(
    val status: Boolean
)

@Serializable
data class DailyStatsResponse(
    val date: String,
    val level: Int,
    val xp: Int,
    val calorie_count: Int,
    val water_count: Int,
    val workouts_count: Int,
    val completed_challenges: Int
)