package common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.Deserializer

/**
 * Payload for Kafka messages.
 * Use snake_case for keys. Exactly as in @SerialName of data classes.
 * ```
 * val p = DataPayload.build("myMessage") {
 *  param("user_id", 42)
 *  param("workouts_goal", 100)
 *  }
 *  ```
 */
@Serializable
data class DataPayload(
    val message: String,
    val params: Map<String, JsonElement>
) {
    companion object {
        /**
         * Быстрый способ создать “error”-payload:
         * ```
         * val error = DataPayload.error(
         *   status = HttpStatusCode.BadRequest,
         *   description = "что-то пошло не так"
         * )
         * ```
         */
        fun error(
            status: HttpStatusCode,
            description: String
        ): DataPayload =
            DataPayload(
                message = "error",
                params = mapOf(
                    "status" to status.value.toJsonElement(),
                    "description" to description.toJsonElement()
                )
            )

        /**
         * Build a DataPayload with a builder-style DSL:
         * ```
         * val p = DataPayload.build("myMessage") {
         *   param("foo", 42)
         *   param("bar", listOf("a","b"))
         * }
         * ```
         */
        fun build(message: String, block: Builder.() -> Unit): DataPayload {
            val b = Builder(message).apply(block)
            return DataPayload(message, b.map.toMap())
        }
    }

    /**
     * Typed access to a param by key, returns null if not present or wrong type.
     * ```
     * val x: Int? = payload.getParam("foo")
     * ```
     */
    inline fun <reified T> getParam(key: String): T? =
        params[key]?.asType<T>()
}

/** DSL builder for DataPayload.params */
class Builder(val message: String) {
    val map = mutableMapOf<String, JsonElement>()
    inline fun <reified T> param(key: String, value: T) {
        map[key] = value.toJsonElement()
    }
}


class DataPayloadSerializer : Serializer<DataPayload> {
    private val json = Json { encodeDefaults = true }
    override fun serialize(topic: String?, data: DataPayload?): ByteArray? =
        data?.let { json.encodeToString(it).toByteArray(Charsets.UTF_8) }
}

class DataPayloadDeserializer : Deserializer<DataPayload> {
    private val json = Json { ignoreUnknownKeys = true }
    override fun deserialize(topic: String?, data: ByteArray?): DataPayload? =
        data?.let {
            json.decodeFromString<DataPayload>(it.toString(Charsets.UTF_8))
        }
}

inline fun <reified T> T.toJsonElement(): JsonElement =
    Json.encodeToJsonElement(this)

inline fun <reified T> JsonElement.asType(): T =
    Json.decodeFromJsonElement(this)
