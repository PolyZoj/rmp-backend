package org.example.simulator.model

data class UserCredentials(val username: String, val password: String) {
    fun toRegisterPayload(): Map<String, Any> = mapOf(
        "username" to username,
        "password" to password,
        "first_name" to "Test",
        "last_name" to "User",
        "email" to "$username@test.com",
        "avatar_url" to "https://example.com/$username.png",
        "weight" to 70,
        "height" to 175,
        "birth_date" to "2000-01-01",
        "unit_system" to "METRIC",
        "energy_system" to "KCAL",
        "health_goal" to "AAA",
        "daily_step_goal" to 10000,
        "water_intake_goal" to 3000,
        "calorie_goal" to 2500,
        "sleep_goal" to 8.0,
        "workouts_goal" to 3
    )
}
