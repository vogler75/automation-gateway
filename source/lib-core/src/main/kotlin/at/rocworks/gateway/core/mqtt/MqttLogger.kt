package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.logger.LoggerBase
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.DataSet.DataSetBuilder
import org.eclipse.tahu.message.model.Metric
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.Row.RowBuilder
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import org.eclipse.tahu.protobuf.SparkplugBProto.Payload.DataSet.Row
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.LinkedHashMap


class MqttLogger (config: JsonObject) : LoggerBase(config) {
    var client: MqttClient? = null

    private val configMqtt = config.getJsonObject("Mqtt", config)
    private val port: Int = configMqtt.getInteger("Port", 1883)
    private val host: String = configMqtt.getString("Host", "localhost")
    private val username: String? = configMqtt.getString("Username")
    private val password: String? = configMqtt.getString("Password")
    private val clientId: String = configMqtt.getString("ClientId", UUID.randomUUID().toString())
    private val cleanSession: Boolean = configMqtt.getBoolean("CleanSession", true)
    private val ssl: Boolean = configMqtt.getBoolean("Ssl", false)
    private val trustAll: Boolean = configMqtt.getBoolean("TrustAll", true)
    private val qos: Int = configMqtt.getInteger("Qos", 0)
    private val retained: Boolean = configMqtt.getBoolean("Retained", false)
    private val topic: String = configMqtt.getString("Topic", "")
    private val format: String = configMqtt.getString("Format", "JSON")
    private val bulkMessages: Boolean = configMqtt.getBoolean("BulkMessages", false)
    private val maxMessageSizeKb = configMqtt.getInteger("MaxMessageSizeKb", 8) * 1024

    private val formatter: (DataPoint) -> Buffer = when (format.uppercase()) {
        "RAW" -> ::rawFormat
        "JSON" -> ::jsonFormat
        "SPARKPLUGB" -> ::spbFormat
        else -> ::unknownFormat
    }

    private val bulkFormatter: (List<DataPoint>) -> Buffer = when (format.uppercase()) {
        "JSON" -> ::jsonBulkFormat
        "SPARKPLUGB" -> ::spbBulkFormat
        else -> ::unknownBulkFormat
    }

    private val writeExecutorInstance: () -> Unit = when (bulkMessages) {
        false -> ::singleWriteExecutor
        true -> ::bulkWriteExecutor
    }

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        val options = MqttClientOptions()

        username?.let { options.username = it }
        password?.let { options.password = it }
        options.setClientId(clientId)
        options.setCleanSession(cleanSession)
        options.setSsl(ssl)
        options.setTrustAll(trustAll)
        options.setMaxMessageSize(maxMessageSizeKb)

        client = MqttClient.create(vertx, options)
        client?.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.cause()}]")
            if (it.succeeded()) promise.complete()
            else promise.fail("Connect failed!")
        } ?: promise.fail("Client is null!")

        return promise.future()
    }

    override fun close() {
        client?.disconnect {
            logger.info("Mqtt client disconnect [${it.succeeded()}]")
        }
    }

    override fun writeExecutor() {
        try {
            writeExecutorInstance()
        } catch (e: Exception) {
            logger.severe(e.message)
        }
    }

    private fun singleWriteExecutor() {
        var counter = 0
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null) {
            val topic = (if (this.topic.isEmpty()) "" else this.topic + "/") + point.topic.systemBrowsePath()
            client?.publish(topic, formatter(point), MqttQoS.valueOf(qos), false, retained)
            point = if (++counter < writeParameterBlockSize) writeValueQueue.poll() else null
        }
    }

    private fun bulkWriteExecutor() {
        var counter = 0
        val points = mutableListOf<DataPoint>()
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null) {
            points.add(point)
            point = if (++counter < writeParameterBlockSize) writeValueQueue.poll() else null
        }
        if (counter>0)
            client?.publish(this.topic, bulkFormatter(points), MqttQoS.valueOf(qos), false, retained)
    }

    private fun unknownFormat(points: DataPoint) = Buffer.buffer("Unknown message format!")
    private fun unknownBulkFormat(points: List<DataPoint>) = Buffer.buffer("Unknown bulk message format!")

    private fun jsonFormat(point: DataPoint) : Buffer {
        val result = JsonObject()
        result.put("Topic", point.topic.encodeToJson())
        result.put("Value", point.value.encodeToJson())
        return result.toBuffer()
    }
    private fun jsonBulkFormat(points: List<DataPoint>) : Buffer {
        val result = points.map { point ->
            val item = JsonObject()
            item.put("Topic", point.topic.encodeToJson())
            item.put("Value", point.value.encodeToJson())
            item
        }
        return JsonArray(result).toBuffer()
    }

    private fun rawFormat(point: DataPoint) = Buffer.buffer(point.value.value.toString())

    private var spbSeq = 0
    private fun spbFormat(point: DataPoint) : Buffer {
        val payload = SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        metric(point)?.let { payload.addMetric(it) }
        if (spbSeq++ == 255) spbSeq=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    private fun spbBulkFormat(points: List<DataPoint>) : Buffer {
        val payload = SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        points.forEach { point -> metric(point)?.let { payload.addMetric(it) } }
        if (spbSeq++ == 255) spbSeq=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    private fun metric(point: DataPoint): Metric? {
        try {
            //logger.info("${point.topic.browsePath} ${point.value.dataTypeName()} ${point.value.value?.javaClass?.name}")
            if (point.value.value != null) {
                if (point.value.value is LinkedHashMap<*, *>) {
                    val map = point.value.value.entries.associate { item -> item.key.toString() to item.value }
                    return MetricBuilder(point.topic.browsePath, MetricDataType.String, JsonObject(map).toString())
                        .timestamp(Date(point.value.sourceTime.toEpochMilli()))
                        .createMetric()
                } else {
                    val dataType = when (point.value.value) {
                        is Boolean -> MetricDataType.Boolean
                        is Int -> MetricDataType.Int32
                        is Long -> MetricDataType.Int64
                        is Double -> MetricDataType.Double
                        is String -> MetricDataType.String
                        else -> MetricDataType.Unknown
                    }
                    if (dataType != MetricDataType.Unknown) {
                        return MetricBuilder(point.topic.browsePath, dataType, point.value.value)
                            .timestamp(Date(point.value.sourceTime.toEpochMilli()))
                            .createMetric()
                    } else {
                        logger.warning("Unhandled datatype ${point.value.dataTypeName()} for ${point.topic.browsePath}! value: ${point.value.value}")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        TODO("Not yet implemented")
    }
}