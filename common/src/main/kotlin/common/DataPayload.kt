package common

import kotlinx.serialization.Serializable

@Serializable
data class DataPayload(
    val message: String,
    val params: List<String>
)