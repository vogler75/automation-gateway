package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.ServiceHandler

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status

import java.lang.IllegalStateException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

import kotlin.concurrent.thread
import kotlin.math.roundToInt

abstract class LoggerBase(config: JsonObject) : AbstractVerticle() {
    protected val id: String = config.getString("Id", "Logger")
    protected val logger: Logger = Logger.getLogger(id)

    private val topics : List<Topic>

    private val services : List<Pair<Topic.SystemType, String>>

    companion object {
        const val defaultRetryWaitTime = 5000L
    }

    private val writeParameterQueueSizeDefault = 10000
    private val writeParameterQueueSize : Int

    private val writeParameterBlockSizeDefault = 2000
    protected val writeParameterBlockSize : Int

    init {
        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDefault) ?: writeParameterQueueSizeDefault
        writeParameterBlockSize = writeParameters?.getInteger("BlockSize", writeParameterBlockSizeDefault) ?: writeParameterBlockSizeDefault

        logger.level = Level.parse(config.getString("LogLevel", "INFO"))

        topics = config
            .getJsonArray("Logging")
            ?.asSequence()
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { it.getString("Topic") }
            ?.map { Topic.parseTopic(it) }
            ?.filter { it.format == Topic.Format.Json }
            ?.toList()
            ?:listOf()

        services = topics.map { Pair(it.systemType, it.systemName) }.distinct()

        logger.info("Valid topics: ${topics.joinToString(separator = "|") { it.topicName }}")
    }

    abstract fun open(): Future<Unit>
    abstract fun close()

    fun connect(connectPromise: Promise<Void>) {
        thread {
            try {
                open().onComplete { result ->
                    if (result.succeeded()) {
                        connectPromise.complete()
                    } else {
                        logger.warning("Connect failed...")
                        vertx.setTimer(defaultRetryWaitTime) { connect(connectPromise) }
                    }
                }
            } catch (e: Exception) {
                logger.warning("Error in connect [${e.message}]")
                connectPromise.fail(e)
            }
        }
    }

    @Volatile
    private var reconnectOngoing = false
    protected fun reconnect() {
        if (!reconnectOngoing) {
            reconnectOngoing = true
            val promise = Promise.promise<Void>()
            promise.future().onComplete {
                reconnectOngoing = false
            }
            connect(promise)
        }
    }

    override fun start(startPromise: Promise<Void>) {
        startPromise.future().onComplete {
            vertx.eventBus().consumer("${Common.BUS_ROOT_URI_LOG}/$id/QueryHistory", ::queryHandler)
            vertx.setPeriodic(1000, ::metricCalculator)
            writeValueThread.start()
            subscribeTopics()
        }
        connect(startPromise)
    }

    override fun stop(stopPromise: Promise<Void>) {
        writeValueStop.set(true)
        writeValueStopped.future().onComplete {
            close()
            stopPromise.complete()
        }
    }

    private fun subscribeTopics() {
        val handler = ServiceHandler(vertx, logger)
        services.forEach { it ->
            handler.observeService(it.first.name, it.second) { service ->
                logger.info("Service [${service.name}] changed status [${service.status}]")
                if (service.status == Status.UP) {
                    topics
                        .filter { it.systemType.name == service.type && it.systemName == service.name }
                        .forEach { topic ->
                            vertx.eventBus().consumer<DataPoint>(topic.topicName) {
                                valueConsumerDataPoint(it.body())
                            }
                            subscribeTopic(ServiceHandler.endpointOf(service), topic)
                        }
                }
            }
        }
    }

    private fun subscribeTopic(endpoint: String, topic: Topic) { // TODO: Same in Influx
        val request = JsonObject().put("ClientId", this.id).put("Topic", topic.encodeToJson())
        if (endpoint!="") {
            logger.info("Subscribe to [${endpoint}]")
            vertx.eventBus().request<JsonObject>("${endpoint}/Subscribe", request) {
                logger.finest { "Subscribe response [${it.succeeded()}] [${it.result()?.body()}]" }
            }
        }
    }

    abstract fun writeExecutor()

    private val writeValueStop = AtomicBoolean(false)
    private val writeValueStopped = Promise.promise<Boolean>()
    protected val writeValueQueue = ArrayBlockingQueue<DataPoint>(writeParameterQueueSize)
    private var writeValueQueueFull = false
    private val writeValueThread =
        thread(start = false) {
            logger.info("Writer thread with queue size [${writeValueQueue.remainingCapacity()}]")
            while (!writeValueStop.get()) {
                writeExecutor()
            }
            writeValueStopped.complete()
        }

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
            val value = TopicValue.fromJsonObject(data.getJsonObject("Value"))
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
            vertx.eventBus().publish(topic, result)
        }
        t1 = t2
        valueCounterInput = 0
        valueCounterOutput = 0
    }

    abstract fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any>>?) -> Unit // [[sourcetime, servertime, value, statuscode, system]]
    )

    private fun queryHandler(message: Message<JsonObject>) {
        val request = message.body()
        val system = request.getString("System")
        val nodeId = request.getString("NodeId")
        val t1 = request.getLong("T1") // ms
        val t2 = request.getLong("T2") // ms
        queryExecutor(system, nodeId, t1, t2) { ok, result ->
            val response = JsonObject().put("Ok", ok)
            if (ok) response.put("Result", result)
            message.reply(response)
        }
    }
}