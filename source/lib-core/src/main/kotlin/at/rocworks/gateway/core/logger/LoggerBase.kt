package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.EventBus
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.Component
import at.rocworks.gateway.core.service.ComponentLogger
import at.rocworks.gateway.core.service.ServiceHandler
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.math.roundToInt


abstract class LoggerBase(config: JsonObject) : Component(config) {
    protected val id: String = config.getString("Id", "")
    protected val logger: Logger = ComponentLogger.getLogger(this::class.java.simpleName, id)
    protected val eventBus = EventBus(logger)

    protected val topicsWithConfig : List<Pair<Topic, JsonObject>>
    private val topics : List<Topic>
    private val services : List<Pair<Topic.SystemType, String>>

    private val defaultRetryWaitTime = 5000L

    private val writeParameterQueueSizeDefault = 10000
    private val writeParameterQueueSize : Int

    private val writeParameterBlockSizeDefault = 2000
    protected val writeParameterBlockSize : Int

    private val writeQueuePollTimeout : Long = 10L

    private val messageConsumers = mutableListOf<MessageConsumer<DataPoint>>()

    init {
        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDefault) ?: writeParameterQueueSizeDefault
        writeParameterBlockSize = writeParameters?.getInteger("BlockSize", writeParameterBlockSizeDefault) ?: writeParameterBlockSizeDefault

        logger.level = Level.parse(config.getString("LogLevel", "INFO"))

        topicsWithConfig = config
            .getJsonArray("Logging", JsonArray())
            .asSequence()
            .filterIsInstance<JsonObject>()
            .filter { it.getString("Topic") != null }
            .map { Pair(Topic.parseTopic(it.getString("Topic")), it) }
            .filter { it.first.format == Topic.Format.Json }
            .toList()

        topics = topicsWithConfig.map { it.first }

        services = topics.map { Pair(it.systemType, it.systemName) }.distinct()

