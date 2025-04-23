package ru.polyZoj.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Создаем контекстный сериализатор для Any
val AppSerializersModule = SerializersModule {
    contextual(AnyToStringSerializer)
}

// Сериализатор для преобразования различных типов в строки в JSON
object AnyToStringSerializer : KSerializer<Any> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AnyToString", PrimitiveKind.STRING)
    
    override fun serialize(encoder: Encoder, value: Any) {
        encoder.encodeString(value.toString())
    }
    
    override fun deserialize(decoder: Decoder): Any {
        return decoder.decodeString()
    }
}

/**
 * Базовая модель пользователя
 */
@Serializable
data class User(
    @SerialName("user_id") val userId: Int,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val email: String,
    @SerialName("created_at") val createdAt: String? = null
)


@Serializable
data class UserDTO(
    val id: Int, // [serial]
    val first_name: String, // [varchar(64)]
    val last_name: String, // [varchar(64)]

    val email: String, // [varchar(128)]
    val avatar_url: String, // [varchar(512)]
    val password: String, // [varchar(256)](encrypted)
    val is_admin: Boolean, // [boolean]
    val username: String, // [varchar(128)]

    @Serializable(with = ZonedDateTimeSerializer::class)
    val date_of_birth: ZonedDateTime, // [timestamp with time zone]
    val weight: Float, // (kg) [float]
    val height: Short, // (cm) [smallint]
    val primary_health_goal: PrimaryHealthGoal, // enum
    val daily_step_goal: Int, // (steps count) [integer]
    val water_intake_goal: Int, // (millilitres) [integer]
    val calorie_goal: Short, // (calories) [smallint]
    val workouts_count: Short, // [smallint]
    val clubs: List<Int>, // (ids) [integer[]]
    @Serializable(with = ZonedDateTimeSerializer::class)
    val created_at: ZonedDateTime, // [timestamp with timezone]
    @Serializable(with = ZonedDateTimeSerializer::class)
    val updated_at: ZonedDateTime // [timestamp with timezone]
)

/**
 * Модель для регистрации нового пользователя
 */
@Serializable
data class UserRegistration(
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val email: String,
    val password: String,
    val weight: Int,
    val height: Int,
    @SerialName("birth_date") val birthDate: String,
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("health_goal_id") val healthGoalId: Int,
    @SerialName("daily_steps") val dailySteps: Int,
    @SerialName("water_intake") val waterIntake: Int,
    @SerialName("energy_intake") val energyIntake: Int,
    @SerialName("sleep_hours") val sleepHours: Int
)

/**
 * Физические параметры пользователя
 */
@Serializable
data class UserParametersDTO(
    @SerialName("user_id") val userId: Int,
    val weight: Int,
    val height: Int,
    @SerialName("birth_date") val birthDate: String,
    @SerialName("unit_system_id") val unitSystemId: Int
) {
    // Вычисляемое свойство для ИМТ 
    fun getBMI(): Double = when (height) {
        0 -> 0.0 // Защита от деления на ноль
        else -> weight * 10000.0 / (height * height)
    }
}

/**
 * Предпочтения пользователя
 */
@Serializable
data class UserPreferences(
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("health_goal_id") val healthGoalId: Int,
    @SerialName("daily_steps") val dailySteps: Int,
    @SerialName("water_intake") val waterIntake: Int,
    @SerialName("energy_intake") val energyIntake: Int,
    @SerialName("sleep_hours") val sleepHours: Int
)

/**
 * Система измерения (метрическая, имперская)
 */
@Serializable
data class UnitSystem(
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("system_name") val systemName: String
)

/**
 * Система измерения энергии (калории, джоули)
 */
@Serializable
data class EnergySystem(
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("system_name") val systemName: String
)

/**
 * Цель по здоровью
 */
@Serializable
data class HealthGoal(
    @SerialName("health_goal_id") val healthGoalId: Int,
    @SerialName("goal_name") val goalName: String
)

/**
 * Учетные данные пользователя для авторизации
 */
@Serializable
data class UserCredentials(
    val email: String,
    val password: String
)

/**
 * Модель для смены пароля
 */
@Serializable
data class PasswordChangeRequest(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String
)

/**
 * Ответ с полной информацией о пользователе
 */
@Serializable
data class UserResponse(
    @SerialName("user_id") val userId: Int,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val email: String,
    @SerialName("created_at") val createdAt: String,
    val parameters: UserParametersDTO? = null,
    val preferences: UserPreferences? = null
)

/**
 * Стандартный ответ об ошибке
 */
@Serializable
data class ErrorResponse(
    val status: Int,
    val message: String,
    val timestamp: String = LocalDateTime.now().toString()
)

/**
 * Стандартный успешный ответ
 */
@Serializable
data class SuccessResponse(
    val success: Boolean = true,
    val message: String? = null,
    val data: Map<String, String>? = null
)