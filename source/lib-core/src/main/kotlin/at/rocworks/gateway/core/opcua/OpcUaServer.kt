package at.rocworks.gateway.core.opcua

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.EventBus
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.service.Component
import at.rocworks.gateway.core.service.ComponentLogger
import at.rocworks.gateway.core.opcua.server.OpcUaServerInstance
import at.rocworks.gateway.core.service.ServiceHandler

import io.vertx.core.Promise
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.concurrent.thread

class OpcUaServer(config: JsonObject): Component(config) {
    private val id: String = config.getString("Id", "")
    private val logger = ComponentLogger.getLogger(this::class.java.simpleName, id)
    private val eventBus = EventBus(logger)

    private val topics : List<Topic>
    private val services : List<Pair<Topic.SystemType, String>>

    val bindPort: Int = config.getInteger("Port", 4840)
    val bindPath: String = config.getString("Path", "server")

    val manufacturerName = "Rocworks"
    val productName = "Automation Gateway OPC UA Server"
    val productUri = "https://github.com/vogler75/automation-gateway"
    val softwareVersion = "1"
    val buildNumber = "1"
    val buildDate= ""

    val bindAddresses = listOf("0.0.0.0")
    val endpointAddresses = listOf("<hostname>", "<localhost>")
    val securityPolicies = listOf("None","Basic128Rsa15","Basic256","Basic256Sha256","Aes128_Sha256_RsaOaep","Aes256_Sha256_RsaPss")

    private var serverInstance : OpcUaServerInstance? = null

    private val messageConsumers = mutableListOf<MessageConsumer<DataPoint>>()

    private val writeValueQueueSize = 100
    private var writeValueQueueFull = false
    private val writeValueQueue = ArrayBlockingQueue<DataPoint>(100)
    private var writeValueThread : Thread? = null
    private val writeValueStop = AtomicBoolean(false)

    fun writeValue(data: DataPoint) {
        try {
            writeValueQueue.add(data)
            if (writeValueQueueFull) {
                writeValueQueueFull = false
                logger.warning("Write queue not full anymore. [${writeValueQueue.size}]")
            }
        } catch (e: IllegalStateException) {
            if (!writeValueQueueFull) {
                writeValueQueueFull = true
                logger.warning("Write queue is full! [${writeValueQueueSize}]")
            }
        }
    }

    private fun writerThread() = thread(start = true) {
        logger.info("Writer thread with queue size [${writeValueQueue.remainingCapacity()}]")
        writeValueStop.set(false)
        while (!writeValueStop.get()) {
            writeValueQueue.poll(10, TimeUnit.MILLISECONDS)?.let { data ->
                eventBus.requestPublishDataPoint(vertx, data)
            }
        }
    }

    init {
        topics = config
            .getJsonArray("Topics")
            ?.asSequence()
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.getString("Topic") }
            ?.map { Topic.parseTopic(it) }
            ?.filter { it.format == Topic.Format.Json }
            ?.toList()
            ?:listOf()

        services = topics.map { Pair(it.systemType, it.systemName) }.distinct()

        logger.level = Level.parse(config.getString("LogLevel", "INFO"))
    }

    override fun getComponentGroup(): ComponentGroup {
        return ComponentGroup.Server
    }

    override fun getComponentId(): String {
        return id
    }

    override fun getComponentConfig(): JsonObject {
        return config
    }

    override fun start(startPromise: Promise<Void>) {
        super.start()
        thread {
            writeValueThread = writerThread()
            serverInstance = OpcUaServerInstance(this).also {
                it.startup()
                subscribeTopics()
                startPromise.complete()
            }
        }
    }

    override fun stop(stopPromise: Promise<Void>) {
        super.stop()
        unsubscribeTopics()
        writeValueStop.set(true)
        serverInstance?.shutdown()
        stopPromise.complete()
    }

    private fun subscribeTopics() { // TODO: same in LoggerBase.kt
        val handler = ServiceHandler(vertx, logger)
        fun onComplete(ok: Boolean, consumer: MessageConsumer<DataPoint>) {
            if (ok) messageConsumers.add(consumer)
        }
        fun onMessage(@Suppress("UNUSED_PARAMETER") topic: Topic, message: Message<DataPoint>) {
            valueConsumerDataPoint(message.body())
        }
        services.forEach { it ->
            handler.observeService(it.first.name, it.second) { service ->
                logger.info("Service [${service.name}] changed status [${service.status}]")
                if (service.status == Status.UP) {
                    topics
                        .filter { it.systemType.name == service.type && it.systemName == service.name }
                        .forEach { topic -> eventBus.requestSubscribeTopic(vertx, this.id, topic, ::onComplete,::onMessage) }
                }
            }
        }
    }

    private fun unsubscribeTopics() {
        messageConsumers.forEach { it.unregister() }
        eventBus.requestUnsubscribeTopics(vertx, this.id, topics) { _, _ -> }
    }

    private fun valueConsumerDataPoint(data: DataPoint) {
        try {
            val topic = data.topic
            val value = data.value
            if (value.hasNoValue()) return
            logger.finest { "Got value $topic $value" }
            serverInstance?.gatewayNodes?.setDataPoint(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}