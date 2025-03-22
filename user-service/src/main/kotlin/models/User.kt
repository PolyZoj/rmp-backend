package ru.polyZoj.models

import java.time.LocalDateTime

data class User(
    val userId: Int,
    val firstName: String,
    val lastName: String,
    val joinDate: LocalDateTime?
) 