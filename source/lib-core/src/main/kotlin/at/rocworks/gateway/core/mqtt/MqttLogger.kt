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
import org.eclipse.tahu.message.model.Metric
import org.eclipse.tahu.message.model.Metric.MetricBuilder
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder
import java.util.*
import java.util.concurrent.TimeUnit


class MqttLogger (config: JsonObject) : LoggerBase(config) {
    var client: MqttClient? = null

    private val port: Int = config.getInteger("Port", 1883)
    private val host: String = config.getString("Host", "localhost")
    private val username: String? = config.getString("Username")
    private val password: String? = config.getString("Password")
    private val clientId: String = config.getString("ClientId", UUID.randomUUID().toString())
    private val cleanSession: Boolean = config.getBoolean("CleanSession", true)
    private val ssl: Boolean = config.getBoolean("Ssl", false)
    private val trustAll: Boolean = config.getBoolean("TrustAll", true)
    private val qos: Int = config.getInteger("Qos", 0)
    private val topic: String = config.getString("Topic", "")
    private val bulkMessages: Boolean = config.getBoolean("BulkMessages", false)
    private val format: String = config.getString("Format", "JSON")
    private val retained: Boolean = config.getBoolean("Retained", false)
    private val maxMessageSizeKb = config.getInteger("MaxMessageSizeKb", 8) * 1024

    private val formatter: (DataPoint) -> Buffer = when (format) {
        "RAW" -> ::rawFormat
        "JSON" -> ::jsonFormat
        "SPARKPLUGB" -> ::spbFormat
        else -> ::unknownFormat
    }

    private val bulkFormatter: (List<DataPoint>) -> Buffer = when (format) {
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
        payload.addMetric(metric(point))
        if (spbSeq++ == 255) spbSeq=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    private fun spbBulkFormat(points: List<DataPoint>) : Buffer {
        val payload = SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        points.forEach { point -> payload.addMetric(metric(point)) }
        if (spbSeq++ == 255) spbSeq=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    private fun metric(point: DataPoint): Metric? {
        val dataType = MetricDataType.String
        return MetricBuilder(point.topic.browsePath, dataType, point.value.value.toString())
            .timestamp(Date(point.value.sourceTime.toEpochMilli()))
            .createMetric()
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