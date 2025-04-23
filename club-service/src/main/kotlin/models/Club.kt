package ru.polyZog.models

import kotlinx.serialization.Serializable

@Serializable
data class Club(
    val id: String,
    val name: String,
    val description: String,
    val ownerId: String,
    val members: MutableSet<String> = mutableSetOf(),
)