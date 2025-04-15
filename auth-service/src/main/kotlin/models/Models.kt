package ru.polyZog.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class User(val id: String, val username: String, val password: String)

@Serializable
data class TokenResponse(val token: String)