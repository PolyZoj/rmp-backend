package ru.polyZoj.repositories

import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.postgresql.util.PSQLException
import ru.polyZoj.db.*
import ru.polyZoj.exceptions.DuplicateFieldException
import ru.polyZoj.models.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import common.LogSender
import common.Level

class ClubRepository(private val logger: LogSender) {

    fun logInfo(ctx: String, msg: String) = logger.log("club-interface", Level.INFO, msg, ctx)
    fun logError(ctx: String, msg: String) = logger.log("club-interface", Level.ERROR, msg, ctx)

    /** Create a new club and return its ID */
    @OptIn(ExperimentalTime::class)
    fun createClub(name: String, description: String, ownerId: Int): Int {
        logInfo("createClub", "Creating new club: name='$name', ownerId=$ownerId")
        try {
            val clubId = DatabaseFactory.write {
                ClubsTable.insertAndGetId {
                    it[ClubsTable.name] = name
                    it[ClubsTable.description] = description
                    it[ClubsTable.ownerId] = ownerId
                    it[ClubsTable.createdAt] = Clock.System.now().toJavaInstant()
                }.value.also { newClubId ->
                    ClubMembersTable.insert {
                        it[ClubMembersTable.clubId] = newClubId
                        it[ClubMembersTable.userId] = ownerId
                        it[ClubMembersTable.joinedAt] = Clock.System.now().toJavaInstant()
                    }
                }
            }
            logInfo("createClub", "Club created successfully with clubId=$clubId")
            return clubId
        } catch (e: ExposedSQLException) {
            logError("createClub", "Error creating club: name='$name'\nerror=${e.message}")
            throw e
        }
    }

    @OptIn(ExperimentalTime::class)
    fun addMember(clubId: Int, userId: Int): Boolean {
        logInfo("addMember", "Adding member userId=$userId to clubId=$clubId")
        return try {
            DatabaseFactory.write {
                ClubMembersTable.insert {
                    it[ClubMembersTable.clubId] = clubId
                    it[ClubMembersTable.userId] = userId
                    it[ClubMembersTable.joinedAt] = Clock.System.now().toJavaInstant()
                }
            }
            logInfo("addMember", "Member added successfully: clubId=$clubId, userId=$userId")
            true
        } catch (e: Exception) {
            logError("addMember", "Error adding member: clubId=$clubId, userId=$userId, error=${e.message}")
            false
        }
    }

@OptIn(ExperimentalTime::class)
fun removeMember(clubId: Int, userId: Int): Boolean {
    logInfo("removeMember", "Removing member userId=$userId from clubId=$clubId")
    return try {
        DatabaseFactory.write {
            val deleted = ClubMembersTable.deleteWhere { 
                (ClubMembersTable.clubId eq clubId) and (ClubMembersTable.userId eq userId)
            }
            val success = deleted > 0

            if (success) {
                val memberCount = ClubMembersTable
                    .selectAll()
                    .where{ ClubMembersTable.clubId eq clubId } 
                    .count()
                
                if (memberCount == 0L) {
                    ClubsTable.deleteWhere { ClubsTable.id eq clubId }
                    logInfo("removeMember", "Club $clubId deleted due to zero remaining members")
                }
            }

            success
        }.also { success ->
            if (success) logInfo("removeMember", "Member removed successfully: clubId=$clubId, userId=$userId")
            else logInfo("removeMember", "Failed to remove member: clubId=$clubId, userId=$userId")
        }
    } catch (e: Exception) {
        logError("removeMember", "Error removing member: clubId=$clubId, userId=$userId, error=${e.message}")
        false
    }
}
    
    @OptIn(ExperimentalTime::class)
    fun getClub(clubId: Int): Club? {
        logInfo("getClub", "Fetching club with clubId=$clubId")
        return DatabaseFactory.read {
            val clubRow = ClubsTable
                .selectAll()
                .where { ClubsTable.id eq clubId }
                .singleOrNull() ?: return@read null

            val members = ClubMembersTable
                .selectAll()
                .where { ClubMembersTable.clubId eq clubId }
                .map { it[ClubMembersTable.userId].toString() }
                .toMutableSet()

            Club(
                id = clubRow[ClubsTable.id].value.toString(),
                name = clubRow[ClubsTable.name],
                description = clubRow[ClubsTable.description],
                ownerId = clubRow[ClubsTable.ownerId].toString(),
                members = members
            )
        }.also {
            if (it != null) logInfo("getClub", "Club found with clubId=$clubId")
            else logInfo("getClub", "No club found with clubId=$clubId")
        }
    }

    @OptIn(ExperimentalTime::class)
    fun listClubs(limit: Int, offset: Int): List<Club?> {
        logInfo("listClubs", "Listing clubs with pagination - limit: $limit, offset: $offset")
        
        return DatabaseFactory.read {
            ClubsTable
                .selectAll()
                .limit(limit)
                .offset(offset.toLong())
                .map { row ->
                    val clubId = row[ClubsTable.id].value
                    
                    Club(
                        id = clubId.toString(),
                        name = row[ClubsTable.name],
                        description = row[ClubsTable.description],
                        ownerId = row[ClubsTable.ownerId].toString(),
                        members = ClubMembersTable
                            .selectAll()
                            .where { ClubMembersTable.clubId eq clubId }
                            .map { it[ClubMembersTable.userId].toString() }
                            .toMutableSet()
                    )
                }
        }.also {
            logInfo("listClubs", "Returning ${it.size} clubs")
        }
    }
}