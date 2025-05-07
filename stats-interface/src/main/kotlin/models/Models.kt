package ru.polyZoj.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DataPayload(
    val message: String,
    val params: List<String>
)