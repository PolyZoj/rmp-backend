package ru.polyZog.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
data class ClubCreateRequest(val name: String, val description: String, val ownerId: String)

@Serializable
data class ClubMemberRequest(val userId: String)

@Serializable
data class Club(
    val id: String,
    val name: String,
    val description: String,
    val ownerId: String,
    val members: MutableSet<String> = mutableSetOf(),
)

@Serializable
data class TokenResponse(val id: String, val token: String)

@Serializable
data class DataPayload(
    val message: String,
    val clubId: String? = null,
    val parameters: List<String> = emptyList()
)
