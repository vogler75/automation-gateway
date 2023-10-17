package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.logger.LoggerBase
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import org.eclipse.tahu.SparkplugInvalidTypeException
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
    private val format: String = config.getString("Format", "JSON")
    private val retained: Boolean = config.getBoolean("Retained", false)

    private val formatter: (DataPoint) -> Buffer = when (format) {
        "JSON" -> ::jsonFormat
        "VALUE" -> ::valueFormat
        "SPARKPLUGB" -> ::spbFormat
        else -> ::jsonFormat
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
        client = MqttClient.create(vertx, options)

        client?.publishCompletionHandler(Handler { id: Int -> println("Id of just received PUBACK or PUBCOMP packet is $id") })
        client?.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.cause()}]")
            if (it.succeeded()) {
                promise.complete()
            }
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
        var counter = 0
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null) {
            try {
                val topic = (if (this.topic.isEmpty()) "" else this.topic+"/") + point.topic.systemBrowsePath()
                client?.publish(
                    topic,
                    formatter(point),
                    MqttQoS.valueOf(qos), false, retained
                )
            } catch (e: Exception) {
                e.printStackTrace()
                logger.severe(e.message)
            }
            point = if (++counter < writeParameterBlockSize) writeValueQueue.poll() else null
        }
    }

    private fun jsonFormat(point: DataPoint) = point.value.encodeToJson().toBuffer()
    private fun valueFormat(point: DataPoint) = Buffer.buffer(point.value.value.toString())

    private var spbSeq = 0
    private fun spbFormat(point: DataPoint) : Buffer {
        val payload = SparkplugBPayloadBuilder(spbSeq.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        val dataType = MetricDataType.String
        val metric = MetricBuilder(point.topic.browsePath, dataType, point.value.value.toString())
            .timestamp(Date(point.value.sourceTime.toEpochMilli()))
            .createMetric()
        payload.addMetric(metric)
        if (spbSeq++ == 255) spbSeq=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    @Throws(SparkplugInvalidTypeException::class)
    private fun newRecord(name: String, type: MetricDataType): Metric {
        val random = Random()
        val timestamp = Date()
        println("Creatine new $type record, $timestamp")
        // Metric name = Record type
        // Metric datatype = (not used)
        // Metric value = (not used)
        // Metric properties = Record fields
        return MetricBuilder(name, type, null).timestamp(timestamp).createMetric()
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