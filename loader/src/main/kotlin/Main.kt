package org.example

import kotlinx.coroutines.runBlocking
import org.example.simulator.AppSimulator

fun main() = runBlocking {
    val simulator = AppSimulator()
    simulator.runSimulation(count = 10000)
}
