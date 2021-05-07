package at.rocworks.gateway.core.logger

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.ServiceHandler
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status
import org.slf4j.LoggerFactory
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
    protected val logger: org.slf4j.Logger = LoggerFactory.getLogger(id)

    private val topics : List<Topic>

    private val services : List<Pair<Topic.SystemType, String>>

    companion object {
        const val defaultRetryWaitTime = 5000L
    }

    private val writeParameterQueueSizeDef = 10000
    private val writeParameterQueueSize : Int

    private val writeParameterBlockSizeDefault = 2000
    protected val writeParameterBlockSize : Int

    init {
        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDef) ?: writeParameterQueueSizeDef
        writeParameterBlockSize = writeParameters?.getInteger("BlockSize", writeParameterBlockSizeDefault) ?: writeParameterBlockSizeDefault

        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))

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

        logger.info("Valid topics: {}", topics.joinToString(separator = "|") { it.topicName })
    }

    abstract fun open(): Future<Unit>
    abstract fun close()

    override fun start(startPromise: Promise<Void>) {
        fun connect() {
            thread {
                try {
                    open().onComplete { result ->
                        if (result.succeeded()) {
                            this.subscribeTopics()
                            vertx.setPeriodic(1000, ::metricCalculator)
                            vertx.eventBus().consumer("${Common.BUS_ROOT_URI_LOG}/$id/QueryHistory", ::queryHandler)
                            startPromise.complete()
                        } else {
                            vertx.setTimer(defaultRetryWaitTime) { connect() }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    startPromise.fail(e)
                }
            }
        }
        connect()
    }

    override fun stop(stopPromise: Promise<Void>) {
        writeValueStop.set(true)
        writeValueStopped.future().onComplete {
            close()
            stopPromise.complete()
        }
    }

    private fun subscribeTopics() { // TODO: Same in Influx
        val handler = ServiceHandler(vertx, logger)
        services.forEach { it ->
            handler.observeService(it.first.name, it.second) { service ->
                logger.info("Service [{}] changed status [{}]", service.name, service.status)
                if (service.status == Status.UP) {
                    topics
                        .filter { it.systemType.name == service.type && it.systemName == service.name }
                        .forEach { topic ->
                            vertx.eventBus().consumer<Any>(topic.topicName) { valueConsumer(it.body()) }
                            subscribeTopic(ServiceHandler.endpointOf(service), topic)
                        }
                }
            }
        }
    }

    private fun subscribeTopic(endpoint: String, topic: Topic) { // TODO: Same in Influx
        val request = JsonObject().put("ClientId", this.id).put("Topic", topic.encodeToJson())
        if (endpoint!="") {
            logger.info("Subscribe to [{}]", endpoint)
            vertx.eventBus().request<JsonObject>("${endpoint}/Subscribe", request) {
                logger.debug("Subscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
            }
        }
    }

    private fun valueConsumer(value: Any) { // TODO: Same in Influx
        try {
            when (value) {
                is Buffer -> valueConsumer(Json.decodeValue(value) as JsonObject)
                is JsonObject -> valueConsumer(value)
                else -> logger.warn("Got unhandled class of instance []", value.javaClass.simpleName)
            }
        } catch (e: Exception) {
            logger.error(e.message)
        }
    }

    protected data class DataPoint(
        val topic: Topic,
        val value: TopicValue
    )

    abstract fun writeExecutor()

    private val writeValueStop = AtomicBoolean(false)
    private val writeValueStopped = Promise.promise<Boolean>()
    protected val writeValueQueue = ArrayBlockingQueue<DataPoint>(writeParameterQueueSize)
    private var writeValueQueueFull = false
    private val writeValueThread =
        thread {
            logger.info("Writer thread with queue size [{}]", writeValueQueue.remainingCapacity())
            while (!writeValueStop.get()) {
                writeExecutor()
            }
            writeValueStopped.complete()
        }

    private var valueCounterInput : Int = 0
    @Volatile var valueCounterOutput : Int = 0

    private fun valueConsumer(data: JsonObject) {
        valueCounterInput++
        try {
            val topic = Topic.decodeFromJson(data.getJsonObject("Topic"))
            val value = TopicValue.fromJsonObject(data.getJsonObject("Value"))
            if (!value.hasValue()) return

            val measurement = DataPoint(topic, value)

            try {
                writeValueQueue.add(measurement)
                if (writeValueQueueFull) {
                    writeValueQueueFull = false
                    logger.warn("Logger write queue not full anymore. [{}]", writeValueQueue.size)
                }
            } catch (e: IllegalStateException) {
                if (!writeValueQueueFull) {
                    writeValueQueueFull = true
                    logger.warn("Logger write queue is full! [{}]", writeParameterQueueSize)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var t1: Instant = Instant.now()
    @Suppress("UNUSED_PARAMETER")
    private fun metricCalculator(jobId: Long) { // TODO: Same in Influx
        val t2 = Instant.now()
        val d = Duration.between(t1, t2).toMillis() / 1000.0
        if (d>0) {
            val topic = "logger/${this.id}/metrics"
            val vsInput = (valueCounterInput / d).roundToInt()
            val vsOutput = (valueCounterOutput / d).roundToInt()
            val result = JsonObject()
            result.put("Input v/s", vsInput)
            result.put("Output v/s", vsOutput)
            vertx.eventBus().publish(topic, result)
        }
        t1 = t2
        valueCounterInput = 0
        valueCounterOutput = 0
    }

    abstract fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeNano: Long,
        toTimeNano: Long,
        result: (Boolean, List<List<Any>>?) -> Unit // [[sourcetime, value, statuscode, system]]
    )

    private fun queryHandler(message: Message<JsonObject>) {
        val request = message.body()
        val system = request.getString("System")
        val nodeId = request.getString("NodeId")
        val t1 = request.getLong("T1") * 1000000 // ms to nano
        val t2 = request.getLong("T2") * 1000000 // ms to nano
        queryExecutor(system, nodeId, t1, t2) { ok, result ->
            if (ok) message.reply(JsonObject().put("Ok", true).put("Result", result))
            else message.reply(JsonObject().put("Ok", false))
        }
    }
}