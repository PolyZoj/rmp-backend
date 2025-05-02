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

class ClubRepository {
    private val log = logger<ClubRepository>()

    /** Create a new club and return its ID */
    @OptIn(ExperimentalTime::class)
    fun createClub(request: ClubCreationRequest): Int {
        log.info("Creating new club: name='{}', ownerId={}", request.name, request.ownerId)
        try {
            val clubId = DatabaseFactory.write {
                ClubsTable.insertAndGetId {
                    it[name] = request.name
                    it[description] = request.description
                    it[ownerId] = request.ownerId
                    it[createdAt] = Clock.System.now().toJavaInstant()
                }.value.also { newClubId ->
                    // Add owner as first member with "owner" role
                    ClubMembersTable.insert {
                        it[clubId] = newClubId
                        it[userId] = request.ownerId
                        it[joinedAt] = Clock.System.now().toJavaInstant()
                        it[role] = "owner"
                    }
                }
            }
            log.info("Club created successfully with clubId={}", clubId)
            return clubId
        } catch (e: ExposedSQLException) {
            log.error("Error creating club: name='{}'\nerror={}", request.name, e.message)
            throw e
        }
    }

    /** Get club by ID with its members */
    @OptIn(ExperimentalTime::class)
    fun getClub(clubId: Int): ClubResponse? {
        log.info("Fetching club with clubId={}", clubId)
        return DatabaseFactory.read {
            val club = ClubsTable
                .select { ClubsTable.id eq clubId }
                .map { row ->
                    Club(
                        clubId = row[ClubsTable.id].value,
                        name = row[ClubsTable.name],
                        description = row[ClubsTable.description],
                        ownerId = row[ClubsTable.ownerId],
                        createdAt = row[ClubsTable.createdAt].toKotlinInstant()
                    )
                }
                .singleOrNull() ?: return@read null

            val members = ClubMembersTable
                .select { ClubMembersTable.clubId eq clubId }
                .map { row ->
                    ClubMember(
                        clubId = row[ClubMembersTable.clubId],
                        userId = row[ClubMembersTable.userId],
                        joinedAt = row[ClubMembersTable.joinedAt].toKotlinInstant(),
                        role = row[ClubMembersTable.role]
                    )
                }

            ClubResponse(
                clubId = club.clubId,
                name = club.name,
                description = club.description,
                ownerId = club.ownerId,
                createdAt = club.createdAt,
                members = members
            )
        }.also {
            if (it != null) log.info("Club found with clubId={}", clubId)
            else log.info("No club found with clubId={}", clubId)
        }
    }

    /** Get list of clubs with pagination */
    fun getClubs(limit: Int, offset: Int): ClubListResponse {
        log.info("Fetching clubs with limit={}, offset={}", limit, offset)
        return DatabaseFactory.read {
            val total = ClubsTable.selectAll().count()
            val clubs = ClubsTable
                .selectAll()
                .limit(limit, offset.toLong())
                .map { row ->
                    Club(
                        clubId = row[ClubsTable.id].value,
                        name = row[ClubsTable.name],
                        description = row[ClubsTable.description],
                        ownerId = row[ClubsTable.ownerId],
                        createdAt = row[ClubsTable.createdAt].toKotlinInstant()
                    )
                }
            ClubListResponse(
                clubs = clubs,
                total = total.toInt(),
                offset = offset,
                limit = limit
            )
        }
    }

    /** Add a member to a club */
    @OptIn(ExperimentalTime::class)
    fun addMember(clubId: Int, request: ClubMemberAddRequest): Boolean {
        log.info("Adding member userId={} to clubId={}", request.userId, clubId)
        return try {
            DatabaseFactory.write {
                ClubMembersTable.insert {
                    it[ClubMembersTable.clubId] = clubId
                    it[userId] = request.userId
                    it[joinedAt] = Clock.System.now().toJavaInstant()
                    it[role] = request.role
                }
            }
            log.info("Member added successfully: clubId={}, userId={}", clubId, request.userId)
            true
        } catch (e: Exception) {
            log.error("Error adding member: clubId={}, userId={}, error={}", clubId, request.userId, e.message)
            false
        }
    }

    /** Remove a member from a club */
    fun removeMember(clubId: Int, userId: Int): Boolean {
        log.info("Removing member userId={} from clubId={}", userId, clubId)
        return DatabaseFactory.write {
            val deleted = ClubMembersTable.deleteWhere { 
                (ClubMembersTable.clubId eq clubId) and (ClubMembersTable.userId eq userId)
            }
            deleted > 0
        }.also { success ->
            if (success) log.info("Member removed successfully: clubId={}, userId={}", clubId, userId)
            else log.warn("Failed to remove member: clubId={}, userId={}", clubId, userId)
        }
    }
}