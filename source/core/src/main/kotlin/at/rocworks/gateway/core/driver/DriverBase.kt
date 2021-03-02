package at.rocworks.gateway.core.driver

import at.rocworks.gateway.core.data.Topic

import io.vertx.core.*
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import io.vertx.servicediscovery.Record
import io.vertx.servicediscovery.ServiceDiscovery

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.Exception

import java.util.ArrayList
import java.util.concurrent.ExecutionException
import java.util.function.Consumer
import java.util.logging.Level

abstract class DriverBase(config: JsonObject) : AbstractVerticle() {
    protected abstract fun getRootUri(): String

    protected val id = config.getString("Id", DriverBase::class.java.simpleName)

    protected val uri = "${getRootUri()}/$id"

    protected val logger: Logger
    protected val logLevel: String = config.getString("LogLevel", "INFO")

    protected var messageHandlers: List<MessageConsumer<*>> = ArrayList()

    protected val registry = Registry()

    protected abstract fun connect(): Future<Boolean>
    protected abstract fun disconnect(): Future<Boolean>
    protected abstract fun shutdown()

    init {
        java.util.logging.Logger.getLogger(id).level = Level.parse(logLevel)
        logger = LoggerFactory.getLogger(id)
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.warn("Shutdown [{}]", id)
            try {
                shutdown()
                logger.warn("Shutdown finished [{}]", id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        })
    }

    override fun start(startPromise: Promise<Void>) {
        logger.info("Driver start [{}]", id)
        Thread {
            try {
                connect().onSuccess {
                    connectHandlers()
                    registerService()
                    startPromise.complete()
                }.onFailure { result: Throwable -> startPromise.fail(result.message) }
            } catch (e: Exception) {
                startPromise.fail(e)
            }
        }.start()
    }

    override fun stop(stopPromise: Promise<Void>) {
        logger.info("Driver stop [{}]", id)
        Thread {
            try {
                disconnect().onSuccess {
                    disconnectHandlers()
                    stopPromise.complete()
                }.onFailure { result: Throwable -> stopPromise.fail(result.message) }
            } catch (e: Exception) {
                stopPromise.fail(e)
            }
        }.start()
    }

    private fun registerService() {
        val discovery = ServiceDiscovery.create(vertx)
        val record = Record()
            .setName(id)
            .setType(Topic.SystemType.Opc.toString())
            .setLocation(JsonObject().put("endpoint", uri))
        discovery.publish(record) { ar ->
            if (ar.succeeded()) {
                val publishedRecord = ar.result()
                logger.info("Service registered.")
            } else {
                logger.warn("Service registration failed!")
            }
        }
    }

    private fun connectHandlers() {
        logger.info("Connect handlers to [{}]", uri)
        messageHandlers = listOf<MessageConsumer<JsonObject>>(
            vertx.eventBus().consumer("$uri/ServerInfo") { serverInfoHandler(it) },
            vertx.eventBus().consumer("$uri/Subscribe") { subscribeHandler(it) },
            vertx.eventBus().consumer("$uri/Unsubscribe") { unsubscribeHandler(it) },
            vertx.eventBus().consumer("$uri/Publish") { publishHandler(it) },
            vertx.eventBus().consumer("$uri/Read") { readHandler(it) },
            vertx.eventBus().consumer("$uri/Write") { writeHandler(it) },
            vertx.eventBus().consumer("$uri/Browse") { browseHandler(it) }
        )
    }

    private fun disconnectHandlers() {
        messageHandlers.forEach(Consumer { h: MessageConsumer<*> -> h.unregister() })
    }

    private fun serverInfoHandler(message: Message<JsonObject>) { // TODO: make it async
        val result = readServerInfo()
        message.reply(JsonObject().put("Ok", true).put("Result", result))
    }

    private fun subscribeHandler(message: Message<JsonObject>) {
        val request = message.body()
        val clientId = request.getString("ClientId")
        val tagTopic = Topic.decodeFromJson(request.getJsonObject("Topic"))
        subscribeTopic(clientId, tagTopic).onComplete { result: AsyncResult<Boolean> ->
            if (result.cause() != null) result.cause().printStackTrace()
            message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
        }
    }

    private fun unsubscribeHandler(message: Message<JsonObject>) {
        val request = message.body()
        val clientId = request.getString("ClientId")
        val tagTopics = request.getJsonArray("Topics").map { Topic.decodeFromJson(it as JsonObject) }
        unsubscribeTopics(clientId, tagTopics).onComplete { result: AsyncResult<Boolean> ->
            if (result.cause() != null) result.cause().printStackTrace()
            message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
        }
    }

    private fun publishHandler(message: Message<JsonObject>) {
        val topic = Topic.decodeFromJson(message.body().getJsonObject("Topic"))
        val data = message.body().getBuffer("Data")
        logger.debug("Publish [{}] [{}]", topic.toString(), data.toString())
        try {
            writeTopicValue(topic, data).onComplete { result: AsyncResult<Boolean> ->
                if (result.cause() != null) result.cause().printStackTrace()
                message.reply(JsonObject().put("Ok", result.succeeded() && result.result()))
            }
        } catch (e: ExecutionException) {
            e.printStackTrace()
            message.reply(JsonObject().put("Ok", false))
        } catch (e: InterruptedException) {
            e.printStackTrace()
            message.reply(JsonObject().put("Ok", false))
        }
    }

    private fun subscribeTopic(clientId: String, topic: Topic): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            val (count, added) = registry.addClient(clientId, topic)
            logger.debug("Subscribe [{}] [{}]", count, topic)
            if (!added) {
                logger.warn("Client [{}] already subscribed to [{}]", clientId, topic)
                ret.complete(false)
            } else if (count == 1) {
                subscribeTopics(listOf(topic)).onComplete {
                    ret.complete(it.result())
                }
            } else {
                ret.complete(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ret.fail(e)
        }
        return ret.future()
    }

    fun resubscribe() {
        val topics = registry.getTopics()
        if (topics.isNotEmpty()) {
            logger.info("Resubscribe [{}] topics", topics.size)
            topics.forEach(registry::delTopic)
            subscribeTopics(topics)
        }
    }

    private fun unsubscribeTopics(clientId: String, topics: List<Topic>): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        val items = ArrayList<MonitoredItem>()

        topics.forEach { topic ->
            val (count, removed) = registry.delClient(clientId, topic)
            logger.debug("Unsubscribe [{}] [{}]", count, topic)
            if (!removed) {
                logger.warn("Client [{}] was not subscribed to [{}]", clientId, topic)
            } else if (count == 0) {
                items.addAll(registry.delTopic(topic))
            }
        }
        if (items.size > 0) {
            unsubscribeItems(items).onComplete(ret)
        } else {
            ret.complete(true)
        }
        return ret.future()
    }

    // MQTT
    protected abstract fun subscribeTopics(topics: List<Topic>): Future<Boolean>
    protected abstract fun unsubscribeItems(items: List<MonitoredItem>) : Future<Boolean>
    protected abstract fun writeTopicValue(topic: Topic, value: Buffer): Future<Boolean>

    // GraphQL
    protected abstract fun readServerInfo(): JsonObject
    protected abstract fun readHandler(message: Message<JsonObject>)
    protected abstract fun writeHandler(message: Message<JsonObject>)
    protected abstract fun browseHandler(message: Message<JsonObject>)
}