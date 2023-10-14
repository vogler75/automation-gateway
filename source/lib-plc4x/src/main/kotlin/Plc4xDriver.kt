import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValuePlc
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import org.apache.plc4x.java.api.PlcConnection
import org.apache.plc4x.java.PlcDriverManager
import org.apache.plc4x.java.api.messages.*
import org.apache.plc4x.java.api.value.PlcValue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class Plc4xDriver(config: JsonObject): DriverBase(config) {
    override fun getType() = Topic.SystemType.Plc

    private val url: String = config.getString("Url", "")
    private var plc: PlcConnection? = null

    private val pollingTime: Long
    private val pollingTimeout: Long
    private val pollingOldNew: Boolean
    private val writeTimeout: Long
    private val readTimeout: Long
    private val defaultRetryWaitTime = 5000

    private val pollingTopics = HashMap<Topic, PlcValue?>()

    init {
        val polling = config.getJsonObject("Polling", JsonObject())
        pollingTime = polling.getLong("Time", 0)
        pollingTimeout = polling.getLong("Timeout", pollingTime)
        pollingOldNew = polling.getBoolean("OldNew", false)
        writeTimeout = config.getLong("WriteTimeout", 100)
        readTimeout = config.getLong("ReadTimeout", 100)
    }

    override fun start(startPromise: Promise<Void>) {
        super.start(startPromise)
        if (pollingTime>0) {
            vertx.setPeriodic(pollingTime, ::pollingExecutor)
        }
    }

    private var pollingRequestId = 0
    private var pollingResponseId = 0

    @Suppress("UNUSED_PARAMETER")
    private fun pollingExecutor(id: Long) {
        if (isConnected() && pollingTopics.isNotEmpty()) {
            if (pollingRequestId > pollingResponseId) {
                logger.warning("Polling request id [${pollingRequestId}] is still pending (response id [${pollingResponseId})].")
            } else {
                val localRequestId = ++pollingRequestId
                val builder: PlcReadRequest.Builder = plc!!.readRequestBuilder()
                pollingTopics.forEach {
                    builder.addItem(it.key.topicName, it.key.node)
                }
                logger.finest("Poll request [${localRequestId}] for [${pollingTopics.size}] items...")
                val request = builder.build()
                val response = request.execute()
                response.orTimeout(pollingTimeout, TimeUnit.MILLISECONDS)
                response.whenComplete { readResponse, throwable ->
                    ++pollingResponseId
                    if (readResponse != null) {
                        logger.finest("Poll response [${readResponse.asPlcValue.toString()}]", )
                        pollingTopics.forEach { topic ->
                            val value = readResponse.getPlcValue(topic.key.topicName)
                            if (!pollingOldNew || value.toString() != topic.value.toString()) { // TODO: String Compare?
                                pollingTopics[topic.key] = value
                                valueConsumer(topic.key, value)
                            }
                        }
                    } else {
                        logger.severe("An error occurred: $throwable")
                    }
                }
            }
        }
    }

    override fun connect(): Future<Boolean> {
        return connectClientAsync()
    }

    private var reconnectInProgress = false
    private val connectionStateThread = thread {
        while (true) {
            Thread.sleep(1000)
            plc?.let { plc ->
                if (!plc.isConnected && !reconnectInProgress) {
                    reconnectInProgress = true
                    connectClientAsync().onComplete {
                        logger.info("Reconnect complete with result [${it.result()}]")
                        reconnectInProgress = false
                    }
                }
            }
        }
    }

    private fun connectPlc4xDriver() {
        plc = PlcDriverManager().getConnection(url)
        plc?.let {
            it.connect()
            val info = (if (it.metadata?.canRead() == true) "Read " else " ") +
                    (if (it.metadata?.canWrite() == true) "Write " else " ") +
                    if (it.metadata?.canSubscribe() == true) "Subscribe " else " "
            logger.severe("This connection supports: ${info}")
        }
    }

    private fun connectClientAsync(): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        connectClientThread(ret)
        return ret.future()
    }

    private fun connectClientThread(ret: Promise<Boolean>) {
        thread {
            try {
                connectPlc4xDriver()
                ret.complete(true)
            } catch (e: Exception) {
                logger.info("Plc4x connect failed! Wait and retry... " + e.message)
                vertx.setTimer(defaultRetryWaitTime.toLong()) { connectClientThread(ret) }
            } catch (e: Exception) {
                ret.fail(e)
            }
        }
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
        return subscribeTopic(topics.filter { it.topicType === Topic.TopicType.Node })
    }

    private fun subscribeTopic(topics: List<Topic>) : Future<Boolean> {
        logger.info("Subscribe topic [${topics.size}]", )
        val ret = Promise.promise<Boolean>()
        when {
            plc?.metadata?.canSubscribe() == false -> {
                topics.forEach { topic ->
                    if (!pollingTopics.containsKey(topic)) {
                        pollingTopics[topic] = null
                        registry.addMonitoredItem(Plc4xPolledItem(topic), topic)
                    }
                }
                ret.complete(true)
            }
            topics.isEmpty() -> ret.complete(true)
            else -> {
                val builder: PlcSubscriptionRequest.Builder = plc!!.subscriptionRequestBuilder()
                topics.forEach {
                    builder.addEventField("value", it.node)
                }
                val request = builder.build()

                request.execute().whenComplete { subscribeResponse, throwable ->
                    if (subscribeResponse != null) {
                        topics.forEach { topic ->
                            val handle = subscribeResponse.getSubscriptionHandle("value")
                            registry.addMonitoredItem(Plc4xMonitoredItem(handle), topic)
                            handle.register {
                                valueConsumer(topic, it.asPlcValue)
                            }
                        }
                    } else {
                        logger.severe("An error occurred: ${throwable.message}")
                        ret.complete(false)
                    }
                }
            }
        }
        return ret.future()
    }

    private fun toValue(node: String, value: PlcValue): TopicValuePlc {
        val data = when {
            value.isStruct && value.keys.isNotEmpty() -> {
                value.struct[value.keys.first()]?.`object`
            }
            value.isList && value.list.isNotEmpty() -> {
                value.getIndex(0).`object`
            }
            else -> value.`object`
        }
        return TopicValuePlc(value = data!!.toString())
    }

    private fun valueConsumer(topic: Topic, data: PlcValue) {
        logger.finest("Got value [${topic.topicName}] [${data.toString()}]")
        try {
            fun json() = JsonObject()
                .put("Topic", topic.encodeToJson())
                .put("Value", toValue(topic.node, data).encodeToJson())

            val buffer : Buffer? = when (topic.format) {
                Topic.Format.Value -> {
                    toValue(topic.node, data).value.toString().let {
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

    override fun unsubscribeTopics(topics: List<Topic>, items: List<MonitoredItem>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            val xs = items.filterIsInstance<Plc4xMonitoredItem>()
            if (xs.isNotEmpty()) {
                val builder = plc!!.unsubscriptionRequestBuilder()
                items.filterIsInstance<Plc4xMonitoredItem>().forEach { item ->
                    builder.addHandles(item.item)
                }
                val request = builder.build()
                request.execute().whenComplete { response, throwable ->
                    if (response!=null) promise.complete(true)
                    else {
                        logger.severe("An error occurred: ${throwable.message}")
                        promise.complete(false)
                    }
                }
            }

            val polledItems = items.filterIsInstance<Plc4xPolledItem>()
            polledItems.forEach { item ->
                pollingTopics.filter { poll -> poll.key.topicName == item.item.topicName }.forEach {
                    pollingTopics.remove(it.key)
                }
            }
            if (polledItems.isNotEmpty()) {
                promise.complete(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return promise.future()
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            when (topic.topicType) {
                Topic.TopicType.Node -> {
                    when (topic.format) {
                        Topic.Format.Value -> {
                            writeValueAsync(topic.node, value.toString()).onComplete(ret)
                        }
                        Topic.Format.Json,
                        Topic.Format.Pretty -> {
                            logger.warning("Value format not yet implemented!") // TODO
                        }
                    }
                }
                else -> {
                    logger.warning("Item type [${topic.topicType}] not yet implemented!")
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
                    if (!isConnected()) {
                        message.reply(JsonObject().put("Ok", false).put("Result", "Not connected!"))
                    } else {
                        val builder: PlcReadRequest.Builder = plc!!.readRequestBuilder()
                        builder.addItem("value", node)
                        val request = builder.build()
                        val response = request.execute()
                        response.orTimeout(readTimeout, TimeUnit.MILLISECONDS)
                        response.whenComplete { readResponse, throwable ->
                            if (readResponse != null) {
                                try {
                                    val result = toValue(node, readResponse.getPlcValue("value")).encodeToJson()
                                    message.reply(JsonObject().put("Ok", true).put("Result", result))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            } else {
                                logger.severe("An error occurred: $throwable")
                                val result = JsonObject().put("Value", throwable.toString())
                                message.reply(JsonObject().put("Ok", false).put("Result", result))
                            }
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
                logger.severe(err)
            }
        }
    }

    override fun writeHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        logger.info("writeHandler [${node}]")
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
                logger.warning("Write of array not yet implemented!")
            }
            else -> {
                val err = String.format("Invalid format in write request!")
                message.reply(JsonObject().put("Ok", false))
                logger.severe(err)
            }
        }
    }

    private fun isConnected() = plc != null && plc!!.isConnected

    private fun writeValueAsync(node: String, value: Any): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            if (!isConnected()) {
                promise.complete(false)
            } else {
                val builder: PlcWriteRequest.Builder = plc!!.writeRequestBuilder()
                builder.addItem("value", node, value)
                val request = builder.build()
                val response = request.execute()
                response.orTimeout(writeTimeout, TimeUnit.MILLISECONDS)
                response.whenComplete { writeResponse, throwable ->
                    if (writeResponse != null) {
                        logger.finest("Write response [${writeResponse.getResponseCode("value")}]")
                        promise.complete(true)
                    } else {
                        logger.severe("Write error [$throwable]")
                        promise.complete(false)
                    }
                }
            }
        } catch (e: Exception) {
            logger.severe("Write exception [${e.message}]")
            promise.complete(false)
        }
        return promise.future()
    }

    override fun browseHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }

    override fun schemaHandler(message: Message<JsonObject>) {
        TODO("Not yet implemented")
    }
}