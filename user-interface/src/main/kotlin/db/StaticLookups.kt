package ru.polyZoj.db

import common.models.EnergySystem
import common.models.FriendshipStatus
import common.models.UnitSystem
import org.jetbrains.exposed.sql.selectAll

object StaticLookups {
    private val unitSystemByName: Map<UnitSystem, Int> = DatabaseFactory.readBlocking {
        UnitSystemsTable
            .selectAll()
            .associate { row ->
                row[UnitSystemsTable.systemName] to row[UnitSystemsTable.unitSystemId]
            }
    }

    private val energySystemByName: Map<EnergySystem, Int> = DatabaseFactory.readBlocking {
        EnergySystemsTable
            .selectAll()
            .associate { row ->
                row[EnergySystemsTable.systemName] to row[EnergySystemsTable.energySystemId]
            }
    }

    private val friendshipStatusByName: Map<FriendshipStatus, Int> = DatabaseFactory.readBlocking {
        FriendshipStatusesTable
            .selectAll()
            .associate { row ->
                row[FriendshipStatusesTable.friendshipStatus] to row[FriendshipStatusesTable.friendshipStatusId]
            }
    }

    fun idFor(unit: UnitSystem): Int =
        unitSystemByName[unit] ?: error("Unknown unit system ${unit.name}")

    fun idFor(energy: EnergySystem): Int =
        energySystemByName[energy] ?: error("Unknown energy system ${energy.name}")

    fun idFor(friendship: FriendshipStatus): Int =
        friendshipStatusByName[friendship] ?: error("Unknown friendship status ${friendship.name}")

    fun nameForUnitSystemId(unitId: Int): UnitSystem =
        unitSystemByName.entries.firstOrNull { it.value == unitId }?.key
            ?: error("Unknown unit system id $unitId")

    fun nameForEnergySystemId(energyId: Int): EnergySystem =
        energySystemByName.entries.firstOrNull { it.value == energyId }?.key
            ?: error("Unknown energy system id $energyId")

    fun nameForFriendshipStatusId(friendshipId: Int): FriendshipStatus =
        friendshipStatusByName.entries.firstOrNull { it.value == friendshipId }?.key
            ?: error("Unknown friendship status id $friendshipId")
}

