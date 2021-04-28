package at.rocworks.gateway.logger.iotdb

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
import org.apache.iotdb.session.Session
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType

import org.slf4j.LoggerFactory

import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.TimeUnit
import java.lang.IllegalStateException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

import kotlin.concurrent.thread
import kotlin.math.roundToInt

class IoTDBLogger(private val config: JsonObject) : AbstractVerticle() {
    private val id = config.getString("Id", "IoTDB")
    private val logger = LoggerFactory.getLogger(id)

    private val topics : List<Topic>

    private val services : List<Pair<Topic.SystemType, String>>

    private val host = config.getString("Host", "localhost")
    private val port = config.getInteger("Port", 6667)
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "root.scada")

    companion object {
        const val defaultRetryWaitTime = 5000L
    }

    private val writeParameterQueueSize : Int
    private val writeParameterQueueSizeDef = 10000

    private val session: Session

    init {
        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDef) ?: writeParameterQueueSizeDef

        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))
        session = if (username == null || username == "")
            Session(host, port)
        else
            Session(host, port, username, password)

        topics = config // TODO: Same in Influx
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
                    session.open()
                    try {
                        session.setStorageGroup(database)
                    } catch (e: Exception) {
                        logger.warn(e.message)
                    }
                    logger.info("InfluxDB connected.")
                    this.subscribeTopics()
                    vertx.setPeriodic(1000, ::metricCalculator)
                    vertx.eventBus().consumer("${Common.BUS_ROOT_URI_LOG}/$id/QueryHistory", ::queryHandler)
                    startPromise.complete()
                } catch (e: Exception) {
                    logger.error("IoTDB connect failed! Wait and retry...[{}]", e.message)
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

    private fun subscribeTopics() { // TODO: Same in Influx
        val handler = ServiceHandler(vertx, logger)
        services.forEach { it ->
            handler.observeService(it.first.name, it.second) { service ->
                logger.info("Service [{}] changed status [{}]", service.name, service.status)
                if (service.status == Status.UP) {
                    topics
                        .filter { it.systemType.name == service.type && it.systemName == service.name }
                        .forEach { topic ->
                            /*
                            try {
                                val path = database+"."+topic.topicName.replace("/", ".")
                                session.createTimeseries( // TODO: not good for wildcard/path topics + configurable?
                                    path,
                                    TSDataType.DOUBLE,
                                    TSEncoding.RLE,
                                    CompressionType.SNAPPY
                                )
                            } catch (e: Exception) {
                                logger.warn(e.message)
                            }
                             */
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

    private data class DataPoint(
        val topic: Topic,
        val value: TopicValue
    )

    private val writeValueStop = AtomicBoolean(false)
    private val writeValueStopped = Promise.promise<Boolean>()
    private val writeValueQueue = ArrayBlockingQueue<DataPoint>(writeParameterQueueSize)
    private var writeValueQueueFull = false
    private val writeValueThread =
        thread {
            logger.info("Writer thread with queue size [{}]", writeValueQueue.remainingCapacity())
            var point : DataPoint?

            while (!writeValueStop.get()) {
                point = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
                while (point!=null) {
                    try {
                        val time = point.value.sourceTime().toEpochMilli()
                        val value = point.value.valueAsDouble() ?: point.value.valueAsString()
                        val path = point.topic.browsePath.replace("/", ".")

                        session.insertRecord( // TODO: create and insert blocks 
                            "$database.$path",
                            time,
                            listOf("value", "status"),
                            listOf(TSDataType.DOUBLE, TSDataType.TEXT),
                            listOf(value, point.value.statusAsString())
                        )

                    } catch (e: Exception) {
                        logger.error(e.message)
                    }

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

            val measurement = DataPoint(topic, value)
            //logger.info(measurement.toString())

            /*
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
             */

            try {
                writeValueQueue.add(measurement)
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

    private fun queryHandler(message: Message<JsonObject>) {
        val request = message.body()
        val system = request.getString("System")
        val nodeId = request.getString("NodeId")
        val t1 = request.getLong("T1") * 1000000 // ms to nano
        val t2 = request.getLong("T2") * 1000000 // ms to nano

        /*
        try {
            val sql = "SELECT value, status, system FROM \"${system}\" WHERE \"tag\" = '$nodeId' AND time >= $t1 AND time <= $t2"
            val res: QueryResult = session.query(Query(sql))
            val list = res.results.getOrNull(0)?.series?.getOrNull(0)?.values
            if (list!=null) message.reply(JsonObject().put("Ok", true).put("Result", list))
            else message.reply(JsonObject().put("Ok", false))
        } catch (e: Exception) {
            message.reply(JsonObject().put("Ok", false))
            e.printStackTrace()
        }
         */
    }
}