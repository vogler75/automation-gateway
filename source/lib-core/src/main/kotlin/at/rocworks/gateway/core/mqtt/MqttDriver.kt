package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem

import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.mqtt.messages.MqttPublishMessage
import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload

import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class MqttDriver(val config: JsonObject) : DriverBase(config) {
    override fun getType() = Topic.SystemType.Mqtt

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
    private val retained: Boolean = config.getBoolean("Retained", false)
    private val format: String = config.getString("Format", "JSON")

    private val maxMessageSizeKb = config.getInteger("MaxMessageSizeKb", 8) * 1024

    private val subscribedTopics = HashSet<Topic>() // Subscribed topic name can have wildcard
    private val receivedTopics = HashMap<String, List<Topic>>()

    private val decodeMessage: (topic: Topic, topicReceived: String, message: Buffer) -> List<DataPoint> = when (format.uppercase()) {
        "RAW" -> ::decodeValueMessage
        "JSON" -> ::decodeJsonMessage
        "SPARKPLUGB" -> ::decodeSparkplugMessage
        else -> throw Exception("Unknown message format for decode!")
    }

    private val encodeMessage: (topic: String, value: TopicValue) -> Buffer = when (format.uppercase()) {
        "RAW" -> ::encodeRawMessage
        "JSON" -> ::encodeJsonMessage
        "SPARKPLUGB" -> ::encodeSparkplugBMessage
        else -> throw Exception("Unknown message format for encode!")
    }

    init {
    }

    override fun connect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val options = MqttClientOptions()

        username?.let { options.username = it }
        password?.let { options.password = it }
        options.setClientId(clientId)
        options.setCleanSession(cleanSession)
        options.setSsl(ssl)
        options.setTrustAll(trustAll)
        options.setMaxMessageSize(maxMessageSizeKb)

        client = MqttClient.create(vertx, options)
        client?.publishHandler(::valueConsumer)
        client?.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.cause()}]")
            if (it.succeeded()) promise.complete()
            else promise.fail("Connect failed!")
        } ?: promise.fail("Client is null!")

        return promise.future()
    }

    override fun disconnect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        client?.disconnect {
            logger.info("Mqtt client disconnect [${it.succeeded()}]")
            promise.complete(it.succeeded())
        } ?: promise.fail("Client is null!")
        return promise.future()
    }

    override fun shutdown() {
        disconnect()
    }

    private fun compareTopic(actualTopic: String, subscribedNode: String): Boolean {
        val regex = subscribedNode.replace("+", "[^/]+").replace("#", ".+")
        return actualTopic.matches(regex.toRegex());
    }

    override fun subscribeTopics(topics: List<Topic>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        if (topics.isEmpty()) promise.complete(true)
        else {
            logger.info("Subscribe to [${topics.size}] topics")
            topics.forEach { topic ->
                logger.info("Subscribe topic [${topic.topicName}] node [${topic.node}]")
                client?.subscribe(topic.node, qos)
                registry.addMonitoredItem(MqttMonitoredItem(topic.node), topic)
                subscribedTopics.add(topic)
                resetReceivedTopics(topic.node)
            }
            promise.complete(true)
        }
        return promise.future()
    }

    override fun unsubscribeTopics(topics: List<Topic>, items: List<MonitoredItem>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()

        subscribedTopics.removeIf { topic ->
            topics.any { it.topicName == topic.topicName }
        }

        items.map { (it as MqttMonitoredItem).item }
            .filter { node ->
                subscribedTopics.none { it.node == node }
            }
            .forEach { node ->
                logger.info("Unsubscribe node [${node}]", )
                client?.unsubscribe(node)?.onComplete {
                    logger.info("Unsubscribe node [${node}] result [${it.succeeded()}]")
                }
                resetReceivedTopics(node)
            }
        promise.complete(true)
        return promise.future()
    }

    private fun resetReceivedTopics(node: String) {
        receivedTopics.filter { receivedTopic ->
            compareTopic(receivedTopic.key, node)
        }.forEach {
            receivedTopics.remove(it.key)
        }
    }

    private fun decodeValueMessage(topic: Topic, topicReceived: String, payload: Buffer) : List<DataPoint> {
        val clone = topic.copy(browsePath = topicReceived)
        return listOf(DataPoint(clone, TopicValue(payload)))
    }

    private fun decodeJsonMessage(topic: Topic, topicReceived: String, payload: Buffer) : List<DataPoint> {
        val clone = topic.copy(browsePath = topicReceived)
        return when (val json = Json.decodeValue(payload)) {
            is JsonObject -> {
                listOf(DataPoint(clone, TopicValue.decodeFromJson(json)))
            }
            is JsonArray -> {
                json.filterIsInstance<JsonObject>().map {
                    DataPoint(clone, TopicValue.decodeFromJson(it))
                }
            }
            else -> listOf(DataPoint(clone, TopicValue(json)))
        }
    }

    private fun decodeSparkplugMessage(topic: Topic, topicReceived: String, payload: Buffer) : List<DataPoint> {
        val decoder = SparkplugBPayloadDecoder()
        val message = decoder.buildFromByteArray(payload.bytes, null)

        return message.metrics.map {
            val clone = topic.copy(node = it.name, browsePath = topicReceived)
            DataPoint(clone, TopicValue(
                value = it.value,
                sourceTime = it.timestamp.toInstant(),
                serverTime = it.timestamp.toInstant()
            ))
        }
    }

    private fun valueConsumer(message: MqttPublishMessage) {
        logger.finest { "Got value [${message.topicName()}] [${message.payload()}]" }
        try {
            val receivedTopic = message.topicName()
            val receivedPayload = message.payload()
            fun publish(topic: Topic) {
                try {
                    val data = decodeMessage(topic, receivedTopic, receivedPayload)
                    data.forEach {
                        vertx.eventBus().publish(it.topic.topicName, it)
                    }
                } catch (e: Exception) {
                    logger.warning("Exception on publish [$topic] value [${e.message}]", )
                }
            }

            receivedTopics[receivedTopic]?.forEach(::publish) ?: run {
                val topics = subscribedTopics.filter { compareTopic(receivedTopic, it.node) }
                receivedTopics[receivedTopic] = topics
                topics.forEach(::publish)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        val promise = Promise.promise<Boolean>()

        val message = when (topic.format) {
            Topic.Format.Value -> encodeMessage(topic.node, TopicValue(value.toString()))
            Topic.Format.Json -> encodeMessage(topic.node, TopicValue.decodeFromJson(value.toJsonObject()))
        }
        client?.publish(topic.node, message, MqttQoS.valueOf(qos), false, retained)?.onComplete {
            promise.complete(true)
        }

        return promise.future()
    }

    override fun readServerInfo(): JsonObject {
        logger.severe("readServerInfo() Not yet implemented")
        return JsonObject()
    }

    override fun readHandler(message: Message<JsonObject>) {
        logger.severe("readHandler() Not yet implemented")
        message.fail(-1, "Not yet implemented")
    }
    @Suppress("UNUSED_PARAMETER")
    private fun encodeRawMessage(topic: String, value: TopicValue): Buffer =
        Buffer.buffer(value.valueAsString())
    @Suppress("UNUSED_PARAMETER")
    private fun encodeJsonMessage(topic: String, value: TopicValue): Buffer =
        value.encodeToJson().toBuffer()

    private var spbSequenceNumber = 0

    private fun encodeSparkplugBMessage(topic: String, value: TopicValue) : Buffer {
        val payload = SparkplugBPayload.SparkplugBPayloadBuilder(spbSequenceNumber.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        val metric = Metric.MetricBuilder(
            topic,
            MetricDataType.String,  // TODO: handle different data types of value.value
            value.valueAsString()
        )
        payload.addMetric(metric.createMetric())
        if (spbSequenceNumber++ == 255) spbSequenceNumber=0
        return Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    override fun writeHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")

        fun publish(client: MqttClient, topic: String, value: String): Future<Int> {
            val payload = encodeMessage(topic, TopicValue(value))
            return client.publish(topic, payload, MqttQoS.valueOf(qos), false, retained)
        }

        when {
            node != null && node is String -> {
                val value = message.body().getString("Value", "")
                client?.let { client ->
                    publish(client, node, value).onComplete {
                        message.reply(JsonObject().put("Ok", true))
                    }
                } ?: run {
                    message.reply(JsonObject().put("Ok", false))
                }
            }
            node != null && node is JsonArray -> {
                val values = message.body().getJsonArray("Value", JsonArray())
                client?.let { client ->
                    CompositeFuture.all(node.zip(values).mapNotNull {
                        if (it.first is String && it.second is String) {
                            publish(client, it.first as String, it.second as String)
                        } else null
                    }).onComplete { result ->
                      message.reply(JsonObject().put("Ok", JsonArray(node.map { result.succeeded() })))
                    }
                } ?: run {
                    message.reply(JsonObject().put("Ok", JsonArray(node.map { false })))
                }
            }
            else -> {
                val err = String.format("Invalid format in write request!")
                message.reply(JsonObject().put("Ok", false))
                logger.severe(err)
            }
        }
    }

    override fun browseHandler(message: Message<JsonObject>) {
        logger.severe("browseHandler() Not yet implemented")
        message.reply(JsonObject().put("Ok", false))
    }

    override fun schemaHandler(message: Message<JsonObject>) {
        logger.severe("schemaHandler() Not yet implemented")
        message.reply(JsonObject().put("Ok", false))
    }
}