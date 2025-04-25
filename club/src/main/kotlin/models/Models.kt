package ru.polyZog.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class Club(
    val id: String,
    val name: String,
    val description: String,
    val ownerId: String,
    val members: MutableSet<String> = mutableSetOf(),
)


@Serializable
data class ClubCreateRequest(val name: String, val description: String, val ownerId: String)

@Serializable
data class ClubMemberRequest(val userId: String)

@Serializable
data class ClubCreateResponse(val clubId: String, val name: String)

@Serializable
data class ClubMemberResponse(val message: String,val userId: String, val clubId: String)

@Serializable
data class ClubInfoResponse(
    val club: Club
)


@Serializable
data class DataPayload(
    val message: String,
    val params: List<String>
)

