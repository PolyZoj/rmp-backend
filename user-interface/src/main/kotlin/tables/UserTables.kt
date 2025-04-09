package ru.polyZoj.tables

import org.jetbrains.exposed.sql.Table

/**
 * Таблица пользователей
 */
object Users : Table("user") {
    val userId = integer("user_id").autoIncrement()
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255)
    val email = varchar("email", 255).uniqueIndex()
    val password = varchar("password", 255)
    val createdAt = varchar("created_at", 255)
    
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Таблица физических параметров пользователей
 */
object UserParameters : Table("user_parameters") {
    val userId = integer("user_id").references(Users.userId)
    val weight = integer("weight")
    val height = integer("height")
    val birthDate = varchar("birth_date", 255)
    val unitSystemId = integer("unit_system_id").references(UnitSystemsTable.unitSystemId)
    
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Таблица предпочтений пользователей
 */
object UserPreferencesTable : Table("user_preferences") {
    val userId = integer("user_id").references(Users.userId)
    val unitSystemId = integer("unit_system_id").references(UnitSystemsTable.unitSystemId)
    val energySystemId = integer("energy_system_id").references(EnergySystemsTable.energySystemId)
    val healthGoalId = integer("health_goal_id").references(HealthGoalsTable.healthGoalId)
    val dailySteps = integer("daily_steps")
    val waterIntake = integer("water_intake")
    val energyIntake = integer("energy_intake")
    val sleepHours = integer("sleep_hours")
    
    override val primaryKey = PrimaryKey(userId)
}

/**
 * Таблица систем единиц измерения
 */
object UnitSystemsTable : Table("unit_system") {
    val unitSystemId = integer("unit_system_id").autoIncrement()
    val systemName = varchar("system_name", 255)
    
    override val primaryKey = PrimaryKey(unitSystemId)
}

/**
 * Таблица систем измерения энергии
 */
object EnergySystemsTable : Table("energy_system") {
    val energySystemId = integer("energy_system_id").autoIncrement()
    val systemName = varchar("system_name", 255)
    
    override val primaryKey = PrimaryKey(energySystemId)
}

/**
 * Таблица целей по здоровью
 */
object HealthGoalsTable : Table("health_goal") {
    val healthGoalId = integer("health_goal_id").autoIncrement()
    val goalName = varchar("goal_name", 255)
    
    override val primaryKey = PrimaryKey(healthGoalId)
} 