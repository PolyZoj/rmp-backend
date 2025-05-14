package org.example.simulator.model

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    val first_name: String,
    val last_name: String,
    val email: String,
    val avatar_url: String,
    val weight: Int,
    val height: Int,
    val birth_date: String,
    val unit_system: String,
    val energy_system: String,
    val health_goal: String,
    val daily_step_goal: Int,
    val water_intake_goal: Int,
    val calorie_goal: Int,
    val sleep_goal: Double,
    val workouts_goal: Int
)
