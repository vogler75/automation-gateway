package at.rocworks.gateway.core.data

import org.apache.ignite.cache.query.annotations.QuerySqlField
import java.sql.Timestamp
import kotlin.math.pow

class PiOpcValue(topic: Topic, topicValue: TopicValue) {
    fun key(): String = "$systemName/$address"

    @QuerySqlField(index = true, inlineSize = 100)
    val topicType = topic.topicType.name

    @QuerySqlField(index = true, inlineSize = 100)
    val systemName = topic.systemName

    @QuerySqlField(index = true, inlineSize = 100)
    val systemType = topic.systemType.name

    @QuerySqlField(index = true, inlineSize = 100)
    val statusString: String = topicValue.statusAsString()

    @QuerySqlField(index = true, inlineSize = 100)
    val dataTypeName: String = topicValue.dataTypeName()

    @QuerySqlField
    val topicName = topic.topicName

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
}