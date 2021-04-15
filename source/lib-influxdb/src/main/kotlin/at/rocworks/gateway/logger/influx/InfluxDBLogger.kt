package at.rocworks.gateway.logger.influx

import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValue
import at.rocworks.gateway.core.service.ServiceHandler
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status
import org.influxdb.BatchOptions
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.InfluxDBIOException
import org.influxdb.dto.*
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.TimeUnit
import java.lang.IllegalStateException
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class InfluxDBLogger(private val config: JsonObject) : AbstractVerticle() {
    private val id = config.getString("Id", "InfluxDB")
    private val logger = LoggerFactory.getLogger(id)

    private val topics : List<Topic>

    private val services : List<Pair<Topic.SystemType, String>>

    private val url = config.getString("Url", "")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "scada")

    private val db: InfluxDB

    companion object {
        const val defaultRetryWaitTime = 5000L
    }

    private val writeParameterQueueSize : Int
    private val writeParameterQueueSizeDef = 10000

    init {
        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDef) ?: writeParameterQueueSizeDef

        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))
        db = if (username == null || username == "")
            InfluxDBFactory.connect(url)
        else
            InfluxDBFactory.connect(url, username, password)

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

    override fun start(startPromise: Promise<Void>) {
        fun connect() {
            thread {
                try {
                    val response: Pong = db.ping()
                    if (!response.isGood) {
                        logger.error("InfluxDB connect failed! Wait and retry...")
                        vertx.setTimer(defaultRetryWaitTime) { connect() }
                    } else {
                        logger.info("InfluxDB connected.")
                        db.setLogLevel(InfluxDB.LogLevel.NONE)
                        db.query(Query("CREATE DATABASE $database"))
                        db.setDatabase(database)
                        db.enableBatch(BatchOptions.DEFAULTS) // TODO: make batch options configurable
                        this.subscribeTopics()
                        vertx.setPeriodic(1000, ::metricCalculator)
                        vertx.eventBus().consumer("${Common.BUS_ROOT_URI_LOG}/$id/QueryHistory", ::queryHandler)
                        startPromise.complete()
                    }
                } catch (e: InfluxDBIOException) {
                    logger.error("InfluxDB connect failed! Wait and retry...[{}]", e.message)
                    vertx.setTimer(defaultRetryWaitTime) { connect() }
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
        writeValueStopped.future().onComplete { stopPromise.complete() }
    }

    private fun subscribeTopics() {
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

    private fun subscribeTopic(endpoint: String, topic: Topic) {
        val request = JsonObject().put("ClientId", this.id).put("Topic", topic.encodeToJson())
        if (endpoint!="") {
            logger.info("Subscribe to [{}]", endpoint)
            vertx.eventBus().request<JsonObject>("${endpoint}/Subscribe", request) {
                logger.debug("Subscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
            }
        }
    }

    private fun valueConsumer(value: Any) {
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

    private val writeValueStop = AtomicBoolean(false)
    private val writeValueStopped = Promise.promise<Boolean>()
    private val writeValueQueue = ArrayBlockingQueue<Point>(writeParameterQueueSize)
    private var writeValueQueueFull = false
    private val writeValueThread =
        thread {
            logger.info("Writer thread with queue size [{}]", writeValueQueue.remainingCapacity())
            var point : Point?
            while (!writeValueStop.get()) {
                point = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
                while (point!=null) {
                    db.write(point)
                    valueCounterOutput++
                    point = writeValueQueue.poll()
                }
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

            val point = Point.measurement(topic.systemName)
                .time(value.sourceTime().toEpochMilli(), TimeUnit.MILLISECONDS)
                .tag("tag", topic.address)
                .tag("system", topic.systemName)
                .tag("status", value.statusAsString())

            if (value.hasStruct()) {
                value.asFlatMap().forEach { (k, v) ->
                    val d = v.toString().toDoubleOrNull()
                    if (d!=null) point.addField(k, d)
                    else point.addField(k, v.toString())
                }
            } else {
                val numeric: Double? = value.valueAsDouble()
                if (numeric != null) {
                    //logger.debug("topic [$topic] numeric [$numeric]")
                    point.addField("value", numeric)
                } else {
                    //logger.debug("topic [$topic] text [${value.valueAsString()}]")
                    point.addField("text", value.valueAsString())
                }
            }

            try {
                writeValueQueue.add(point.build())
                if (writeValueQueueFull) {
                    writeValueQueueFull = false
                    logger.warn("Write queue free again! [{}]", writeValueQueue.size)
                }
            } catch (e: IllegalStateException) {
                if (!writeValueQueueFull) {
                    writeValueQueueFull = true
                    logger.warn("Write queue is full! [{}]", writeParameterQueueSize)
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
            vertx.eventBus().publish(topic, result)
        }
        t1 = t2
        valueCounterInput = 0
        valueCounterOutput = 0
    }

    private fun queryHandler(message: Message<JsonObject>) {
        val request = message.body()
        val system = request.getString("System")
        val nodeId = request.getString("NodeId")
        val t1 = request.getLong("T1") * 1000000 // ms to nano
        val t2 = request.getLong("T2") * 1000000 // ms to nano

        try {
            val sql = "SELECT value, status, system FROM \"${system}\" WHERE \"tag\" = '$nodeId' AND time >= $t1 AND time <= $t2"
            val res: QueryResult = db.query(Query(sql))
            val list = res.results.getOrNull(0)?.series?.getOrNull(0)?.values
            if (list!=null) message.reply(JsonObject().put("Ok", true).put("Result", list))
            else message.reply(JsonObject().put("Ok", false))
        } catch (e: Exception) {
            message.reply(JsonObject().put("Ok", false))
            e.printStackTrace()
        }
    }
}