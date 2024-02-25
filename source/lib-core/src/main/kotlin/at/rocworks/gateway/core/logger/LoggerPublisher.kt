package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.BrowsePath
import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload
import java.util.*
import kotlin.collections.LinkedHashMap

abstract class LoggerPublisher(config: JsonObject) : LoggerBase(config) {
    private fun getMessageFormat(): String = config.getString("Format", "JSON")
    private fun isBulkMessages(): Boolean = config.getBoolean("BulkMessages", false)

    private val usedWriteExecutor : () -> Unit

    companion object {
        const val RAW = "RAW"
        const val JSON = "JSON"
        const val JSONSIMPLE = "JSONSIMPLE"
        const val SPARKPLUGB = "SPARKPLUGB"
    }

    private val soloPublisher: (DataPoint) -> Unit

    private val bulkPublisher: (List<DataPoint>) -> Unit

    init {
        soloPublisher = when (getMessageFormat().uppercase()) {
            RAW -> ::publishRawSolo
            JSON -> ::publishJsonDefaultSolo
            JSONSIMPLE -> ::publishJsonSimpleSolo
            SPARKPLUGB -> ::publishSpbSolo
            else -> ::unknownSoloFormat
        }

        bulkPublisher = when (getMessageFormat().uppercase()) {
            JSON -> ::publishJsonDefaultBulk
            JSONSIMPLE -> ::publishJsonSimpleBulk
            SPARKPLUGB -> ::publishSpbBulk
            else -> ::unknownBulkFormat
        }

        usedWriteExecutor = when(isBulkMessages()) {
            true -> ::writeExecutorBulk
            false -> ::writeExecutorSolo
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unknownSoloFormat(points: DataPoint) {
        throw Exception("Unknown message format!")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unknownBulkFormat(points: List<DataPoint>) {
        throw Exception("Unknown bulk message format!")
    }

    abstract fun publish(topic: Topic, payload: Buffer)
    abstract fun publish(topics: List<Topic>, payload: Buffer)

    private fun writeExecutorSolo() {
        var counter = 0
        var point: DataPoint? = pollDatapointWait()
        while (point != null) {
            soloPublisher(point)
            point = if (++counter < writeParameterBlockSize) pollDatapointNoWait() else null
        }
    }

    private fun writeExecutorBulk() {
        var counter = 0
        val points = mutableListOf<DataPoint>()
        var point: DataPoint? = pollDatapointWait()
        while (point != null) {
            points.add(point)
            point = if (++counter < writeParameterBlockSize) pollDatapointNoWait() else null
        }
        if (counter>0)
            bulkPublisher(points)
    }

    override fun writeExecutor() {
        try {
            usedWriteExecutor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Format Raw
    private fun publishRawSolo(point: DataPoint) {
        val buffer = Buffer.buffer(point.value.value.toString())
        publish(point.topic, buffer)
    }

    // Format Json
    private fun publishJsonDefaultSolo(point: DataPoint) {
        val result = JsonObject()
        result.put("Topic", point.topic.encodeToJson())
        result.put("Value", point.value.encodeToJson())
        val buffer = result.toBuffer()
        publish(point.topic, buffer)
    }

    private fun publishJsonDefaultBulk(points: List<DataPoint>) {
        val result = points.map { point ->
            val item = JsonObject()
            item.put("Topic", point.topic.encodeToJson())
            item.put("Value", point.value.encodeToJson())
            item
        }
        val buffer = JsonArray(result).toBuffer()
        publish(points.map { it.topic }, buffer)
    }

    // Format JsonSimple
    private fun formatterJsonSimpleSoloObject(point: DataPoint): JsonObject {
        val payload = JsonObject()
        payload.put("nodeId", point.topic.topicNode)
        payload.put("systemName", point.topic.systemName)
        payload.put("topicName", point.topic.topicName)
        payload.put("browsePath", point.topic.getBrowsePathOrNode())
        payload.put("sourceTime", point.value.sourceTimeAsISO())
        payload.put("serverTime", point.value.serverTimeAsISO())
        payload.put("sourceTimeMs", point.value.sourceTimeMs())
        payload.put("serverTimeMs", point.value.serverTimeMs())
        payload.put("value", point.value.valueAsObject())
        payload.put("valueAsString", point.value.valueAsString())
        payload.put("valueAsDouble", point.value.valueAsDouble())
        payload.put("statusCode", point.value.statusAsString())
        return payload
    }

    private fun publishJsonSimpleSolo(point: DataPoint) {
        val buffer = formatterJsonSimpleSoloObject(point).toBuffer()
        publish(point.topic, buffer)
    }

    private fun publishJsonSimpleBulk(points: List<DataPoint>) {
        val result = points.map { point ->
            formatterJsonSimpleSoloObject(point)
        }
        val buffer = JsonArray(result).toBuffer()
        publish(points.map { it.topic }, buffer)
    }

    // Format SparkplugB
    private var spbSeq = 0
    private fun publishSpbSolo(point: DataPoint) {
        val payload = SparkplugBPayload.SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        formatterSpbMetric(point)?.let { payload.addMetric(it) }
        if (spbSeq++ == 255) spbSeq=0
        val buffer = Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
        publish(point.topic.copy(browsePath = BrowsePath(point.topic.getBrowsePathOrNode().toList().dropLast(1))), buffer)
    }

    private fun publishSpbBulk(points: List<DataPoint>) {
        val payload = SparkplugBPayload.SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        points.forEach { point -> formatterSpbMetric(point)?.let { payload.addMetric(it) } }
        if (spbSeq++ == 255) spbSeq=0
        val buffer = Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
        publish(points.map { point ->
            point.topic.copy(browsePath = BrowsePath(point.topic.getBrowsePathOrNode().toList().dropLast(1)))
        }, buffer)
    }

    private fun formatterSpbMetric(point: DataPoint): Metric? {
        try {
            if (point.value.value != null) {
                if (point.value.value is LinkedHashMap<*, *>) {
                    val map = point.value.value.entries.associate { item -> item.key.toString() to item.value }
                    return Metric.MetricBuilder(
                        point.topic.getMetricName(),
                        MetricDataType.String,
                        JsonObject(map).toString()
                    )
                        .timestamp(Date(point.value.sourceTime.toEpochMilli()))
                        .createMetric()
                } else {
                    val (type, value) = when (val value = point.value.value) {
                        is Boolean -> MetricDataType.Boolean to value
                        is Byte -> MetricDataType.Int8 to value
                        is Short -> MetricDataType.Int16 to value
                        is Int -> MetricDataType.Int32 to value
                        is Long -> MetricDataType.Int64 to value
                        is Float -> MetricDataType.Float to value
                        is Double -> MetricDataType.Double to value
                        is String -> MetricDataType.String to value
                        is Date -> MetricDataType.DateTime to value
                        is UUID -> MetricDataType.UUID to value.toString()
                        else -> MetricDataType.Unknown to null
                    }
                    if (type != MetricDataType.Unknown) {
                        return Metric.MetricBuilder(point.topic.getMetricName(), type, value)
                            .timestamp(Date(point.value.sourceTime.toEpochMilli()))
                            .createMetric()
                    } else {
                        logger.warning("Unhandled datatype ${point.value.dataTypeName()} for ${point.topic.getBrowsePathOrNode()}! value: ${point.value.value}")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}