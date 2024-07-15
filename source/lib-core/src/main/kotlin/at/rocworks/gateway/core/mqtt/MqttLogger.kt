package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.Topic
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
    private val baseTopic: String = configMqtt.getString("Topic", "")
    private val maxMessageSizeKb = configMqtt.getInteger("MaxMessageSizeKb", 8) * 1024

    private var isEnabled = false

    private val topicToTarget : Map<String, String> =
        topicsWithConfig
            .filter { it.second.getString("Target") != null }
            .associate { it.first.topicName to it.second.getString("Target") }

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()

        if (client==null) {
            isEnabled = true
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
                if (isEnabled) reconnect()
            }
            client!!.exceptionHandler {
                logger.severe(it.stackTraceToString())
            }
        }

        client!!.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.cause() ?: ""}]")
            if (it.succeeded()) {
                logger.fine { "Connected to MQTT broker." }
                promise.complete()
            }
            else promise.fail("Connect failed!")
        } ?: promise.fail("Client is null!")

        return promise.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        isEnabled = false
        client!!.disconnect {
            promise.complete()
            logger.info("Mqtt client disconnect [${it.succeeded()}]")
        }
        return promise.future()
    }

    private fun publish(topic: String, payload: Buffer) {
        println("$topic: $payload")
        client!!.publish(topic, payload, MqttQoS.valueOf(qos), false, retained)
    }

    override fun publish(topic: Topic, payload: Buffer) {
        logger.fine { "Produce External: $topic" }
        val targetTopic = if (topicToTarget.containsKey(topic.topicName)) {
            val target = topicToTarget[topic.topicName]!!
            if (target.endsWith("#") || target.endsWith("+")) {
                if (topic.hasBrowsePath && topic.topicPath.endsWith("#") || topic.topicPath.endsWith("+")) {
                    target.dropLast(1) + topic.getBrowsePathOrNode().toString().removePrefix(topic.topicPath.dropLast(1))
                } else {
                    target.dropLast(1) + topic.topicNode
                }
            }
            else target
        } else if (this.baseTopic.isEmpty()) {
            topic.systemName + "/" + topic.getBrowsePathOrNode()
        } else {
            this.baseTopic + "/" + topic.systemName + "/" + topic.getBrowsePathOrNode()
        }
        publish(targetTopic, payload)
    }

    override fun publish(topics: List<Topic>, payload: Buffer) {
        publish(this.baseTopic, payload)
    }
}