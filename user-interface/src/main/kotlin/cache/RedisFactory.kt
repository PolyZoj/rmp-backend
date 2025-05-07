package ru.polyZoj.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.Json

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    encodeDefaults = true
}

object RedisFactory {
    private val client: RedisClient = RedisClient.create("redis://redis:6379")
    private val connection: StatefulRedisConnection<String, String> =
        client.connect()
    val sync: RedisCommands<String, String> = connection.sync()
}

inline fun <reified T> RedisCommands<String, String>.getJson(key: String): T? {
    return this.get(key)
        ?.let { json.decodeFromString<T>(it) }
}

inline fun <reified T> RedisCommands<String, String>.setJson(
    key: String,
    value: T,
    ttlSeconds: Long? = null
) {
    val str = json.encodeToString(value)
    if (ttlSeconds != null) {
        this.setex(key, ttlSeconds, str)
    } else {
        this.set(key, str)
    }
}