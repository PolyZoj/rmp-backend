package org.example.simulator.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDTO(val login: String, val password: String)