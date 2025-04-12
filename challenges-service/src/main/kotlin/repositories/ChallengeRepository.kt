package ru.polyZoj.repositories

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import ru.polyZoj.models.Challenge
import ru.polyZoj.models.UserChallengeProgress
import ru.polyZoj.models.ChallengeReward
import java.time.ZoneOffset

object ChallengeRepository {
    // Create or insert a challenge
    fun createChallenge(challenge: Challenge): Challenge = transaction {
        val newId = ChallengesTable.insertAndGetId { row ->
            row[challengeType] = challenge.challengeType
            row[title] = challenge.title
            row[description] = challenge.description
            row[metricType] = challenge.metricType
            row[targetValue] = challenge.targetValue
            row[startTime] = challenge.startTime.toLocalDateTime()
            row[endTime] = challenge.endTime.toLocalDateTime()
            row[createdBy] = challenge.createdBy
            row[status] = challenge.status
        }.value

        challenge.copy(challengeId = newId)
    }

    fun getChallengeById(id: Int): Challenge? = transaction {
        ChallengesTable.selectAll().where { ChallengesTable.id eq id}
            .map { row ->
                Challenge(
                    challengeId = row[ChallengesTable.id].value,
                    challengeType = row[ChallengesTable.challengeType],
                    title = row[ChallengesTable.title],
                    description = row[ChallengesTable.description],
                    metricType = row[ChallengesTable.metricType],
                    targetValue = row[ChallengesTable.targetValue],
                    startTime = row[ChallengesTable.startTime].atZone(ZoneOffset.UTC),
                    endTime = row[ChallengesTable.endTime].atZone(ZoneOffset.UTC),
                    createdBy = row[ChallengesTable.createdBy],
                    status = row[ChallengesTable.status]
                )
            }.singleOrNull()
    }

    fun getAllChallenges(): List<Challenge> = transaction {
        ChallengesTable.selectAll().map { row ->
            Challenge(
                challengeId = row[ChallengesTable.id].value,
                challengeType = row[ChallengesTable.challengeType],
                title = row[ChallengesTable.title],
                description = row[ChallengesTable.description],
                metricType = row[ChallengesTable.metricType],
                targetValue = row[ChallengesTable.targetValue],
                startTime = row[ChallengesTable.startTime].atZone(ZoneOffset.UTC),
                endTime = row[ChallengesTable.endTime].atZone(ZoneOffset.UTC),
                createdBy = row[ChallengesTable.createdBy],
                status = row[ChallengesTable.status]
            )
        }
    }
}

object UserChallengeRepository {
    fun getUserChallenges(userId: Int): List<UserChallengeProgress> = transaction {
        UserChallengesTable.selectAll().where { UserChallengesTable.userId eq userId }
            .map { row ->
                UserChallengeProgress(
                    challengeId = row[UserChallengesTable.challengeId].value,
                    userId = row[UserChallengesTable.userId],
                    progressValue = row[UserChallengesTable.progressValue],
                    completedAt = row[UserChallengesTable.completedAt]?.atZone(ZoneOffset.UTC),
                    rewardGranted = row[UserChallengesTable.rewardGranted]
                )
            }
    }

    fun createUserChallengeProgress(progress: UserChallengeProgress) = transaction {
        UserChallengesTable.insert {
            it[challengeId] = progress.challengeId
            it[userId] = progress.userId
            it[progressValue] = progress.progressValue
            it[completedAt] = progress.completedAt?.toLocalDateTime()
            it[rewardGranted] = progress.rewardGranted
        }
    }

    fun updateUserChallengeProgress(progress: UserChallengeProgress) = transaction {
        UserChallengesTable.update(
            where = {
                (UserChallengesTable.challengeId eq progress.challengeId) and
                        (UserChallengesTable.userId eq progress.userId)
            }
        ) {
            it[progressValue] = progress.progressValue
            it[completedAt] = progress.completedAt?.toLocalDateTime()
            it[rewardGranted] = progress.rewardGranted
        }
    }
}

object ChallengeRewardRepository {
    fun getRewards(): List<ChallengeReward> = transaction {
        ChallengeRewardsTable.selectAll().map { row ->
            ChallengeReward(
                rewardId = row[ChallengeRewardsTable.id].value,
                challengeId = row[ChallengeRewardsTable.challengeId].value,
                rewardType = row[ChallengeRewardsTable.rewardType],
                rewardValue = row[ChallengeRewardsTable.rewardValue]
            )
        }
    }
}
