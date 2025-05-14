package org.example

import kotlinx.coroutines.runBlocking
import org.example.simulator.AppSimulator

fun main() = runBlocking {
    val simulator = AppSimulator()
    simulator.runRegistration(userCount = 10_000)
    simulator.runActions()
}
