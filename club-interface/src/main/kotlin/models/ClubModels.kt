package ru.polyZoj.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import common.serializers.InstantSerializer
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Basic Club model (DB table "clubs")
 */
@Serializable
data class Club @OptIn(ExperimentalTime::class) constructor(
    @SerialName("club_id") val clubId: Int,
    val name: String,
    val description: String,
    @SerialName("owner_id") val ownerId: Int,
    @SerialName("created_at")
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant
)

/**
 * Club Member model (DB table "club_members")
 */
@Serializable
data class ClubMember @OptIn(ExperimentalTime::class) constructor(
    @SerialName("club_id") val clubId: Int,
    @SerialName("user_id") val userId: Int,
    @SerialName("joined_at")
    @Serializable(with = InstantSerializer::class)
    val joinedAt: Instant,
)

/**
 * Club Update Request DTO
 */
@Serializable
data class ClubUpdateRequest(
    val name: String? = null,
    val description: String? = null
)

/**
 * Club Member Request DTO
 */
@Serializable
data class ClubMemberAddRequest(
    @SerialName("user_id") val userId: Int,
)

/**
 * Club Response DTO with member information
 */
@Serializable
data class ClubResponse @OptIn(ExperimentalTime::class) constructor(
    @SerialName("club_id") val clubId: Int,
    val name: String,
    val description: String,
    @SerialName("owner_id") val ownerId: Int,
    @SerialName("created_at")
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    val members: List<ClubMember> = emptyList()
)

/**
 * Club List Response DTO
 */
@Serializable
data class ClubListResponse(
    val clubs: List<Club>,
    val total: Int,
    val offset: Int,
    val limit: Int
)
