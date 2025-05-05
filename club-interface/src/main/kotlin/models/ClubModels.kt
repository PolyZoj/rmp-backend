package ru.polyZoj.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import common.serializers.InstantSerializer
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Basic Club model
 */

@Serializable
data class Club(
    val id: String,
    val name: String,
    val description: String,
    val ownerId: String,
    val members: MutableSet<String> = mutableSetOf(),
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

