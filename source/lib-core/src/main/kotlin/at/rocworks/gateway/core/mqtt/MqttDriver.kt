package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.BrowsePath
import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.get
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.mqtt.messages.MqttPublishMessage
import org.eclipse.tahu.message.SparkplugBPayloadDecoder
import org.eclipse.tahu.message.SparkplugBPayloadEncoder
import org.eclipse.tahu.message.model.Metric
import org.eclipse.tahu.message.model.MetricDataType
import org.eclipse.tahu.message.model.SparkplugBPayload
import java.time.Instant
import java.util.*

class MqttDriver(config: JsonObject) : DriverBase(config) {
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

    enum class PayloadFormat {
        Raw, DefaultJson, CustomJson, SparkplugB
    }

    private val format: PayloadFormat = when (val f = config.getString("Format", "JSON").uppercase()) {
        "RAW" -> PayloadFormat.Raw
        "JSON" -> if (config.containsKey("CustomJson")) PayloadFormat.CustomJson else PayloadFormat.DefaultJson
        "SPARKPLUGB" -> PayloadFormat.SparkplugB
        else -> {
            logger.severe("Unsupported format '$f'! Using RAW.")
            PayloadFormat.Raw
        }
    }

    private val formatJson: JsonObject = config.getJsonObject("CustomJson", JsonObject())

    private val formatJsonValuePath: String = formatJson.getString("Value", "Value")
    private val formatJsonTimestampMsPath: String? = formatJson.getString("TimestampMs", null)
    private val formatJsonTimestampIsoPath: String? = formatJson.getString("TimestampIso", null)

    private val maxMessageSizeKb = config.getInteger("MaxMessageSizeKb", 8) * 1024

    private val subscribedTopics = HashSet<Topic>() // Subscribed topic name can have wildcard
    private val receivedTopics = HashMap<String, List<Topic>>()

    private val decodeMessage: (topic: Topic, topicReceived: String, message: Buffer) -> List<DataPoint> = when (format) {
        PayloadFormat.Raw -> ::decodeValueMessage
        PayloadFormat.DefaultJson -> ::decodeDefaultJsonMessage
        PayloadFormat.CustomJson -> ::decodeCustomJsonMessage
        PayloadFormat.SparkplugB -> ::decodeSparkplugMessage
    }

    private val encodeMessage: (path: BrowsePath, value: TopicValue) -> Pair<BrowsePath, Buffer> = when (format) {
        PayloadFormat.Raw -> ::encodeRawMessage
        PayloadFormat.DefaultJson -> ::encodeDefaultJsonMessage
        PayloadFormat.CustomJson -> ::encodeCustomJsonMessage
        PayloadFormat.SparkplugB -> ::encodeSparkplugBMessage
    }

    override fun getComponentId(): String {
        return id
    }

