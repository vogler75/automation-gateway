package at.rocworks.gateway.core.mqtt

import at.rocworks.gateway.core.logger.LoggerBase
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.mqtt.messages.MqttPublishMessage
import java.util.concurrent.TimeUnit

class MqttLogger (config: JsonObject) : LoggerBase(config) {
    var client: MqttClient? = null

    private val port: Int = config.getInteger("Port", 1883)
    private val host: String = config.getString("Host", "localhost")
    private val username: String? = config.getString("Username")
    private val password: String? = config.getString("Password")
    private val ssl: Boolean? = config.getBoolean("Ssl")
    private val qos: Int = config.getInteger("Qos", 0)

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        val options: MqttClientOptions = MqttClientOptions()
        options.isCleanSession = true
        username?.let { options.username = it }
        password?.let { options.password = it }
        ssl?.let { options.setSsl(it) }
        client = MqttClient.create(vertx, options)

        client?.publishCompletionHandler(Handler { id: Int -> println("Id of just received PUBACK or PUBCOMP packet is $id") })
        client?.connect(port, host) {
            logger.info("Mqtt client connect [${it.succeeded()}] [${it.result().code()}]")
            if (it.succeeded()) {
                promise.complete()
            }
            else promise.fail("Connect failed!")
        } ?: promise.fail("Client is null!")
        return promise.future()
    }

    override fun close() {
        client?.disconnect {
            logger.info("Mqtt client disconnect [${it.succeeded()}]")
        }
    }

    override fun writeExecutor() {
        var counter = 0
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null) {
            try {
                client?.publish(
                    point.topic.systemBrowsePath(),
                    point.value.encodeToJson().toBuffer(),
                    MqttQoS.valueOf(qos), false, false
                )
            } catch (e: Exception) {
                e.printStackTrace()
                logger.severe(e.message)
            }
            point = if (++counter < writeParameterBlockSize) writeValueQueue.poll() else null
        }
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