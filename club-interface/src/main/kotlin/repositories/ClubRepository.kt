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
import ru.polyZoj.logger
import ru.polyZoj.models.*
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

class ClubRepository {
    private val log = logger<ClubRepository>()

    /** Create a new club and return its ID */
    @OptIn(ExperimentalTime::class)
    fun createClub(name: String, description: String, ownerId: Int): Int {
        log.info("Creating new club: name='{}', ownerId={}", name, ownerId)
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
            log.info("Club created successfully with clubId={}", clubId)
            return clubId
        } catch (e: ExposedSQLException) {
            log.error("Error creating club: name='{}'\nerror={}", name, e.message)
            throw e
        }
    }

    @OptIn(ExperimentalTime::class)
    fun addMember(clubId: Int, userId: Int): Boolean {
        log.info("Adding member userId={} to clubId={}", userId, clubId)
        return try {
            DatabaseFactory.write {
                ClubMembersTable.insert {
                    it[ClubMembersTable.clubId] = clubId
                    it[ClubMembersTable.userId] = userId
                    it[ClubMembersTable.joinedAt] = Clock.System.now().toJavaInstant()
                }
            }
            log.info("Member added successfully: clubId={}, userId={}", clubId, userId)
            true
        } catch (e: Exception) {
            log.error("Error adding member: clubId={}, userId={}, error={}", clubId, userId, e.message)
            false
        }
    }

    @OptIn(ExperimentalTime::class)
    fun removeMember(clubId: Int, userId: Int): Boolean {
        log.info("Removing member userId={} from clubId={}", userId, clubId)
        return try {
            DatabaseFactory.write {
                val deleted = ClubMembersTable.deleteWhere { 
                    (ClubMembersTable.clubId eq clubId) and (ClubMembersTable.userId eq userId)
                }
                deleted > 0
            }.also { success ->
                if (success) log.info("Member removed successfully: clubId={}, userId={}", clubId, userId)
                else log.warn("Failed to remove member: clubId={}, userId={}", clubId, userId)
            }
        } catch (e: Exception) {
            log.error("Error removing member: clubId={}, userId={}, error={}", clubId, userId, e.message)
            false
        }
    }
    @OptIn(ExperimentalTime::class)
    fun getClub(clubId: Int): Club? {
        log.info("Fetching club with clubId={}", clubId)
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
            if (it != null) log.info("Club found with clubId={}", clubId)
            else log.info("No club found with clubId={}", clubId)
        }
    }

}