        logger.fine { "Valid topics: ${topics.joinToString(separator = "|") { it.topicName }}" }
    }

    override fun getComponentId(): String {
        return id
    }

    override fun getComponentConfig(): JsonObject {
        return this.config
    }

    final override fun getComponentGroup(): ComponentGroup {
        return ComponentGroup.Logger
    }

    abstract fun open(): Future<Unit>
    abstract fun close(): Future<Unit>

    fun connect(connectPromise: Promise<Void>) {
        vertx.executeBlocking(Callable {
            try {
                open().onComplete { result ->
                    if (result.succeeded()) {
                        connectPromise.complete()
                    } else {
                        logger.warning("Connect failed!")
                        vertx.setTimer(defaultRetryWaitTime) { connect(connectPromise) }
                    }
                }
            } catch (e: Exception) {
                logger.warning("Error in connect [${e.message}]")
                connectPromise.fail(e)
            }
        })
    }

    @Volatile
    private var reconnectOngoing = false
    protected fun reconnect() {
        logger.info("Reconnect...")
        if (!reconnectOngoing) {
            reconnectOngoing = true
            val promise = Promise.promise<Void>()
            promise.future().onComplete {
                reconnectOngoing = false
            }
            connect(promise)
        }
    }

    private var busConsumerQueryHistory: MessageConsumer<JsonObject>? = null
    private var busConsumerExecuteSQL: MessageConsumer<JsonObject>? = null

    private var periodicMetricCalculator: Long = 0

    private fun writerThread() = thread(start = true) {
        logger.info("Writer thread with queue size [${writeValueQueue.remainingCapacity()}]")
        writeValueStop.set(false)
        while (!writeValueStop.get()) {
            writeExecutor()
        }
    }

    override fun start(startPromise: Promise<Void>) {
        super.start()
        vertx.executeBlocking(Callable { connect(startPromise) })

        busConsumerQueryHistory = vertx.eventBus().consumer("${Common.BUS_ROOT_URI_LOG}/$id/QueryHistory", ::queryHandler)
        busConsumerExecuteSQL = vertx.eventBus().consumer("${Common.BUS_ROOT_URI_LOG}/$id/ExecuteSQL", ::sqlHandler)

        periodicMetricCalculator = vertx.setPeriodic(1000, ::metricCalculator)
        writeValueThread = writerThread()
        subscribeTopics()
    }

    override fun stop(stopPromise: Promise<Void>) {
        super.stop()
        busConsumerQueryHistory?.unregister()
        vertx.cancelTimer(periodicMetricCalculator)
        unsubscribeTopics()
        writeValueStop.set(true)
        vertx.executeBlocking(Callable { close() })
        stopPromise.complete()
    }

    private fun subscribeTopics() {
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
                        .forEach { topic -> eventBus.requestSubscribeTopic(vertx, this.id, topic, ::onComplete, ::onMessage ) }
                }
            }
        }
    }

    private fun unsubscribeTopics() {
        messageConsumers.forEach { it.unregister() }
        eventBus.requestUnsubscribeTopics(vertx, this.id, topics) { _, _ -> }
    }

    abstract fun writeExecutor()

    private val writeValueStop = AtomicBoolean(false)
    private val writeValueQueue = ArrayBlockingQueue<DataPoint>(writeParameterQueueSize)
    private var writeValueQueueFull = false
    private var writeValueThread : Thread? = null

    private var valueCounterInput : Int = 0
    @Volatile var valueCounterOutput : Int = 0

    private fun valueConsumerDataPoint(data: DataPoint) {
        valueCounterInput++

        try {
            val topic = data.topic
            val value = data.value
            if (value.hasNoValue()) return

            logger.finest { "Got value $topic $value" }

            try {
                writeValueQueue.add(data)
                if (writeValueQueueFull) {
                    writeValueQueueFull = false
                    logger.warning("Logger write queue not full anymore. [${writeValueQueue.size}]")
                }
            } catch (e: IllegalStateException) {
                if (!writeValueQueueFull) {
                    writeValueQueueFull = true
                    logger.warning("Logger write queue is full! [${writeParameterQueueSize}]")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun valueConsumerJsonObject(data: JsonObject) {
        valueCounterInput++
        try {
            val topic = Topic.decodeFromJson(data.getJsonObject("Topic"))
            val value = TopicValue.decodeFromJson(data.getJsonObject("Value"))
            if (!value.hasValue()) return

            logger.finest { "Got value $topic $value" }

            val measurement = DataPoint(topic, value)
            try {
                writeValueQueue.add(measurement)
                if (writeValueQueueFull) {
                    writeValueQueueFull = false
                    logger.warning("Logger write queue not full anymore. [${writeValueQueue.size}]")
                }
            } catch (e: IllegalStateException) {
                if (!writeValueQueueFull) {
                    writeValueQueueFull = true
                    logger.warning("Logger write queue is full! [${writeParameterQueueSize}]")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    protected fun pollDatapointWait() : DataPoint? = writeValueQueue.poll(writeQueuePollTimeout, TimeUnit.MILLISECONDS)
    protected fun pollDatapointNoWait() : DataPoint? = writeValueQueue.poll()

    private var t1: Instant = Instant.now()
    @Suppress("UNUSED_PARAMETER")
    private fun metricCalculator(jobId: Long) {
        val t2 = Instant.now()
        val d = Duration.between(t1, t2).toMillis() / 1000.0
        if (d>0) {
            val topic = "logger/${this.id}/metrics"
            val vsInput = (valueCounterInput / d).roundToInt()
            val vsOutput = (valueCounterOutput / d).roundToInt()
            val result = JsonObject()
            result.put("Input v/s", vsInput)
            result.put("Output v/s", vsOutput)
            result.put("Queue Size", writeValueQueue.size)
            getAdditionalMetrics().forEach {
                result.put(it.key, it.value)
            }
            eventBus.publishJsonValue(vertx, topic, result)
        }
        t1 = t2
        valueCounterInput = 0
        valueCounterOutput = 0
    }

    open fun getAdditionalMetrics(): JsonObject {
        return JsonObject()
    }

    open fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any>>?) -> Unit // [[sourcetime, servertime, value, statuscode]]
    ) {
        logger.warning("Function queryExecutor not implemented!")
        result(false, null)
    }

    private fun queryHandler(message: Message<JsonObject>) {
        val request = message.body()
        val system = request.getString("System")
        val nodeId = request.getString("NodeId")
        val t1 = request.getLong("T1") // ms
        val t2 = request.getLong("T2") // ms

        vertx.executeBlocking(Callable {
            val timing1 = Instant.now()
            queryExecutor(system, nodeId, t1, t2) { ok, result ->
                val timing2 = Instant.now()
                logger.fine { "Query [${system}/${nodeId}] executed in [${Duration.between(timing1, timing2).toMillis()}] ms" }
                val response = JsonObject().put("Ok", ok)
                if (ok) response.put("Result", result)
                message.reply(response)
            }
        })
    }

    open fun sqlExecutor(sql: String, result: (Boolean, List<List<Any>>?) -> Unit) {
        result(true, listOf(listOf<Any>("Not implemented")))
    }

    private fun sqlHandler(message: Message<JsonObject>) {
        val request = message.body()
        val sql = request.getString("SQL")
        vertx.executeBlocking(Callable {
            val timing1 = Instant.now()
            sqlExecutor(sql) { ok, result ->
                val timing2 = Instant.now()
                logger.fine("SQL [${sql}] executed in [${Duration.between(timing1, timing2).toMillis()}] ms")
                val response = JsonObject().put("Ok", ok)
                if (ok) response.put("Result", result)
                message.reply(response)
            }
        })
    }
}