package ru.polyZoj.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime
import ru.polyZoj.common.ZonedDateTimeSerializer

@Serializable
enum class PrimaryHealthGoal {
    LOSE_WEIGHT,
    GAIN_MUSCLE,
    MAINTAIN_FITNESS
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
    val id: Int,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name")  val lastName:  String,
    val email: String,
    @SerialName("avatar_url") val avatarUrl: String,
    val password: String,
    @SerialName("is_admin")    val isAdmin: Boolean,
    val username: String,
    @SerialName("date_of_birth") @Serializable(with = ZonedDateTimeSerializer::class) val dateOfBirth: ZonedDateTime,
    val weight: Float,
    val height: Short,
    @SerialName("primary_health_goal") val primaryHealthGoal: PrimaryHealthGoal,
    @SerialName("daily_step_goal") val dailyStepGoal: Int,
    @SerialName("water_intake_goal") val waterIntakeGoal: Int,
    @SerialName("calorie_goal") val calorieGoal: Short,
    @SerialName("workouts_count") val workoutsCount: Short,
    val clubs: List<Int>, // (ids)
    @SerialName("created_at") @Serializable(with = ZonedDateTimeSerializer::class) val createdAt: ZonedDateTime,
    @SerialName("updated_at") @Serializable(with = ZonedDateTimeSerializer::class) val updatedAt: ZonedDateTime,
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
