package common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import common.serializers.InstantSerializer
import common.serializers.LocalDateSerializer
import java.time.LocalDate
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Table "users" **/
@Serializable
data class User @OptIn(ExperimentalTime::class) constructor(
    @SerialName("user_id") val userId: Int,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("email") val email: String,
    @SerialName("avatar_url") val avatarUrl: String?,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("club_id") val clubId: Int? = 0,
    @SerialName("created_at") @Serializable(with = InstantSerializer::class) val createdAt: Instant? = null
)

/**
 * Учетные данные для авторизации
 */
@Serializable
data class UserCredentials(
    @SerialName("user_id")  val userId: Int,
    @SerialName("username") val username: String,
    @SerialName("password") val password: String
)

@Serializable
enum class UnitSystem {
    @SerialName("metric")    METRIC,
    @SerialName("imperial")  IMPERIAL
}

@Serializable
enum class EnergySystem {
    @SerialName("kcal") KCAL,
    @SerialName("kj")   KJ
}

/** Table "primary_health_goals" **/
@Serializable
data class PrimaryHealthGoal(
    @SerialName("health_goal_id") val healthGoalId: Int,
    @SerialName("goal_name")      val goalName: String
)


@Serializable
enum class FriendshipStatus {
    @SerialName("pending")   PENDING,
    @SerialName("accepted")  ACCEPTED,
    @SerialName("rejected")  REJECTED,
    @SerialName("blocked")   BLOCKED
}

/** “YourFriend/InviteSent/NotYourFriend/Self” */
@Serializable
enum class FriendshipStatusFrontEnd {
    @SerialName("YourFriend") YOUR_FRIEND,
    @SerialName("InviteSent") INVITE_SENT,
    @SerialName("NotYourFriend") NOT_YOUR_FRIEND,
    @SerialName("Self") SELF,
}

/** Table "user_parameters" **/
@Serializable
data class UserParameters @OptIn(ExperimentalTime::class) constructor(
    @SerialName("user_id") val userId: Int,
    @SerialName("weight") val weight: Float,
    @SerialName("height") val height: Short,
    @SerialName("birth_date") @Serializable(with = LocalDateSerializer::class) val birthDate: LocalDate,
    @SerialName("unit_system_id") val unitSystemId: Int
) {
    // BMI высчитываем метрическим:
    fun getBMI(): Double =
        if (height == 0.toShort()) 0.0
        else weight * 10000.0 / (height * height)
}

/** Table "user_preferences" **/
@Serializable
data class UserPreferences(
    @SerialName("user_id") val userId: Int,
    @SerialName("unit_system_id") val unitSystemId: Int,
    @SerialName("energy_system_id") val energySystemId: Int,
    @SerialName("health_goal_id") val healthGoalId: Int? = null,
    @SerialName("daily_step_goal") val dailyStepGoal: Int? = null,
    @SerialName("water_intake_goal") val waterIntakeGoal: Int? = null,
    @SerialName("calorie_goal") val calorieGoal: Short? = null,
    @SerialName("sleep_goal") val sleepGoal: Float? = null,
    @SerialName("workouts_goal") val workoutsGoal: Short? = null,
)

/**
 * Join of "users", "user_credentials, "user_parameters", "user_preferences"
 * without password
 * with resolved unit_system_id, energy_system_id, health_goal_id
 */
@Serializable
data class UserDTO @OptIn(ExperimentalTime::class) constructor(
    val user: User,
    @SerialName("username") val username: String,
    @SerialName("weight") val weight: Float,
    @SerialName("height") val height: Short,
    @SerialName("birth_date") @Serializable(with = LocalDateSerializer::class) val birthDate: LocalDate,
    @SerialName("unit_system") val unitSystem: UnitSystem,
    @SerialName("energy_system") val energySystem: EnergySystem,
    @SerialName("health_goal") val healthGoal: String? = null,
    @SerialName("daily_step_goal") val dailyStepGoal: Int? = null,
    @SerialName("water_intake_goal") val waterIntakeGoal: Int? = null,
    @SerialName("calorie_goal") val calorieGoal: Short? = null,
    @SerialName("sleep_goal") val sleepGoal: Float? = null,
    @SerialName("workouts_goal") val workoutsGoal: Short? = null,
    )

/**
 * Data to register a new user
 */
@Serializable
data class UserRegistration @OptIn(ExperimentalTime::class) constructor(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String,
    @SerialName("email") val email: String,
    @SerialName("avatar_url") val avatarUrl: String?,
    @SerialName("weight") val weight: Float,
    @SerialName("height") val height: Short,
    @SerialName("birth_date") @Serializable(with = LocalDateSerializer::class) val birthDate: LocalDate,
    @SerialName("unit_system") val unitSystem: UnitSystem,
    @SerialName("energy_system") val energySystem: EnergySystem,
    @SerialName("health_goal") val healthGoal: String? = null,
    @SerialName("daily_step_goal") val dailyStepGoal: Int? = null,
    @SerialName("water_intake_goal") val waterIntakeGoal: Int? = null,
    @SerialName("calorie_goal") val calorieGoal: Short? = null,
    @SerialName("sleep_goal") val sleepGoal: Float? = null,
    @SerialName("workouts_goal") val workoutsGoal: Short? = null,
)

@Serializable
data class UserUpdatable @OptIn(ExperimentalTime::class) constructor(
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("weight") val weight: Float? = null,
    @SerialName("height") val height: Short? = null,
    @SerialName("health_goal") val healthGoal: String? = null,
    @SerialName("daily_step_goal") val dailyStepGoal: Int? = null,
    @SerialName("water_intake_goal") val waterIntakeGoal: Int? = null,
    @SerialName("calorie_goal") val calorieGoal: Short? = null,
    @SerialName("sleep_goal") val sleepGoal: Float? = null,
    @SerialName("workouts_goal") val workoutsGoal: Short? = null,
)

@Serializable
data class UserBasicInfo(
    @SerialName("user_id") val userId: Int,
    @SerialName("username") val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * Смена пароля
 */
@Serializable
data class PasswordChangeRequest(
    @SerialName("user_id") val userId: Int,
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String
)

/** Table "friendships" **/
@Serializable
data class Friendship @OptIn(ExperimentalTime::class) constructor(
    @SerialName("user_id") val userId: Int,
    @SerialName("friend_id") val friendId: Int,
    @SerialName("friendship_status") val friendshipStatus: FriendshipStatus,
)
