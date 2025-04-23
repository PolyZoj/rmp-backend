package ru.polyZog.repositories

import ru.polyZog.models.Club
import java.util.*

object ClubDataSource {
    private val clubs = mutableMapOf<String, Club>()
    
    fun createClub(name: String, description: String, ownerId: String): Club {
        val id = "club-${UUID.randomUUID()}"
        val club = Club(id, name, description, ownerId).apply {
            members.add(ownerId)
        }
        clubs[id] = club
        return club
    }
    
    fun addMember(clubId: String, userId: String): Boolean {
        val club = clubs[clubId] ?: return false
        return club.members.add(userId)
    }
    
    fun getClub(clubId: String): Club? = clubs[clubId]
    
    fun removeMember(clubId: String, userId: String): Boolean {
        val club = clubs[clubId] ?: return false
        return club.members.remove(userId)
    }
}