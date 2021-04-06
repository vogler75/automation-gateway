package at.rocworks.gateway.core.data

import org.apache.ignite.cache.query.annotations.QuerySqlField
import java.sql.Timestamp

class PiOpcValue(topic: Topic, topicValue: TopicValue) {
    fun key(): String = "$systemName/$address"

    @QuerySqlField
    val topicName = topic.topicName

    @QuerySqlField(index = true)
    val topicType = topic.topicType.name

    @QuerySqlField(index = true)
    val systemName = topic.systemName

    @QuerySqlField(index = true)
    val systemType = topic.systemType.name

    @QuerySqlField
    val address = topic.address

    @QuerySqlField
    val stringValue: String = topicValue.valueAsString()

    @QuerySqlField
    val doubleValue: Double? = topicValue.valueAsDouble()

    @QuerySqlField
    val sourceTime: Timestamp = Timestamp.from(topicValue.sourceTime())

    @QuerySqlField
    val serverTime: Timestamp = Timestamp.from(topicValue.serverTime())

    @QuerySqlField(index = true)
    val statusString: String = topicValue.statusAsString()

    @QuerySqlField(index = true)
    val dataTypeName: String = topicValue.dataTypeName()
}