package org.example

import kotlinx.coroutines.runBlocking
import org.example.simulator.AppSimulator

fun main() = runBlocking {
    val simulator = AppSimulator()
    simulator.runSimulation(count = 5_000, regIdStart = 101)
    simulator.runSimulation(count = 5_000, regIdStart = 111)
}
