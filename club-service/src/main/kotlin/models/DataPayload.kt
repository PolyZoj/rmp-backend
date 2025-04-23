package ru.polyZog.models

data class DataPayload(
    val message: String,
    val clubId: String? = null,
    val parameters: List<String> = emptyList()
)