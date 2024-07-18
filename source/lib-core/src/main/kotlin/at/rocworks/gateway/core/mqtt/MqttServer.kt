package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.Topic.Format
import at.rocworks.gateway.core.graphql.ConfigServer
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.Component
import at.rocworks.gateway.core.service.ComponentLogger
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.*
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

class MqttServer(config: JsonObject) : Component(config) {
    private val id = config.getString("Id", "")
    private val logger = ComponentLogger.getLogger(this::class.java.simpleName, id)

    private val host = config.getString("Host", "0.0.0.0")
    private val port = config.getInteger("Port", 1883)
    private val ws = config.getBoolean("Websocket", false)
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val maxMessageSize = config.getInteger("MaxMessageSizeKb", 8) * 1024

    private val options = MqttServerOptions()
        .setPort(port)
        .setHost(host)
        .setMaxMessageSize(maxMessageSize)
        .setUseWebSocket(ws)

    private lateinit var server : io.vertx.mqtt.MqttServer

    init {
        logger.level = Level.parse(config.getString("LogLevel", "INFO"))
    }

    override fun start(startPromise: Promise<Void>) {
        super.start()
        server = MqttServer.create(vertx, options)
        server.endpointHandler {
            try {
                val authUsername = it.auth()?.username ?: ""
                val authPassword = it.auth()?.password ?: ""
                if ((username == "" || username == authUsername) &&
                    (password == "" || password == authPassword))
                    vertx.deployVerticle(MqttServerEndpoint(logger, it))
                else
                    logger.warning("Unauthorized access! [${authUsername}] [${authPassword}]")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        server.listen { result ->
            if (result.succeeded()) {
                logger.info("MQTT server started and listening on port " + server.actualPort() + " "+(if (ws) "Websocket" else "")+" MaxMessageSize: "+maxMessageSize)
                startPromise.complete()
            } else {
                logger.severe("MQTT server error on start" + result.cause().message)
                startPromise.fail(result.cause())
            }
        }
    }

    override fun stop(stopPromise: Promise<Void>) {
        super.stop()
        server.close()
        stopPromise.complete()
    }

    override fun getComponentGroup() = ComponentGroup.Server

    override fun getComponentId(): String {
        return id
    }

    override fun getComponentConfig(): JsonObject {
        return this.config
    }
}
