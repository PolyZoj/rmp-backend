package org.example.simulator.util
import org.example.simulator.model.UserCredentials


object DataGenerator {
    fun generateCredentials(i: Int) = UserCredentials(
        username = "user$i",
        password = "pass$i"
    )
}
