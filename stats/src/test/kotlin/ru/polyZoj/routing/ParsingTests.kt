package ru.polyZoj.routing

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.polyZoj.models.DataPayload
import ru.polyZoj.models.DailyStatsResponse
import ru.polyZoj.models.StatsResponse

class ParsingTests : StringSpec({
    "parseStatsResponse with valid data returns correct StatsResponse" {
        val payload = DataPayload(
            message = "success",
            params = listOf("user123", "5", "500", "10000", "3000", "10", "20", "5")
        )
        val result = parseStatsResponse(payload)
        result shouldBe StatsResponse(
            level = 5,
            xp = 500,
            steps_count = 10000,
            calorie_count = 3000,
            water_count = 10,
            workouts_count = 20,
            completed_challenges = 5
        )
    }

    "parseStatsResponse with insufficient params returns defaults" {
        val payload = DataPayload("success", listOf("user123"))
        val result = parseStatsResponse(payload)
        result shouldBe StatsResponse(
            level = 0,
            xp = 0,
            steps_count = 0,
            calorie_count = 0,
            water_count = 0,
            workouts_count = 0,
            completed_challenges = 0
        )
    }

    "parseDailyStatsResponse with valid data returns correct response" {
        val payload = DataPayload(
            message = "success",
            params = listOf("user123", "2023-01-01", "3", "300", "5000", "2000", "8", "15", "2")
        )
        val result = parseDailyStatsResponse(payload)
        result shouldBe DailyStatsResponse(
            date = "2023-01-01",
            level = 3,
            xp = 300,
            steps_count = 5000,
            calorie_count = 2000,
            water_count = 8,
            workouts_count = 15,
            completed_challenges = 2
        )
    }
})