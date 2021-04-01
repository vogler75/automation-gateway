package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.Topic
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
import org.slf4j.LoggerFactory
import java.util.logging.Level
import java.util.logging.Logger

class MqttVerticle(config: JsonObject, private val endpoint: MqttEndpoint) : AbstractVerticle() {
    companion object {
        fun create(vertx: Vertx, config: JsonObject) {
            val logger = LoggerFactory.getLogger(MqttVerticle::class.java.simpleName)

            val options = MqttServerOptions()
                .setPort(config.getInteger("Port", 1883))
                .setHost(config.getString("Host", "0.0.0.0"))

            val server = MqttServer.create(vertx, options)

            // Start a verticle for every incoming connection
            server.endpointHandler {
                vertx.deployVerticle(MqttVerticle(config, it))
            }

            server.listen { result ->
                if (result.succeeded()) {
                    logger.info("MQTT server started and listening on port " + server.actualPort())
                } else {
                    logger.error("MQTT server error on start" + result.cause().message)
                }
            }
        }
    }

    private val id = config.getString("Id", "Mqtt")
    private val logger = LoggerFactory.getLogger(id)

    private val topics = mutableMapOf<String, MessageConsumer<*>>()

    private var unsubscribeAllDone = false

    init {
        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))
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
        logger.info("Client stop [{}]", endpoint.clientIdentifier())
        endpoint.close()
        stopPromise.complete()
    }

    private fun startEndpoint() {
        logger.info("Client [{}] request to connect, clean session [{}] ", endpoint.clientIdentifier() , endpoint.isCleanSession)
        try {
            if (endpoint.auth() != null) {
                logger.info("  auth: username = " + endpoint.auth().username + ", password = " + endpoint.auth().password)
            }

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
                logger.info("Client disconnect [{}]", endpoint.clientIdentifier())
                unsubscribeAll()
                vertx.undeploy(this.deploymentID())
            }

            endpoint.closeHandler {
                logger.info("Client close [{}]", endpoint.clientIdentifier())
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
            logger.info("Unsubscribe all [{}]", this.topics.keys.size)
            unsubscribeAllDone = true
            unsubscribeTopic(this.topics.keys.toList())
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
            logger.error(e.stackTraceToString())
        }
    }

    private fun unsubscribeHandler(message: MqttUnsubscribeMessage) {
        unsubscribeTopic(message.topics())
    }

    private fun subscribeTopic(mqttTopicSubscription: MqttTopicSubscription): Promise<Boolean> {
        val ret = Promise.promise<Boolean>()
        if (!topics.contains(mqttTopicSubscription.topicName())) {
            val t = Topic.parseTopic(mqttTopicSubscription.topicName())
            val qos = mqttTopicSubscription.qualityOfService()
            logger.debug("Subscribe request [{}] with QoS [{}]", t, qos)
            when (t.systemType) {
                Topic.SystemType.Mqtt ->  subscribeMqttTopic(t, qos, ret)
                Topic.SystemType.Opc,
                Topic.SystemType.Plc,
                Topic.SystemType.Dds -> subscribeDriverTopic(t.systemType.name, t, qos, ret)
                else -> {
                    logger.warn("Invalid topic []", t)
                    ret.complete(false)
                }
            }
        } else {
            logger.warn(
                "Client [{}] is already subscribed to topic [{}]!",
                endpoint.clientIdentifier(),
                mqttTopicSubscription.topicName()
            )
            ret.complete(true)
        }
        return ret
    }

    private fun unsubscribeTopic(topics: List<String>) {
        val opc = HashMap<String, ArrayList<Topic>>()
        val plc = HashMap<String, ArrayList<Topic>>()
        val dds = HashMap<String, ArrayList<Topic>>()

        fun add(list: HashMap<String, ArrayList<Topic>>, topic: Topic) {
            val l = list.getOrDefault(topic.systemName, ArrayList())
            if (l.size == 0) list[topic.systemName] = l
            l.add(topic)
        }

        topics.forEach { topic ->
            if (topics.contains(topic)) {
                val t = Topic.parseTopic(topic)
                logger.debug("Unsubscribe request [{}]", t)
                when (t.systemType) {
                    Topic.SystemType.Mqtt -> unsubscribeMqttTopic(t)
                    Topic.SystemType.Opc -> add(opc, t)
                    Topic.SystemType.Plc -> add(plc, t)
                    Topic.SystemType.Dds -> add(dds, t)
                    else -> {
                        logger.warn("Invalid topic [{}]", t)
                    }
                }
            } else {
                logger.warn("Client [{}] not subscribed to [{}]", endpoint.clientIdentifier(), topic)
            }
        }

        opc.forEach { if (it.value.size > 0) unsubscribeDriverTopics(Topic.SystemType.Opc.name, it.key, it.value) }
        plc.forEach { if (it.value.size > 0) unsubscribeDriverTopics(Topic.SystemType.Plc.name, it.key, it.value) }
        dds.forEach { if (it.value.size > 0) unsubscribeDriverTopics(Topic.SystemType.Dds.name, it.key, it.value) }
    }

    private fun subscribeMqttTopic(t: Topic, qos: MqttQoS, ret: Promise<Boolean>) {
        val consumer = vertx.eventBus().consumer<Buffer>(t.topicName) {
            valueConsumer(t, qos, it.body())
        }
        this.topics[t.topicName] = consumer as MessageConsumer<*>
        ret.complete(true)
    }

    private fun unsubscribeMqttTopic(t: Topic) {
        this.topics[t.topicName]?.unregister()
        this.topics.remove(t.topicName)
    }

    private fun subscribeDriverTopic(type: String, t: Topic, qos: MqttQoS, ret: Promise<Boolean>) {
        val consumer = vertx.eventBus().consumer<Any>(t.topicName) { valueConsumer(t, qos, it.body()) }
        val r = JsonObject().put("ClientId", endpoint.clientIdentifier()).put("Topic", t.encodeToJson())
        vertx.eventBus().request<JsonObject>("${type}/${t.systemName}/Subscribe", r) {
            logger.debug("Subscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
            if (it.succeeded() && it.result().body().getBoolean("Ok")) {
                this.topics[t.topicName] = consumer as MessageConsumer<Any>
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
            logger.debug("Unsubscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
            if (it.succeeded() && it.result().body().getBoolean("Ok")) {
                list.forEach { topic ->
                    this.topics[topic.topicName]?.unregister()
                    this.topics.remove(topic.topicName)
                }
            }
        }
    }

    private fun valueConsumer(topic: Topic, qos: MqttQoS, value: Any) {
        when (value) {
            is Buffer -> valueConsumerBuffer(topic, qos, value)
            is String -> valueConsumerBuffer(topic, qos, Buffer.buffer(value))
            is JsonObject -> valueConsumerBuffer(topic, qos, value.toBuffer())
            is JsonArray -> valueConsumerBuffer(topic, qos, value.toBuffer())
            else -> logger.warn("Got unhandled class of instance []", value.javaClass.simpleName)
        }
    }

    private fun valueConsumerBuffer(topic: Topic, qos: MqttQoS, value: Buffer) {
        try {
            if (endpoint.isConnected) {
                endpoint.publish(topic.topicName, value, qos, false /*isDup*/, false /* isRetain */)
            } else {
                logger.warn("Publish topic [{}] to client [{}] which is not connected anymore!", topic, endpoint.clientIdentifier())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun publishHandler(message: MqttPublishMessage) {
        try {
            logger.debug("Publish message [{}]", message.topicName())
            val topic = Topic.parseTopic(message.topicName())
            val value = message.payload()
            if (topic.isValid()) {
                when (topic.systemType) {
                    Topic.SystemType.Mqtt -> {
                        vertx.eventBus().publish(topic.topicName, value)
                    }
                    Topic.SystemType.Opc,
                    Topic.SystemType.Plc,
                    Topic.SystemType.Dds -> {
                        val request = JsonObject()
                        val type = topic.systemType.name
                        request.put("Topic", topic.encodeToJson())
                        request.put("Data", value)
                        vertx.eventBus().request<JsonObject>("${type}/${topic.systemName}/Publish", request) {
                            logger.debug("Publish response [{}] [{}]", it.succeeded(), it.result()?.body())
                        }
                    }
                    else -> logger.warn("Unhandled system type [{}]", topic)
                }
            } else {
                logger.warn("Publish invalid topic []", topic)
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }
}
