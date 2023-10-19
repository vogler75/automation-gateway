package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.Topic.Format
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.AbstractVerticle
import io.vertx.core.CompositeFuture
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttEndpoint
import io.vertx.mqtt.MqttServer
import io.vertx.mqtt.MqttServerOptions
import io.vertx.mqtt.MqttTopicSubscription
import io.vertx.mqtt.messages.MqttPublishMessage
import io.vertx.mqtt.messages.MqttSubscribeMessage
import io.vertx.mqtt.messages.MqttUnsubscribeMessage

import java.util.logging.Level
import java.util.logging.Logger

class MqttServer(config: JsonObject, private val endpoint: MqttEndpoint) : AbstractVerticle() {
    companion object {
        fun create(vertx: Vertx, config: JsonObject) {
            val logger = Logger.getLogger(MqttServer::class.java.simpleName)

            val host = config.getString("Host", "0.0.0.0")
            val port = config.getInteger("Port", 1883)
            val ws = config.getBoolean("Websocket", false)
            val username = config.getString("Username", "")
            val password = config.getString("Password", "")
            val maxMessageSize = config.getInteger("MaxMessageSizeKb", 8) * 1024

            val options = MqttServerOptions()
                .setPort(port)
                .setHost(host)
                .setMaxMessageSize(maxMessageSize)
                .setUseWebSocket(ws)

            val server = MqttServer.create(vertx, options)

            // Start a verticle for every incoming connection
            server.endpointHandler {
                try {
                    val authUsername = it.auth()?.username ?: ""
                    val authPassword = it.auth()?.password ?: ""
                    if ((username == "" || username == authUsername) &&
                        (password == "" || password == authPassword))
                        vertx.deployVerticle(MqttServer(config, it))
                    else
                        logger.warning("Unauthorized access! [${authUsername}] [${authPassword}]")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            server.listen { result ->
                if (result.succeeded()) {
                    logger.info("MQTT server started and listening on port " + server.actualPort() + " "+(if (ws) "Websocket" else "")+" MaxMessageSize: "+maxMessageSize)
                } else {
                    logger.severe("MQTT server error on start" + result.cause().message)
                }
            }
        }
    }

    private val id = config.getString("Id", "Mqtt")
    private val logger = Logger.getLogger(id)

    private val topicConsumer = mutableMapOf<String, MessageConsumer<*>>()

    private var unsubscribeAllDone = false

    init {
        logger.level = Level.parse(config.getString("LogLevel", "INFO"))
    }

    override fun start(startPromise: Promise<Void>) {
        try {
            startEndpoint()
            startPromise.complete()
        } catch (e: Exception) {
            startPromise.fail(e)
        }
    }

    override fun stop(stopPromise: Promise<Void>) {
        logger.info("Client stop [${endpoint.clientIdentifier()}]")
        endpoint.close()
        stopPromise.complete()
    }

    private fun startEndpoint() {
        logger.info("Client [${endpoint.clientIdentifier()}] request to connect, clean session [${endpoint.isCleanSession}]")
        try {
            if (endpoint.will() != null && endpoint.will().isWillFlag) {
                logger.info(
                    "  last will: " +
                            "  topic = " + endpoint.will().willTopic +
                            "  msg = " + endpoint.will().willMessageBytes +
                            "  QoS = " + endpoint.will().willQos +
                            "  isRetain = " + endpoint.will().isWillRetain
                )
            }

            // handling publish messages
            endpoint.publishHandler(::publishHandler)

            // handling requests for subscriptions
            endpoint.subscribeHandler(::subscribeHandler)
            endpoint.unsubscribeHandler(::unsubscribeHandler)

            // handling disconnects from clients
            endpoint.disconnectHandler {
                logger.info("Client disconnect [${endpoint.clientIdentifier()}]")
                unsubscribeAll()
                vertx.undeploy(this.deploymentID())
            }

            endpoint.closeHandler {
                logger.info("Client close [${endpoint.clientIdentifier()}]")
                unsubscribeAll()
                vertx.undeploy(this.deploymentID())
            }

            endpoint.pingHandler {
                //logger.info("pingHandler")
            }

            endpoint.accept(false) // false .. no previous session present
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized private fun unsubscribeAll() {
        if (!unsubscribeAllDone) {
            logger.info("Unsubscribe all [${this.topicConsumer.keys.size}]")
            unsubscribeAllDone = true
            unsubscribeTopic(this.topicConsumer.keys.toList())
        }
    }

    private fun subscribeHandler(message: MqttSubscribeMessage) {
        try {
            val xs = message.topicSubscriptions().map {
                subscribeTopic(it).future()
            }
            CompositeFuture.all(xs).onComplete {
                // ack the subscriptions request
                val grantedQosLevels = xs.map {
                    if (it.succeeded() && it.result()) MqttQoS.AT_MOST_ONCE else MqttQoS.FAILURE
                }

                endpoint.subscribeAcknowledge(message.messageId(), grantedQosLevels)

                // specifying handlers for handling QoS 1 and 2
                /*
                endpoint.publishAcknowledgeHandler(messageId -> {
                    System.out.println("publishAcknowledgeHandler = " + messageId);
                });

                endpoint.publishReceivedHandler(messageId -> {
                    System.out.println("publishReceivedHandler = " + messageId);
                    endpoint.publishRelease(messageId);
                });

                endpoint.publishCompletionHandler(messageId -> {
                    System.out.println("publishCompletionHandler = " + messageId);
                });
                */
            }
        } catch (e: Exception) {
            logger.severe(e.stackTraceToString())
        }
    }

    private fun unsubscribeHandler(message: MqttUnsubscribeMessage) {
        logger.info("Unsubscribe Handler [${message.topics().size}]")
        unsubscribeTopic(message.topics())
    }

    private fun subscribeTopic(mqttTopicSubscription: MqttTopicSubscription): Promise<Boolean> {
        val ret = Promise.promise<Boolean>()
        if (!topicConsumer.contains(mqttTopicSubscription.topicName())) {
            val t = Topic.parseTopic(mqttTopicSubscription.topicName())
            val qos = mqttTopicSubscription.qualityOfService()
            logger.finest { "Subscribe request [${t}] with QoS [${qos}]" }
            when (t.systemType) {
                Topic.SystemType.Mqtt,
                Topic.SystemType.Opc,
                Topic.SystemType.Plc -> subscribeDriverTopic(t.systemType.name, t, qos, ret)
                Topic.SystemType.Unknown -> subscribeMqttTopic(t, qos, ret)
                else -> {
                    logger.warning("Invalid topic [${t}]")
                    ret.complete(false)
                }
            }
        } else {
            logger.warning("Client [${endpoint.clientIdentifier()}] is already subscribed to topic [${mqttTopicSubscription.topicName()}]!")
            ret.complete(true)
        }
        return ret
    }

    private fun unsubscribeTopic(topics: List<String>) {
        val opc = HashMap<String, ArrayList<Topic>>()
        val plc = HashMap<String, ArrayList<Topic>>()
        val mqtt = HashMap<String, ArrayList<Topic>>()

        fun add(list: HashMap<String, ArrayList<Topic>>, topic: Topic) {
            val l = list.getOrDefault(topic.systemName, ArrayList())
            if (l.size == 0) list[topic.systemName] = l
            l.add(topic)
        }

        topics.forEach { topic ->
            if (topics.contains(topic)) {
                val t = Topic.parseTopic(topic)
                logger.finest { "Unsubscribe request [${t}]" }
                when (t.systemType) {
                    Topic.SystemType.Mqtt -> add(mqtt, t)
                    Topic.SystemType.Opc -> add(opc, t)
                    Topic.SystemType.Plc -> add(plc, t)
                    Topic.SystemType.Unknown -> unsubscribeMqttTopic(t)
                    else -> {
                        logger.severe("Invalid topic [${t}]")
                    }
                }
            } else {
                logger.warning("Client [${endpoint.clientIdentifier()}] not subscribed to [${topic}]")
            }
        }

        opc.forEach { if (it.value.size > 0) unsubscribeDriverTopics(Topic.SystemType.Opc.name, it.key, it.value) }
        plc.forEach { if (it.value.size > 0) unsubscribeDriverTopics(Topic.SystemType.Plc.name, it.key, it.value) }
        mqtt.forEach { if (it.value.size > 0) unsubscribeDriverTopics(Topic.SystemType.Mqtt.name, it.key, it.value) }
    }

    private fun subscribeMqttTopic(t: Topic, qos: MqttQoS, ret: Promise<Boolean>) {
        logger.finest { "Register [${t.topicName}]" }
        val consumer = vertx.eventBus().consumer(t.topicName) {
            valueConsumerMqttTopic(t, qos, it.body())
        }
        this.topicConsumer[t.topicName] = consumer as MessageConsumer<*>
        ret.complete(true)
    }

    private fun unsubscribeMqttTopic(t: Topic) {
        logger.finest { "Unregister [${t.topicName}]" }
        this.topicConsumer[t.topicName]?.unregister()
        this.topicConsumer.remove(t.topicName)
    }

    private fun subscribeDriverTopic(type: String, t: Topic, qos: MqttQoS, ret: Promise<Boolean>) {
        val consumer = vertx.eventBus().consumer(t.topicName) {
            valueConsumer(t, qos, it.body())
        }
        val r = JsonObject().put("ClientId", endpoint.clientIdentifier()).put("Topic", t.encodeToJson())
        vertx.eventBus().request<JsonObject>("${type}/${t.systemName}/Subscribe", r) {
            logger.finest { "Subscribe response [${it.succeeded()}] [${it.result()?.body()}]" }
            if (it.succeeded() && it.result().body().getBoolean("Ok")) {
                this.topicConsumer[t.topicName] = consumer as MessageConsumer<*>
                ret.complete(true)
            } else {
                consumer.unregister()
                ret.complete(false)
            }
        }
    }

    private fun unsubscribeDriverTopics(type: String, systemName: String, list: List<Topic>) {
        val r = JsonObject().put("ClientId", endpoint.clientIdentifier())
        r.put("Topics", JsonArray(list.map { it.encodeToJson() }))
        vertx.eventBus().request<JsonObject>("${type}/${systemName}/Unsubscribe", r) {
            logger.finest { "Unsubscribe response [${it.succeeded()}] [${it.result()?.body()}]" }
            if (it.succeeded() && it.result().body().getBoolean("Ok")) {
                list.forEach { topic ->
                    this.topicConsumer[topic.topicName]?.unregister()
                    this.topicConsumer.remove(topic.topicName)
                }
            }
        }
    }

    private fun valueConsumer(topic: Topic, qos: MqttQoS, value: Any) {
        when (value) {
            is DataPoint -> valueConsumerDataPoint(topic, qos, value)
            else -> throw Exception("Unhandled message format!")
        }
    }

    private fun valueConsumerDataPoint(topic: Topic, qos: MqttQoS, value: DataPoint) {
        try {
            logger.finest { "Publish [${value.topic.topicWithBrowsePath}]" }
            if (endpoint.isConnected) {
                when (topic.format) {
                    Format.Json -> {
                        val payload = Buffer.buffer(value.encodeToJson().toString())
                        endpoint.publish(value.topic.topicWithBrowsePath, payload, qos, false /*isDup*/, false /* isRetain */)
                    }
                    Format.Value -> {
                        val payload = Buffer.buffer(value.value.valueAsString())
                        endpoint.publish(value.topic.topicWithBrowsePath, payload, qos, false /*isDup*/, false /* isRetain */)
                    }
                }
            } else {
                logger.warning("Publish topic [${topic}] to client [${endpoint.clientIdentifier()}] which is not connected anymore!")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun valueConsumerMqttTopic(topic: Topic, qos: MqttQoS, value: Any) {
        try {
            logger.finest { "Publish [${value}]" }
            if (endpoint.isConnected) {
                fun publish(buffer: Buffer) = endpoint.publish(
                    topic.topicName,
                    buffer,
                    qos,
                    false /*isDup*/,
                    false /* isRetain */
                )
                when (value) {
                    is Buffer -> publish(value)
                    is String -> publish(Buffer.buffer(value))
                    is JsonObject -> publish(value.toBuffer())
                    is JsonArray -> publish(value.toBuffer())
                    else -> logger.warning("Got unhandled class of instance [${value.javaClass.simpleName}]")
                }
            } else {
                logger.warning("Publish topic [${topic}] to client [${endpoint.clientIdentifier()}] which is not connected anymore!")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun publishHandler(message: MqttPublishMessage) {
        try {
            logger.finest { "Publish message [${message.topicName()}]" }
            val topic = Topic.parseTopic(message.topicName())
            val value = message.payload()
            when (topic.systemType) {
                Topic.SystemType.Mqtt,
                Topic.SystemType.Opc,
                Topic.SystemType.Plc -> {
                    val request = JsonObject()
                    val type = topic.systemType.name
                    request.put("Topic", topic.encodeToJson())
                    request.put("Data", value)
                    vertx.eventBus().request<JsonObject>("${type}/${topic.systemName}/Publish", request) {
                        logger.finest { "Publish response [${it.succeeded()}] [${it.result()?.body()}]" }
                    }
                }
                Topic.SystemType.Unknown -> {
                    vertx.eventBus().publish(topic.topicName, value)
                }
                else -> logger.severe("Unhandled system type [${topic}]")
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }
}
