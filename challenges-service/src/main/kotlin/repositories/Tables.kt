package ru.polyZoj.repositories

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object ChallengesTable : IntIdTable("challenges") {
    val challengeType = varchar("challenge_type", length = 50)
    val title = varchar("title", length = 255)
    val description = varchar("description", length = 1000)
    val metricType = varchar("metric_type", length = 50)
    val targetValue = integer("target_value")
    val startTime = datetime("start_time")
    val endTime = datetime("end_time")
    val createdBy = varchar("created_by", 255)
    val status = varchar("status", 50)

}

object UserChallengesTable : IntIdTable("user_challenges") {
    val challengeId = reference("challenge_id", ChallengesTable)
    val userId = integer("user_id")
    val progressValue = integer("progress_value")
    val completedAt = datetime("completed_at").nullable()
    val rewardGranted = bool("reward_granted").default(false)
}

object ChallengeRewardsTable : IntIdTable("challenge_rewards") {
    val challengeId = reference("challenge_id", ChallengesTable)
    val rewardType = varchar("reward_type", 100)
    val rewardValue = integer("reward_value")

}