    override fun getComponentConfig(): JsonObject {
        return this.config
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
        client?.closeHandler {
            logger.severe("Connection closed.")
        }
        client?.exceptionHandler {
            logger.severe("Exception $it")
        }
        client?.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.cause()}]")
            if (it.succeeded()) {
                resubscribe()
                promise.complete()
            }
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
            logger.fine { "Subscribe to [${topics.size}] topics" }
            topics.forEach { topic ->
                logger.fine { "Subscribe topic [${topic.topicName}] node [${topic.topicPath}]" }
                client?.subscribe(topic.topicPath, qos)
                registry.addMonitoredItem(MqttMonitoredItem(topic.topicPath), topic)
                subscribedTopics.add(topic)
                resetReceivedTopics(topic.topicPath)
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
                subscribedTopics.none { it.topicPath == node }
            }
            .forEach { node ->
                logger.info("Unsubscribe node [${node}]")
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

    private fun newTopicOf(topic: Topic, topicReceived: String) =
        topic.copy(browsePath = BrowsePath(topicReceived))

    private fun decodeValueMessage(topic: Topic, topicReceived: String, payload: Buffer) : List<DataPoint> {
        return listOf(DataPoint(newTopicOf(topic, topicReceived), TopicValue(payload)))
    }

    private fun decodeDefaultJsonMessage(topic: Topic, topicReceived: String, payload: Buffer) : List<DataPoint> {
        val newTopic = newTopicOf(topic, topicReceived)
        return when (val json = Json.decodeValue(payload)) {
            is JsonObject -> {
                listOf(DataPoint(newTopic, TopicValue.decodeFromJson(json)))
            }
            is JsonArray -> {
                json.filterIsInstance<JsonObject>().map {
                    DataPoint(newTopic, TopicValue.decodeFromJson(it))
                }
            }
            else -> listOf(DataPoint(newTopic, TopicValue(json)))
        }
    }

    private fun decodeCustomJsonMessage(
        topic: Topic,
        topicReceived: String,
        payload: Buffer
    ) : List<DataPoint> {
        fun toDataPoint(json: JsonObject): DataPoint {
            val value = getJsonValueByPath(json, formatJsonValuePath)
            val tsPath = formatJsonTimestampMsPath ?: formatJsonTimestampIsoPath
            val tsValue = if (tsPath==null) Instant.now() else {
                when (val v = getJsonValueByPath(json, tsPath)) {
                    is Number -> Instant.ofEpochMilli(v.toLong())
                    is String -> Instant.parse(v)
                    else -> Instant.now()
                }
            }
            return DataPoint(newTopicOf(topic, topicReceived), TopicValue(value, sourceTime = tsValue, serverTime = tsValue))
        }

        return when (val json = Json.decodeValue(payload)) {
            is JsonArray -> json.filterIsInstance<JsonObject>().map { toDataPoint(it) }
            is JsonObject -> listOf(toDataPoint(json))
            else -> listOf()
        }
    }

    private fun getJsonValueByPath(json: Any, jsonPath: String): Any? {
        return try {
            val parts = jsonPath.replace("[", ".").replace("]", "").split(".")
            parts.fold(json) { current, item ->
                when (current) {
                    is JsonObject -> current.getValue(item) ?: return null
                    is JsonArray -> current[item.toInt()]
                    else -> {
                        logger.warning("Invalid JSON Type! ${current.javaClass.name} [$jsonPath>>$json]")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("Invalid JSON Format! Exception: $e [$jsonPath>>$json]")
            null
        }
    }


    private fun decodeSparkplugMessage(topic: Topic, topicReceived: String, payload: Buffer) : List<DataPoint> {
        val decoder = SparkplugBPayloadDecoder()
        val message = decoder.buildFromByteArray(payload.bytes, null)
        return message.metrics.map {
            val clone = topic.copy(browsePath = BrowsePath(topicReceived, it.name))
            DataPoint(clone, TopicValue(
                value = it.value,
                sourceTime = it.timestamp?.toInstant() ?: Instant.now(),
                serverTime = it.timestamp?.toInstant() ?: Instant.now()
            ))
        }
    }

    private fun valueConsumer(message: MqttPublishMessage) {
        try {
            val receivedTopic = message.topicName()
            val receivedPayload = message.payload()

            fun publish(topic: Topic) {
                try {
                    logger.fine { "Consume External: $topic Received Topic: [$receivedTopic]" }
                    val dataPoints = decodeMessage(topic, receivedTopic, receivedPayload)
                    dataPoints.forEach { dataPoint ->
                        logger.fine { "Produce Internal: ${dataPoint.topic}"}
                        eventBus.publishDataPoint(vertx, dataPoint)
                    }
                } catch (e: Exception) {
                    logger.warning("Exception on publish [$topic] value [${e.message}]")
                }
            }

            receivedTopics[receivedTopic]?.forEach(::publish) ?: run {
                val topics = subscribedTopics.filter { compareTopic(receivedTopic, it.topicPath) }
                receivedTopics[receivedTopic] = topics
                topics.forEach(::publish)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        logger.fine { "Produce External: $topic" }
        val promise = Promise.promise<Boolean>()
        val (path, message) = encodeMessage(topic.browsePath, TopicValue(value.toString()))
        client?.publish(path.toString(), message, MqttQoS.valueOf(qos), false, retained)?.onComplete {
            promise.complete(true)
        }

        return promise.future()
    }

    override fun publishTopic(topic: Topic, value: TopicValue): Future<Boolean> {
        logger.fine { "Produce External: $topic" }
        val promise = Promise.promise<Boolean>()
        val (path, message) = encodeMessage(topic.browsePath, value)
        client?.publish(path.toString(), message, MqttQoS.valueOf(qos), false, retained)?.onComplete {
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
    private fun encodeRawMessage(path: BrowsePath, value: TopicValue): Pair<BrowsePath, Buffer> =
        path to Buffer.buffer(value.valueAsString())

    private fun encodeDefaultJsonMessage(path: BrowsePath, value: TopicValue): Pair<BrowsePath, Buffer> =
        path to value.encodeToJson().toBuffer()

    private fun encodeCustomJsonMessage(path: BrowsePath, value: TopicValue): Pair<BrowsePath, Buffer> {
        val json = JsonObject()
        setJsonValueByPath(json, formatJsonValuePath, value.value)
        formatJsonTimestampMsPath?.let {
            setJsonValueByPath(json, it, value.sourceTimeMs())
        }
        formatJsonTimestampIsoPath?.let {
            setJsonValueByPath(json, it, value.sourceTimeAsISO())
        }
        return path to json.toBuffer()
    }

    private fun setJsonValueByPath(json: JsonObject, jsonPath: String, value: Any?) {
        val parts = jsonPath.replace("[", ".[").replace("]", "").split(".")
        parts.fold(json) { current, item ->
            if (item == parts.last()) {
                current.put(item, value)
            }
            else if (current.containsKey(item)) {
                current[item]
            }
            else {
                val next = JsonObject()
                current.put(item, next)
            }
        }
    }


    private var spbSequenceNumber = 0

    private fun metricDataTypeOf(value: Any?): MetricDataType {
        return when (value) {
            is Byte -> MetricDataType.Int8
            is Short -> MetricDataType.Int16
            is Int -> MetricDataType.Int32
            is Long -> MetricDataType.Int64
            is UByte -> MetricDataType.UInt8
            is UShort -> MetricDataType.UInt16
            is UInt -> MetricDataType.UInt32
            is ULong -> MetricDataType.UInt64
            is Float -> MetricDataType.Float
            is Double -> MetricDataType.Double
            is Boolean -> MetricDataType.Boolean
            is String -> MetricDataType.String
            is Date -> MetricDataType.DateTime
            is Buffer -> MetricDataType.Bytes
            is ByteArray -> MetricDataType.Bytes
            else -> MetricDataType.String
        }
    }

    private fun encodeSparkplugBMessage(path: BrowsePath, value: TopicValue) : Pair<BrowsePath, Buffer> {
        val payload = SparkplugBPayload.SparkplugBPayloadBuilder(spbSequenceNumber.toLong())
            .setTimestamp(Date())
            .setUuid(UUID.randomUUID().toString())
            .createPayload()
        val type = metricDataTypeOf(value.value)
        val data = if (type == MetricDataType.String) value.valueAsString() else value.value
        val metric = Metric.MetricBuilder(path.getMetric(), type, data)
        payload.addMetric(metric.createMetric())
        if (spbSequenceNumber++ == 255) spbSequenceNumber=0
        return BrowsePath(path.toList().dropLast(1)) to Buffer.buffer(SparkplugBPayloadEncoder().getBytes(payload, false))
    }

    override fun writeHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId") // In MQTT the NodeId is a full MQTT topic path

        fun publish(client: MqttClient, inputPath: BrowsePath, value: String): Future<Int> {
            val (outputPath, payload) = encodeMessage(inputPath, TopicValue(value))
            return client.publish(outputPath.toString(), payload, MqttQoS.valueOf(qos), false, retained)
        }

        when {
            node != null && node is String -> {
                val value = message.body().getString("Value", "")
                client?.let { client ->
                    publish(client, BrowsePath(node), value).onComplete {
                        message.reply(JsonObject().put("Ok", true))
                    }
                } ?: run {
                    message.reply(JsonObject().put("Ok", false))
                }
            }
            node != null && node is JsonArray -> {
                val values = message.body().getJsonArray("Value", JsonArray())
                client?.let { client ->
                    Future.all(node.zip(values).mapNotNull {
                        if (it.first is String && it.second is String) {
                            publish(client, BrowsePath(it.first as String), it.second as String)
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

    override fun getComponentGroup(): ComponentGroup {
        return ComponentGroup.Driver
    }
}