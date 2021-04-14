package at.rocworks.gateway.core.cache

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import org.apache.ignite.cache.query.annotations.QuerySqlField
import java.sql.Timestamp

class OpcValue(topic: Topic, topicValue: TopicValue) {
    fun key(): String = "$systemName/$nodeId"

    @QuerySqlField(index = true, orderedGroups = [QuerySqlField.Group(name = "opcvalue_unique_idx", order = 0, descending = true)])
    val systemName = topic.systemName

    @QuerySqlField(index = true, orderedGroups = [QuerySqlField.Group(name = "opcvalue_unique_idx", order = 1, descending = true)])
    val nodeId = topic.address

    @QuerySqlField(index = true)
    val topicType = topic.topicType.name

    @QuerySqlField(index = true)
    val systemType = topic.systemType.name

    @QuerySqlField(index = true)
    val statusString: String = topicValue.statusAsString()

    @QuerySqlField(index = true)
    val dataTypeName: String = topicValue.dataTypeName()

    @QuerySqlField
    val topic = topic.topicName

    @QuerySqlField
    val stringValue: String = topicValue.valueAsString()

    @QuerySqlField
    val doubleValue: Double? = topicValue.valueAsDouble()

    @QuerySqlField
    val sourceTime: Timestamp = Timestamp.from(topicValue.sourceTime())

    @QuerySqlField
    val serverTime: Timestamp = Timestamp.from(topicValue.serverTime())

    @QuerySqlField
    val updateValue: String? = null
}