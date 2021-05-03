package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.mqtt.messages.MqttPublishMessage

class MqttDriver(val config: JsonObject) : DriverBase(config) {
    override fun getType() = Topic.SystemType.Mqtt

    var client: MqttClient? = null

    private val port: Int = config.getInteger("Port", 1883)
    private val host: String = config.getString("Host", "localhost")
    private val username: String? = config.getString("Username")
    private val password: String? = config.getString("Password")
    private val ssl: Boolean? = config.getBoolean("Ssl")
    private val qos: Int = config.getInteger("Qos", 0)

    private val subscribedTopics =  HashMap<String, Topic>() // Subscribed (maybe with wildcards) topics to Topic
    private val receivedTopics = HashMap<String, String>()  // Received (no wildcards) to Subscribed (may have wildcards) Topics

    override fun connect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val options: MqttClientOptions = MqttClientOptions()
        options.isCleanSession = true
        username?.let { options.username = it }
        password?.let { options.password = it }
        ssl?.let { options.setSsl(it) }
        client = MqttClient.create(vertx, options)
        client?.publishHandler(::valueConsumer)
        client?.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.result().code()}]")
            promise.complete(it.succeeded())
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

    private fun compareTopic(actualTopic: String, subscribedTopic: String): Boolean {
        val regex = subscribedTopic.replace("+", "[^/]+").replace("#", ".+")
        val match = actualTopic.matches(regex.toRegex())
        println("ActualTopic $actualTopic SubscribedTopic: $subscribedTopic Regex: $regex Match: $match")
        return match
    }

    override fun subscribeTopics(topics: List<Topic>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        if (topics.isEmpty()) promise.complete(true)
        else {
            logger.info("Subscribe to [{}] topics", topics.size)
            topics.forEach { topic ->
                logger.info("Subscribe topic [{}]", topic.topicName)
                client?.subscribe(topic.address, qos)
                registry.addMonitoredItem(MqttMonitoredItem(topic.address), topic)
                subscribedTopics[topic.address] = topic
            }
            promise.complete(true)
        }
        return promise.future()
    }

    private fun valueConsumer(message: MqttPublishMessage) {
        logger.info("Got value [{}] [{}]", message.topicName(), message.payload())
        try {
            val topic = message.topicName()
            val data = message.payload()
            receivedTopics[topic]?.let {
                vertx.eventBus().publish(it, data)
            } ?: subscribedTopics.filter { subscribedTopic ->
                compareTopic(topic, subscribedTopic.key)
            }.forEach {
                receivedTopics[topic] = it.value.topicName
                vertx.eventBus().publish(it.value.topicName, data)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun unsubscribeItems(items: List<MonitoredItem>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val mqttItems = items.map { (it as MqttMonitoredItem).item }
        mqttItems.forEach { address ->
            logger.info("Unsubscribe topic [{}]", address)
            client?.unsubscribe(address) // TODO: Error handling?
            subscribedTopics.remove(address)?.let { topic ->
                receivedTopics.filter { it.value == topic.topicName }.forEach {
                    receivedTopics.remove(it.key)
                }
            }
        }
        promise.complete(true)
        return promise.future()
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        TODO("Not yet implemented")
    }

    override fun readServerInfo(): JsonObject {
        TODO("Not yet implemented")
    }

    override fun readHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

    override fun writeHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

    override fun browseHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

    override fun schemaHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }
}