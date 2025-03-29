package ru.polyZoj.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

// Таблица пользователей
object Users : IntIdTable(name = "user", columnName = "user_id") {
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp())
}

// Таблица email пользователей
object UserEmails : Table(name = "user_email") {
    val userId = integer("user_id").references(Users.id)
    val email = varchar("email", 255).uniqueIndex()

    override val primaryKey = PrimaryKey(userId)
}

// Таблица паролей пользователей
object UserPasswords : Table(name = "user_password") {
    val userId = integer("user_id").references(Users.id)
    val password = varchar("password", 255)

    override val primaryKey = PrimaryKey(userId)
}

// Таблицы справочников
object UnitSystems : IntIdTable(name = "unit_system", columnName = "unit_system_id") {
    val systemName = varchar("system_name", 255).uniqueIndex()
}

object EnergySystems : IntIdTable(name = "energy_system", columnName = "energy_system_id") {
    val systemName = varchar("system_name", 255).uniqueIndex()
}

object HealthGoals : IntIdTable(name = "health_goal", columnName = "health_goal_id") {
    val goalName = varchar("goal_name", 255).uniqueIndex()
}

// Таблица параметров пользователя
object UserParameters : Table(name = "user_parameters") {
    val userId = integer("user_id").references(Users.id)
    val weight = integer("weight")
    val height = integer("height")
    val birthDate = date("birth_date")
    val unitSystemId = integer("unit_system_id").references(UnitSystems.id)

    override val primaryKey = PrimaryKey(userId)
}

// Таблицы предпочтений пользователя
object UserUnitSystems : Table(name = "user_unit_system") {
    val userId = integer("user_id").references(Users.id)
    val unitSystemId = integer("unit_system_id").references(UnitSystems.id)

    override val primaryKey = PrimaryKey(userId)
}

object UserEnergySystems : Table(name = "user_energy_system") {
    val userId = integer("user_id").references(Users.id)
    val energySystemId = integer("energy_system_id").references(EnergySystems.id)

    override val primaryKey = PrimaryKey(userId)
}

object UserGoals : Table(name = "user_goals") {
    val userId = integer("user_id").references(Users.id)
    val healthGoalId = integer("health_goal_id").references(HealthGoals.id)
    val dailySteps = integer("daily_steps")
    val waterIntake = integer("water_intake")
    val energyIntake = integer("energy_intake")
    val sleepHours = integer("sleep_hours")

    override val primaryKey = PrimaryKey(userId)
}