// common/JsonConfig.kt
package common

import common.serializers.InstantSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import common.serializers.LocalDateSerializer
import java.time.LocalDate
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

object JsonConfig {
    @OptIn(ExperimentalTime::class)
    val instance: Json = Json {
        ignoreUnknownKeys  = true
        encodeDefaults     = true
        serializersModule = SerializersModule {
            contextual(LocalDate::class, LocalDateSerializer)
            contextual(Instant::class, InstantSerializer)
        }
    }
}
