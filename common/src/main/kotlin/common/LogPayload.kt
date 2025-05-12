package common

import kotlinx.serialization.Serializable

@Serializable
data class LogPayload(
    val serviceName: String,
    val level: String,
    val logMessage: String,
    val context: String
) {
    val dataPayload: DataPayload = DataPayload(
        message = logMessage,
        params = listOf(serviceName, level, logMessage, context)
    )
}