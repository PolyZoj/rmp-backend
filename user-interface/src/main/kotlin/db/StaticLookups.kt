package ru.polyZoj.db

import common.models.EnergySystem
import common.models.UnitSystem
import org.jetbrains.exposed.sql.selectAll

object StaticLookups {
    private val unitSystemByName: Map<UnitSystem, Int> = DatabaseFactory.read {
        UnitSystemsTable
            .selectAll()
            .associate { row ->
                row[UnitSystemsTable.systemName] to row[UnitSystemsTable.unitSystemId]
            }
    }

    private val energySystemByName: Map<EnergySystem, Int> = DatabaseFactory.read {
        EnergySystemsTable
            .selectAll()
            .associate { row ->
                row[EnergySystemsTable.systemName] to row[EnergySystemsTable.energySystemId]
            }
    }

    fun idFor(unit: UnitSystem): Int =
        unitSystemByName[unit] ?: error("Unknown unit system ${unit.name}")

    fun idFor(energy: EnergySystem): Int =
        energySystemByName[energy] ?: error("Unknown energy system ${energy.name}")
}

