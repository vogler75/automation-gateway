package at.rocworks.gateway.core.driver

import at.rocworks.gateway.core.data.Topic
import java.util.ArrayList
import java.util.HashMap

class Registry {
    private val topics = HashSet<Topic>()
    private val topicClients = HashMap<String, HashSet<String>>()
    private val topicMonitoredItems = HashMap<String, ArrayList<MonitoredItem>>()

    fun addClient(clientId: String, topic: Topic) : Pair<Int, Boolean> {
        val clients = topicClients.getOrDefault(topic.topicName, HashSet())
        if (clients.size == 0) topicClients[topic.topicName] = clients
        val added = clients.add(clientId)
        return Pair(clients.size, added)
    }

    fun delClient(clientId: String, topic: Topic) : Pair<Int, Boolean> {
        val clients = topicClients.getOrDefault(topic.topicName, HashSet())
        val removed = clients.remove(clientId)
        if (clients.size==0) topicClients.remove(topic.topicName)
        return Pair(clients.size, removed)
    }

    fun addMonitoredItem(item: MonitoredItem, topic: Topic) {
        topics.add(topic)
        val items = topicMonitoredItems.getOrDefault(topic.topicName, ArrayList<MonitoredItem>())
        if (items.size == 0) topicMonitoredItems[topic.topicName] = items
        items.add(item)
    }

    fun delMonitoredItems(topic: Topic) {
        topicMonitoredItems.remove(topic.topicName)
    }

    fun getTopics(): List<Topic> {
        return topics.toList()
    }

    fun delTopic(topic: Topic) : List<MonitoredItem> {
        topics.remove(topic)
        val result = topicMonitoredItems.remove(topic.topicName)
        return result ?: ArrayList<MonitoredItem>()
    }
}