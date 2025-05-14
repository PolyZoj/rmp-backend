package org.example.simulator.model

import kotlinx.serialization.Serializable

@Serializable
data class UserCredentials(val username: String, val password: String) {
    fun toRegisterPayload(): RegisterRequest = RegisterRequest(
        username = username,
        password = password,
        first_name = "Test",
        last_name = "User",
        email = "$username@test.com",
        avatar_url = "https://example.com/$username.png",
        weight = 70,
        height = 175,
        birth_date = "2000-01-01",
        unit_system = "METRIC",
        energy_system = "KCAL",
        health_goal = "AAA",
        daily_step_goal = 10000,
        water_intake_goal = 3000,
        calorie_goal = 2500,
        sleep_goal = 8.0,
        workouts_goal = 3
    )

}
