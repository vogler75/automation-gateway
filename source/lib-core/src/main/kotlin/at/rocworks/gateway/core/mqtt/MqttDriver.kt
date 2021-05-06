package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import groovy.lang.Binding
import groovy.lang.GroovyShell
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.mqtt.messages.MqttPublishMessage
import java.nio.charset.Charset

class MqttDriver(val config: JsonObject) : DriverBase(config) {
    override fun getType() = Topic.SystemType.Mqtt

    var client: MqttClient? = null

    private val port: Int = config.getInteger("Port", 1883)
    private val host: String = config.getString("Host", "localhost")
    private val username: String? = config.getString("Username")
    private val password: String? = config.getString("Password")
    private val ssl: Boolean? = config.getBoolean("Ssl")
    private val qos: Int = config.getInteger("Qos", 0)

    private val valueType: String
    private val valueScript: String

    private val subscribedTopics =  HashMap<String, Topic>() // Subscribed (maybe with wildcards) topics to Topic
    private val receivedTopics = HashMap<String, Topic>()  // Received (no wildcards) to Subscribed (may have wildcards) Topics

    init {
        val value = config.getJsonObject("Value", JsonObject())
        valueType = value.getString("Type", "")
        valueScript = value.getString("Script", "")
        logger.info("Value is of type $valueType with script $valueScript")
    }

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

    private val sharedData = Binding()
    private val groovyShell = GroovyShell(sharedData)

    private fun transformValue(value: Buffer): String {
        val script = when (valueType) {
            "JSON" -> {
                if (valueScript.isNotEmpty()) {
                    """ 
                    import groovy.json.JsonSlurper
                    import groovy.json.JsonOutput
                    import java.time.*
                    def output(source) {
                      $valueScript
                    }
                    def input = new JsonSlurper().parseText(value)
                    return JsonOutput.toJson(output(input))
                    """.trimIndent()
                } else {
                    "return value"
                }
            }
            else -> null
        }
        return if (script != null) {
            sharedData.setProperty("value", value.toString(Charset.defaultCharset()))
            groovyShell.evaluate(script).toString()
        } else {
            value.toString(Charset.defaultCharset())
        }
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
        return actualTopic.matches(regex.toRegex())
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
        logger.debug("Got value [{}] [{}]", message.topicName(), message.payload())
        try {
            val receivedTopic = message.topicName()
            val payload : Buffer = message.payload()

            fun json(topic: Topic) = JsonObject()
                .put("Topic", topic.encodeToJson())
                .put("Value", Json.decodeValue(transformValue(payload)))

            fun publish(topic: Topic) {
                topic.browsePath = message.topicName() // TODO: not good, should be immutable
                val buffer : Buffer? = when (topic.format) {
                    Topic.Format.Value -> payload
                    Topic.Format.Json -> Buffer.buffer(json(topic).encode())
                    Topic.Format.Pretty -> Buffer.buffer(json(topic).encodePrettily())
                }
                vertx.eventBus().publish(topic.topicName, buffer)
            }

            receivedTopics[receivedTopic]?.let {
                publish(it)
            } ?: subscribedTopics.filter { subscribedTopic ->
                compareTopic(receivedTopic, subscribedTopic.key)
            }.forEach { subscribedTopic ->
                receivedTopics[receivedTopic] = subscribedTopic.value
                publish(subscribedTopic.value)
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
            subscribedTopics.remove(address)?.let { subscribedTopic ->
                receivedTopics.filter { receivedTopic ->
                    receivedTopic.value.topicName == subscribedTopic.topicName
                }.forEach {
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