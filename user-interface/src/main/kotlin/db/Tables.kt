package ru.polyZoj.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.dao.id.IntIdTable
import common.models.EnergySystem
import common.models.FriendshipStatus
import common.models.UnitSystem

/** “users” **/
object UsersTable : IntIdTable("users", "user_id") {
    val firstName = varchar("first_name", 100)
    val lastName  = varchar("last_name", 100)
    val email     = varchar("email", 255).uniqueIndex()
    val avatarUrl = varchar("avatar_url", 255).nullable()
    val isAdmin = bool("is_admin")
    val clubId = integer("club_id").nullable()
    val createdAt = timestamp("created_at")

}

/** "user_credentials" **/
object UserCredentialsTable : Table("user_credentials") {
    val userId   = integer("user_id").references(UsersTable.id)
    val username = varchar("username", 255).uniqueIndex()
    val password = varchar("password", 255)

    override val primaryKey = PrimaryKey(userId, name = "pk_users_credentials")
}

/** “unit_systems” **/
object UnitSystemsTable : Table("unit_systems") {
    val unitSystemId = integer("unit_system_id").autoIncrement()
    val systemName   = enumerationByName<UnitSystem>("system_name", 20)

    override val primaryKey = PrimaryKey(unitSystemId, name = "pk_unit_systems")
}

/** “energy_systems” **/
object EnergySystemsTable : Table("energy_systems") {
    val energySystemId = integer("energy_system_id").autoIncrement()
    val systemName     = enumerationByName<EnergySystem>("system_name", 20)

    override val primaryKey = PrimaryKey(energySystemId, name = "pk_energy_systems")
}

/** “primary_health_goals” **/
object PrimaryHealthGoalsTable : Table("primary_health_goals") {
    val healthGoalId = integer("health_goal_id").autoIncrement()
    val goalName     = varchar("goal_name", 50)

    override val primaryKey = PrimaryKey(healthGoalId, name = "pk_health_goals")
}

/** “friendship_statuses” **/
object FriendshipStatusesTable : Table("friendship_statuses") {
    val friendshipStatusId = integer("friendship_status_id").autoIncrement()
    val friendshipStatus   = enumerationByName<FriendshipStatus>("friendship_status", 20)

    override val primaryKey = PrimaryKey(friendshipStatusId, name = "pk_friendship_statuses")
}

/** “user_parameters” **/
object UserParametersTable : Table("user_parameters") {
    val userId       = integer("user_id").references(UsersTable.id)
    val weight       = float("weight")
    val height       = short("height")
    val birthDate    = date("birth_date")
    val unitSystemId = integer("unit_system_id").references(UnitSystemsTable.unitSystemId)

    override val primaryKey = PrimaryKey(userId, name = "pk_user_parameters")
}

/** “user_preferences” **/
object UserPreferencesTable : Table("user_preferences") {
    val userId         = integer("user_id").references(UsersTable.id)
    val unitSystemId   = integer("unit_system_id").references(UnitSystemsTable.unitSystemId)
    val energySystemId = integer("energy_system_id").references(EnergySystemsTable.energySystemId)
    val healthGoalId   = integer("health_goal_id").references(PrimaryHealthGoalsTable.healthGoalId).nullable()
    val dailyStepGoal     = integer("daily_step_goal").nullable()
    val waterIntakeGoal    = integer("water_intake_goal").nullable()
    val calorieGoal   = short("calorie_goal").nullable()
    val sleepGoal     = float("sleep_goal").nullable()
    val workoutsGoal  = short("workouts_goal").nullable()

    override val primaryKey = PrimaryKey(userId, name = "pk_user_preferences")
}

/** "friendships" **/
object FriendshipsTable : Table("friendships") {
    val userId = integer("user_id").references(UsersTable.id)
    val friendId = integer("friend_id").references(UsersTable.id)
    val friendshipStatus   = integer("friendship_status_id").references(FriendshipStatusesTable.friendshipStatusId)

    override val primaryKey = PrimaryKey(userId, friendId, name = "pk_friendships")
}
