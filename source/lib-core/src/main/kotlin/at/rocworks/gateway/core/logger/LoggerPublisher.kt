package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.DataPoint
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

    private val soloFormatter: (DataPoint) -> Buffer

    private val bulkFormatter: (List<DataPoint>) -> Buffer

    init {
        soloFormatter = when (getMessageFormat().uppercase()) {
            RAW -> ::formatterRawSolo
            JSON -> ::formatterJsonSolo
            JSONSIMPLE -> ::formatterJsonSimpleSolo
            SPARKPLUGB -> ::formatterSpbSolo
            else -> ::unknownSoloFormat
        }

        bulkFormatter = when (getMessageFormat().uppercase()) {
            JSON -> ::formatterJsonBulk
            JSONSIMPLE -> ::formatterJsonSimpleBulk
            SPARKPLUGB -> ::formatterSpbBulk
            else -> ::unknownBulkFormat
        }

        usedWriteExecutor = when(isBulkMessages()) {
            true -> ::writeExecutorBulk
            false -> ::writeExecutorSolo
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unknownSoloFormat(points: DataPoint): Nothing
    = throw Exception("Unknown message format!")
    @Suppress("UNUSED_PARAMETER")
    private fun unknownBulkFormat(points: List<DataPoint>): Nothing
        = throw Exception("Unknown bulk message format!")

    abstract fun publish(point: DataPoint, payload: Buffer)
    abstract fun publish(points: List<DataPoint>, payload: Buffer)

    private fun writeExecutorSolo() {
        var counter = 0
        var point: DataPoint? = pollDatapointWait()
        while (point != null) {
            publish(point, soloFormatter(point))
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
            publish(points, bulkFormatter(points))
    }

    override fun writeExecutor() {
        try {
            usedWriteExecutor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Format Raw
    private fun formatterRawSolo(point: DataPoint): Buffer = Buffer.buffer(point.value.value.toString())

    // Format Json
    private fun formatterJsonSolo(point: DataPoint): Buffer {
        val result = JsonObject()
        result.put("Topic", point.topic.encodeToJson())
        result.put("Value", point.value.encodeToJson())
        return result.toBuffer()
    }

    private fun formatterJsonBulk(points: List<DataPoint>): Buffer {
        val result = points.map { point ->
            val item = JsonObject()
            item.put("Topic", point.topic.encodeToJson())
            item.put("Value", point.value.encodeToJson())
            item
        }
        return JsonArray(result).toBuffer()
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

    private fun formatterJsonSimpleSolo(point: DataPoint): Buffer {
        return formatterJsonSimpleSoloObject(point).toBuffer()
    }

    private fun formatterJsonSimpleBulk(points: List<DataPoint>): Buffer {
        val result = points.map { point ->
            formatterJsonSimpleSoloObject(point)
        }
        return JsonArray(result).toBuffer()
    }

    // Format SparkplugB
    private var spbSeq = 0
    private fun formatterSpbSolo(point: DataPoint): Buffer {
        val payload = SparkplugBPayload.SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        formatterSpbMetric(point)?.let { payload.addMetric(it) }
        if (spbSeq++ == 255) spbSeq=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    private fun formatterSpbBulk(points: List<DataPoint>): Buffer {
        val payload = SparkplugBPayload.SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        points.forEach { point -> formatterSpbMetric(point)?.let { payload.addMetric(it) } }
        if (spbSeq++ == 255) spbSeq=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
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