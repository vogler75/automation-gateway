package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.EventBus
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttEndpoint
import io.vertx.mqtt.MqttTopicSubscription
import io.vertx.mqtt.messages.MqttPublishMessage
import io.vertx.mqtt.messages.MqttSubscribeMessage
import io.vertx.mqtt.messages.MqttUnsubscribeMessage
import java.util.logging.Logger

class MqttServerEndpoint(
    private val logger: Logger,
    private val endpoint: MqttEndpoint
): AbstractVerticle() {
    private val eventBus = EventBus(logger)

    private val topicConsumer = mutableMapOf<String, MessageConsumer<*>>()

    private var unsubscribeAllDone = false

    override fun start(startPromise: Promise<Void>) {
        logger.info("Client endpoint start [${endpoint.clientIdentifier()}]")
        super.start()
        try {
            startEndpoint()
            startPromise.complete()
        } catch (e: Exception) {
            startPromise.fail(e)
        }
    }

    override fun stop(stopPromise: Promise<Void>) {
        logger.info("Client endpoint stop [${endpoint.clientIdentifier()}]")
        super.stop()
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
            logger.fine { "Unsubscribe all [${this.topicConsumer.keys.size}]" }
            unsubscribeAllDone = true
            unsubscribeTopics(this.topicConsumer.keys.toList())
        }
    }

    private fun subscribeHandler(message: MqttSubscribeMessage) {
        try {
            val xs = message.topicSubscriptions().map {
                subscribeTopic(it).future()
            }
            Future.all(xs).onComplete {
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
        logger.fine { "Unsubscribe Handler [${message.topics().size}]" }
        unsubscribeTopics(message.topics())
    }

    private fun subscribeTopic(mqttTopicSubscription: MqttTopicSubscription): Promise<Boolean> {
        val ret = Promise.promise<Boolean>()
        if (!topicConsumer.contains(mqttTopicSubscription.topicName())) {
            val t = Topic.parseTopic(mqttTopicSubscription.topicName())
            val qos = mqttTopicSubscription.qualityOfService()
            logger.fine { "Subscribe request [${t}] with QoS [${qos}]" }
            when (t.systemType) {
                Topic.SystemType.Mqtt,
                Topic.SystemType.Opc,
                Topic.SystemType.Plc -> subscribeDriverTopic(t, qos, ret)
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

    private fun unsubscribeTopics(names: List<String>) {
        val topics = names.map { Topic.parseTopic(it) }
        topics.filter {
            it.systemType == Topic.SystemType.Mqtt ||
            it.systemType == Topic.SystemType.Plc ||
            it.systemType == Topic.SystemType.Opc
        }.let {
            eventBus.requestUnsubscribeTopics(vertx, endpoint.clientIdentifier(), it) { ok, list ->
                if (ok) {
                    list.forEach { topic ->
                        this.topicConsumer[topic.topicName]?.unregister()
                        this.topicConsumer.remove(topic.topicName)
                    }
                } else {
                    logger.warning("Unsubscribe failed!")
                }
            }
        }

        topics.filter { it.systemType == Topic.SystemType.Unknown }.forEach {
            unsubscribeMqttTopic(it)
        }
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

    private fun subscribeDriverTopic(topic: Topic, qos: MqttQoS, ret: Promise<Boolean>) {
        fun onComplete(ok: Boolean, consumer: MessageConsumer<DataPoint>) {
            if (ok) {
                this.topicConsumer[topic.topicName] = consumer
                ret.complete(true)
            } else {
                println("Nak")
                ret.complete(false)
            }
        }
        fun onMessage(topic: Topic, message: Message<DataPoint>) {
            valueConsumer(topic, qos, message.body())
        }
        eventBus.requestSubscribeTopic(
            vertx,
            endpoint.clientIdentifier(),
            topic,
            ::onComplete,
            ::onMessage
        )
    }

    private fun valueConsumer(topic: Topic, qos: MqttQoS, value: Any) {
        when (value) {
            is DataPoint -> valueConsumerDataPoint(topic, qos, value)
            else -> throw Exception("Unhandled message format!")
        }
    }

    private fun valueConsumerDataPoint(topic: Topic, qos: MqttQoS, value: DataPoint) {
        try {
            logger.finest { "Publish [${value.topic.topicNameAndPath}]" }
            if (endpoint.isConnected) {
                when (topic.format) {
                    Topic.Format.Json -> {
                        val payload = Buffer.buffer(value.encodeToJson().toString())
                        endpoint.publish(value.topic.topicNameAndPath, payload, qos, false /*isDup*/, false /* isRetain */)
                    }
                    Topic.Format.Value -> {
                        val payload = Buffer.buffer(value.value.valueAsString())
                        endpoint.publish(value.topic.topicNameAndPath, payload, qos, false /*isDup*/, false /* isRetain */)
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
            when (topic.systemType) {
                Topic.SystemType.Mqtt, Topic.SystemType.Opc, Topic.SystemType.Plc -> {
                    when (topic.format) {
                        Topic.Format.Value -> {
                            val value = message.payload()
                            eventBus.requestPublishTopicBuffer(vertx, topic, value)
                        }
                        Topic.Format.Json -> {
                            val value = TopicValue.decodeFromJson(message.payload().toJsonObject())
                            eventBus.requestPublishTopicValue(vertx, topic, value)
                        }
                    }
                }
                Topic.SystemType.Unknown -> {
                    val value = message.payload()
                    eventBus.publishBufferValue(vertx, topic.topicName, value)
                }
                else -> logger.severe("Unhandled system type [${topic}]")
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }
}