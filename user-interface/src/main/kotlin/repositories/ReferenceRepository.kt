package ru.polyZoj.repositories

import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import ru.polyZoj.db.*
import ru.polyZoj.models.*

/**
 * Репозиторий для работы со справочными данными
 */
class ReferenceRepository {
    /**
     * Получает список всех систем единиц измерения
     * @return список систем единиц измерения
     */
    suspend fun getAllUnitSystems(): List<UnitSystem> = DatabaseFactory.dbReadQuery {
        UnitSystems.selectAll()
            .map { row -> 
                UnitSystem(
                    unitSystemId = row[UnitSystems.id].value,
                    systemName = row[UnitSystems.systemName]
                )
            }
    }
    
    /**
     * Получает список всех систем измерения энергии
     * @return список систем измерения энергии
     */
    suspend fun getAllEnergySystems(): List<EnergySystem> = DatabaseFactory.dbReadQuery {
        EnergySystems.selectAll()
            .map { row -> 
                EnergySystem(
                    energySystemId = row[EnergySystems.id].value,
                    systemName = row[EnergySystems.systemName]
                )
            }
    }
    
    /**
     * Получает список всех целей по здоровью
     * @return список целей по здоровью
     */
    suspend fun getAllHealthGoals(): List<HealthGoal> = DatabaseFactory.dbReadQuery {
        HealthGoals.selectAll()
            .map { row -> 
                HealthGoal(
                    healthGoalId = row[HealthGoals.id].value,
                    goalName = row[HealthGoals.goalName]
                )
            }
    }
    
    /**
     * Получает систему единиц измерения по ID
     * @param id ID системы единиц измерения
     * @return система единиц измерения или null, если не найдена
     */
    suspend fun getUnitSystemById(id: Int): UnitSystem? = DatabaseFactory.dbReadQuery {
        UnitSystems.select { UnitSystems.id eq id }
            .firstOrNull()
            ?.let { row ->
                UnitSystem(
                    unitSystemId = row[UnitSystems.id].value,
                    systemName = row[UnitSystems.systemName]
                )
            }
    }
    
    /**
     * Получает систему измерения энергии по ID
     * @param id ID системы измерения энергии
     * @return система измерения энергии или null, если не найдена
     */
    suspend fun getEnergySystemById(id: Int): EnergySystem? = DatabaseFactory.dbReadQuery {
        EnergySystems.select { EnergySystems.id eq id }
            .firstOrNull()
            ?.let { row ->
                EnergySystem(
                    energySystemId = row[EnergySystems.id].value,
                    systemName = row[EnergySystems.systemName]
                )
            }
    }
    
    /**
     * Получает цель по здоровью по ID
     * @param id ID цели по здоровью
     * @return цель по здоровью или null, если не найдена
     */
    suspend fun getHealthGoalById(id: Int): HealthGoal? = DatabaseFactory.dbReadQuery {
        HealthGoals.select { HealthGoals.id eq id }
            .firstOrNull()
            ?.let { row ->
                HealthGoal(
                    healthGoalId = row[HealthGoals.id].value,
                    goalName = row[HealthGoals.goalName]
                )
            }
    }
    
    /**
     * Метод для совместимости с ReferenceRoutes
     */
    suspend fun getUnitSystems(): List<UnitSystem> = getAllUnitSystems()
    
    /**
     * Метод для совместимости с ReferenceRoutes
     */
    suspend fun getEnergySystems(): List<EnergySystem> = getAllEnergySystems()
    
    /**
     * Метод для совместимости с ReferenceRoutes
     */
    suspend fun getHealthGoals(): List<HealthGoal> = getAllHealthGoals()
} 