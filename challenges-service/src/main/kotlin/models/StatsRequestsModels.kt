package ru.polyZoj.models

import kotlinx.serialization.Serializable

@Serializable
data class StatsRequest(
    val userId: Int,
    val metricType: String,
    val correlationId: String = ""  // will be overwritten at runtime
)

@Serializable
data class StatsResponse(
    val userId: Int,
    val metricType: String,
    val totalValue: Int,
    val correlationId: String = ""
)
