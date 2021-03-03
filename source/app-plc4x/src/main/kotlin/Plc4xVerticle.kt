import at.rocworks.gateway.core.data.Globals
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.Value
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import io.vertx.core.CompositeFuture

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import org.apache.plc4x.java.api.PlcConnection
import org.apache.plc4x.java.PlcDriverManager
import org.apache.plc4x.java.api.exceptions.PlcConnectionException
import org.apache.plc4x.java.api.messages.*
import java.time.Instant

import java.util.concurrent.CompletableFuture


class Plc4xVerticle(config: JsonObject): DriverBase(config) {
    override fun getRootUri() = Globals.BUS_ROOT_URI_PLC

    private val url: String = config.getString("Url", "")
    private var plc: PlcConnection? = null

    override fun connect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            plc = PlcDriverManager().getConnection(url)
            plc!!.connect()
            promise.complete(true)
        } catch (e: PlcConnectionException) {
            logger.error(e.message)
            promise.complete(false)
        }

        if (plc != null) {
            val info = (if (plc?.metadata?.canRead() == true) "Read " else " ") +
                    (if (plc?.metadata?.canWrite() == true) "Write " else " ") +
                    if (plc?.metadata?.canSubscribe() == true) "Subscribe " else " "
            logger.error("This connection supports: {}", info)
        }
        return promise.future()
    }

    override fun disconnect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        if (plc != null && plc?.isConnected == true) {
            plc?.close()
            promise.complete(true)
        } else {
            promise.complete(false)
        }
        return promise.future()
    }

    override fun shutdown() {
        disconnect()
    }

    override fun subscribeTopics(topics: List<Topic>): Future<Boolean> {
        return subscribeNodes(topics.filter { it.topicType === Topic.TopicType.NodeId })
    }

    private fun subscribeNodes(topics: List<Topic>) : Future<Boolean> {
        logger.info("Subscribe nodes [{}]", topics.size)
        val ret = Promise.promise<Boolean>()
        when {
            plc?.metadata?.canSubscribe() == false -> ret.complete(false)
            topics.isEmpty() -> ret.complete(true)
            else -> {
                val builder: PlcSubscriptionRequest.Builder = plc!!.subscriptionRequestBuilder()
                topics.forEach {
                    builder.addEventField(it.payload, it.payload)
                }
                val request = builder.build()

                request.execute().whenComplete { subscribeResponse, throwable ->
                    if (subscribeResponse != null) {
                        topics.forEach { topic ->
                            val handle = subscribeResponse.getSubscriptionHandle(topic.payload)
                            registry.addMonitoredItem(Plc4xMonitoredItem(handle), topic)
                            handle.register {
                                valueConsumer(topic, it)
                            }
                        }
                    } else {
                        logger.error("An error occurred: " + throwable.message, throwable)
                        ret.complete(false)
                    }
                }
            }
        }
        return ret.future()
    }

    private fun toValue(v: PlcSubscriptionEvent): Value {
        return Value(
            value = v.asPlcValue.toString(),
            dataTypeId = 0,
            statusCode = 0,
            sourceTime = v.timestamp,
            serverTime = v.timestamp,
            sourcePicoseconds = 0,
            serverPicoseconds = 0,
        )
    }

    private fun valueConsumer(topic: Topic, data: PlcSubscriptionEvent) {
        logger.debug("Got value [{}] [{}]", topic.topicName, data.toString())
        try {
            fun json() = JsonObject()
                .put("Topic", topic.encodeToJson())
                .put("Value", toValue(data).encodeToJson())

            val buffer : Buffer? = when (topic.format) {
                Topic.Format.Value -> {
                    data.asPlcValue.toString().let {
                        Buffer.buffer(it)
                    }
                }
                Topic.Format.Json -> Buffer.buffer(json().encode())
                Topic.Format.Pretty -> Buffer.buffer(json().encodePrettily())
            }
            if (buffer!=null) vertx.eventBus().publish(topic.topicName, buffer)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    override fun unsubscribeItems(items: List<MonitoredItem>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val builder = plc!!.unsubscriptionRequestBuilder()
        items.filterIsInstance<Plc4xMonitoredItem>().forEach { item ->
            builder.addHandles(item.item)
        }
        val request = builder.build()
        request.execute().whenComplete { response, throwable ->
            if (response!=null) promise.complete(true)
            else {
                logger.error("An error occurred: " + throwable.message, throwable)
                promise.complete(false)
            }
        }
        return promise.future()
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            when (topic.topicType) {
                Topic.TopicType.NodeId -> {
                    when (topic.format) {
                        Topic.Format.Value -> {
                            writeValueAsync(topic.payload, value.toString()).onComplete(ret)
                        }
                        Topic.Format.Json,
                        Topic.Format.Pretty -> {
                            logger.warn("Value format not yet implemented!") // TODO
                        }
                    }
                }
                else -> {
                    logger.warn("Item type [{}] not yet implemented!", topic.topicType)
                }
            }
        } catch (e: Exception) {
            ret.fail(e)
        }
        return ret.future()    }

    override fun readServerInfo(): JsonObject {
        TODO("Not yet implemented")
    }

    override fun readHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        when {
            plc?.metadata?.canRead() == false -> {
                message.reply(JsonObject().put("Ok", false).put("Error", "Read not supported!"))
            }
            node != null && node is String -> {
                try {
                    val builder: PlcReadRequest.Builder = plc!!.readRequestBuilder()
                    builder.addItem(node, node)
                    val request = builder.build()
                    val response = request.execute()
                    response.whenComplete { readResponse, throwable ->
                        if (readResponse != null) {
                            val value = readResponse.getAllBytes(node)
                            val result = JsonObject().put("Value", value)
                            message.reply(JsonObject().put("Ok", true).put("Result", result))
                        } else {
                            logger.error("An error occurred: " + throwable.message, throwable)
                            val result = JsonObject().put("Value", throwable.message)
                            message.reply(JsonObject().put("Ok", false).put("Result", result))
                        }
                    }
                } catch (e: Exception)  {
                    val result = JsonObject().put("Value", e.message)
                    message.reply(JsonObject().put("Ok", false).put("Result", result))
                }
            }
            node != null && node is JsonArray -> {
                message.reply(JsonObject().put("Ok", false))
            }
            else -> {
                val err = String.format("Invalid format in read request!")
                message.reply(JsonObject().put("Ok", false))
                logger.error(err)
            }
        }
    }

    override fun writeHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        logger.info("writeHandler [{}]", node)
        when {
            plc?.metadata?.canWrite() == false -> {
                message.reply(JsonObject().put("Ok", false).put("Error", "Write not supported!"))
            }
            node != null && node is String -> {
                val value = message.body().getString("Value", "")
                writeValueAsync(node, value).onComplete {
                    message.reply(JsonObject().put("Ok", it.succeeded() && it.result()))
                }
            }
            node != null && node is JsonArray -> {
                logger.warn("Write of array not yet implemented!")
            }
            else -> {
                val err = String.format("Invalid format in write request!")
                message.reply(JsonObject().put("Ok", false))
                logger.error(err)
            }
        }
    }

    private fun writeValueAsync(node: String, value: String): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val builder: PlcWriteRequest.Builder = plc!!.writeRequestBuilder()
        builder.addItem(node, node, value)
        val request = builder.build()
        val response = request.execute()
        response.whenComplete { writeResponse, throwable ->
            if (writeResponse != null) {
                logger.info("Write response [{}]", writeResponse.getResponseCode("value"))
                promise.complete(true)
            } else {
                logger.error("Write error [{}]", throwable.message, throwable)
                promise.complete(false)
            }
        }
        return promise.future()
    }

    override fun browseHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }
}