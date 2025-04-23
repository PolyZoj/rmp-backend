package ru.polyZoj.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.polyZoj.common.InstantSerializer
import ru.polyZoj.common.LocalDateSerializer
import java.time.LocalDate
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Serializable
data class PrimaryHealthGoal(
    @SerialName("health_goal_id") val healthGoalId: Int,
    @SerialName("goal_name")      val goalName: String
)

@Serializable
data class UnitSystem(
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("system_name")    val systemName: String
)

@Serializable
data class EnergySystem(
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("system_name")      val systemName: String
)


/**
 * Базовая модель пользователя (DB table “users”)
 */
@Serializable
data class User @OptIn(ExperimentalTime::class) constructor(
    @SerialName("user_id") val userId: Int,
    val username: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val email: String,
    @SerialName("created_at")
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant? = null
)

/**
 * Полная информация о пользователе (API DTO)
 */
@Serializable
data class UserDTO @OptIn(ExperimentalTime::class) constructor(
    val userId: Int,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name")  val lastName:  String,
    val username: String,
    val email: String,
    val password: String,
    @SerialName("avatar_url") val avatarUrl: String?,
    @SerialName("is_admin")    val isAdmin: Boolean = false,
    val weight: Float,
    val height: Short,
    @SerialName("date_of_birth") @Serializable(with = LocalDateSerializer::class) val dateOfBirth: LocalDate,
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("primary_health_goal") val primaryHealthGoalId: Int? = null,
    @SerialName("daily_step_goal") val dailyStepGoal: Int? = null,
    @SerialName("water_intake_goal") val waterIntakeGoal: Int? = null,
    @SerialName("calorie_goal") val calorieGoal: Short? = null,
    @SerialName("sleep_goal") val sleepGoal: Float? = null,
    @SerialName("workouts_count") val workoutsCount: Short? = null,
    val clubs: List<Int>? = null, // FK to clubs table. TODO: will it be saved here?
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

/**
 * Регистрация нового пользователя
 */
@Serializable
data class UserRegistration @OptIn(ExperimentalTime::class) constructor(
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    val username: String,
    val email: String,
    val password: String,
    val avatarUrl: String?,
    // пользователь может передавать в имперской или метрической системе…
    // но мы всегда конвертим и храним как метрические:
    val weight: Float,
    val height: Short,
    @SerialName("birth_date") @Serializable(with = LocalDateSerializer::class) val birthDate: LocalDate,
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("health_goal_id") val healthGoalId: Int? = null,
    @SerialName("daily_step_goal") val dailyStepGoal: Int? = null,
    @SerialName("water_intake_goal") val waterIntakeGoal: Int? = null,
    @SerialName("calorie_goal") val calorieGoal: Short? = null,
    @SerialName("sleep_goal") val sleepGoal: Float? = null,
    @SerialName("workouts_count") val workoutsCount: Short? = null,
)

/**
 * Параметры пользователя (API DTO & DB table “user_parameters”)
 */
@Serializable
data class UserParametersDTO @OptIn(ExperimentalTime::class) constructor(
    @SerialName("user_id") val userId: Int,
    val weight: Float,
    val height: Short,
    @SerialName("birth_date") @Serializable(with = LocalDateSerializer::class) val birthDate: LocalDate,
    @SerialName("unit_system_id") val unitSystemId: Int
) {
    // BMI высчитываем метрическим:
    fun getBMI(): Double =
        if (height == 0.toShort()) 0.0
        else weight * 10000.0 / (height * height)
}

/**
 * Предпочтения пользователя (API DTO & DB table “user_preferences”)
 */
@Serializable
data class UserPreferences(
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("health_goal_id") val healthGoalId: Int? = null,
    @SerialName("daily_steps") val dailySteps: Int? = null,
    @SerialName("water_intake") val waterIntake: Int? = null,
    @SerialName("energy_intake") val energyIntake: Short? = null,
    @SerialName("sleep_hours") val sleepHours: Float? = null
)

/**
 * Учетные данные для авторизации
 */
@Serializable
data class UserCredentials(
    @SerialName("user_id")  val userId: Int,
    val username: String,
    val password: String
)

/**
 * Смена пароля
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
data class UserResponse @OptIn(ExperimentalTime::class) constructor(
    @SerialName("user_id")  val userId: Int,
    val username: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name")  val lastName: String,
    val email: String,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val parameters: UserParametersDTO? = null,
    val preferences: UserPreferences? = null
)
