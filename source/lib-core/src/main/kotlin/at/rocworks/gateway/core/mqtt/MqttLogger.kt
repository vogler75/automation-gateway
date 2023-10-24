package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerPublisher
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions

import java.util.*

class MqttLogger (config: JsonObject) : LoggerPublisher(config) {
    private var client : MqttClient? = null

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
    private val maxMessageSizeKb = configMqtt.getInteger("MaxMessageSizeKb", 8) * 1024

    private var isConnected = false

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()

        if (client==null) {
            val options = MqttClientOptions()
            username?.let { options.username = it }
            password?.let { options.password = it }
            options.setClientId(clientId)
            options.setCleanSession(cleanSession)
            options.setSsl(ssl)
            options.setTrustAll(trustAll)
            options.setMaxMessageSize(maxMessageSizeKb)
            client = MqttClient.create(vertx, options)
            client!!.closeHandler {
                logger.severe("Connection closed!")
                reconnect()
            }
            client!!.exceptionHandler {
                logger.severe(it.stackTraceToString())
            }
        }

        client!!.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.cause() ?: ""}]")
            if (it.succeeded()) promise.complete()
            else promise.fail("Connect failed!")
        } ?: promise.fail("Client is null!")

        return promise.future()
    }

    override fun close() {
        client!!.disconnect {
            logger.info("Mqtt client disconnect [${it.succeeded()}]")
        }
    }

    fun publish(topic: String, payload: Buffer) {
        if (client!!.isConnected) {
            if (!this.isConnected) {
                this.isConnected=true
                logger.fine("Connected to MQTT broker.")
            }
            client!!.publish(topic, payload, MqttQoS.valueOf(qos), false, retained)
        } else {
            if (this.isConnected) {
                this.isConnected=false
                logger.fine("Disconnected from MQTT broker.")
            }
        }
    }

    override fun publish(point: DataPoint, payload: Buffer) {
        val topic = if (this.topic.isEmpty()) {
            if (point.topic.hasBrowsePath) {
                point.topic.systemNameAndPath
            } else {
                point.topic.node
            }
        } else {
            this.topic + "/" + (if (point.topic.hasBrowsePath) point.topic.browsePath else point.topic.node)
        }
        publish(topic, payload)
    }

    override fun publish(points: List<DataPoint>, payload: Buffer) {
        publish(this.topic, payload)
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