package ru.polyZoj.kafka

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import java.util.Properties

class KafkaConfig {

    fun createTopicIfNotExists(topicName: String, numPartitions: Int, replicationFactor: Short, bootstrapServers: String = "kafka:9092") {
        val props = Properties().apply {
            put("bootstrap.servers", bootstrapServers)
        }

        AdminClient.create(props).use { adminClient ->
            val topics = adminClient.listTopics().names().get()
            if (!topics.contains(topicName)) {
                println("Topic $topicName does not exist. Creating it...")
                val newTopic = NewTopic(topicName, numPartitions, replicationFactor)
                val createTopicsResult = adminClient.createTopics(listOf(newTopic))
                createTopicsResult.all().get()
                println("Topic $topicName created successfully.")
            } else {
                println("Topic $topicName already exists.")
            }
        }
    }
}