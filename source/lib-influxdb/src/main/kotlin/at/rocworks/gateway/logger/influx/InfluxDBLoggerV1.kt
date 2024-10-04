package at.rocworks.gateway.logger.influx

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise

import io.vertx.core.json.JsonObject
import java.util.concurrent.TimeUnit

import org.influxdb.BatchOptions
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.*
import java.util.concurrent.Callable


class InfluxDBLoggerV1(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "test")

    private var enabled = false

    private var session: InfluxDB? = null

    private fun connect() = if (username == null || username == "")
        InfluxDBFactory.connect(url)
    else
        InfluxDBFactory.connect(url, username, password)

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        vertx.executeBlocking(Callable {
            try {
                connect().let {
                    session = it
                    val response: Pong = it.ping()
                    if (!response.isGood) {
                        enabled = true
                        result.complete()
                    } else {
                        it.setLogLevel(InfluxDB.LogLevel.NONE)
                        it.query(Query("CREATE DATABASE $database"))
                        it.setDatabase(database)
                        var options = BatchOptions.DEFAULTS
                        options = options.bufferLimit(writeParameterBlockSize)
                        it.enableBatch(options)
                        logger.info("InfluxDB connected.")
                        enabled = true
                        result.complete()
                    }
                }
            } catch (e: Exception) {
                logger.severe("InfluxDB connect failed! [${e.message}]")
                enabled = false
                result.fail(e)
            }
        })
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        session?.close()
        enabled = false
        promise.complete()
        return promise.future()
    }

    override fun isEnabled(): Boolean {
        return enabled
    }

    private fun influxPointOf(dp: DataPoint): Point {
        val point = Point.measurement(dp.topic.systemName) // TODO: configurable measurement name
            .time(dp.value.sourceTime().toEpochMilli(), TimeUnit.MILLISECONDS)
            .tag("tag", dp.topic.getBrowsePathOrNode().toString()) // TODO: add topicName, topicType, topicNode, ...
            .tag("address", dp.topic.topicNode)
            .tag("status", dp.value.statusAsString())

        val numeric: Double? = dp.value.valueAsDouble()
        if (numeric != null) {
            //logger.debug("topic [$topic] numeric [$numeric]")
            point.addField("value", numeric)
        } else {
            //logger.debug("topic [$topic] text [${value.valueAsString()}]")
            point.addField("text", dp.value.valueAsString())
        }

        return point.build()
    }

    override fun writeExecutor() {
        val batch = BatchPoints.database(database).build()
        pollDatapointBlock {
            batch.point(influxPointOf(it))
        }
        if (batch.points.size > 0) {
            try {
                session?.write(batch)
                commitDatapointBlock()
                valueCounterOutput+=batch.points.size
            } catch (e: Exception) {
                logger.severe("Error writing batch [${e.message}]")
            }
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any?>>?) -> Unit
    ) {
        val fromTimeNano = fromTimeMS * 1_000_000
        val toTimeNano = toTimeMS * 1_000_000
        try {
            val data = session?.let { s ->
                val sql = """
                SELECT time, servertime, value, text, status 
                FROM "$system" 
                WHERE "address" = '$nodeId' 
                AND time >= $fromTimeNano AND time <= $toTimeNano 
                """.trimIndent()
                s.query(Query(sql)).let { query ->
                    query.results.getOrNull(0)
                        ?.series?.getOrNull(0)
                        ?.values?.map {
                            listOf(
                                it.component1(),
                                it.component2(),
                                it.component3() ?: it.component4(),
                                it.component5()
                            )
                        }
                }
            }
            result(data != null, data)
        } catch (e: Exception) {
            logger.severe("Error executing query [${e.message}]")
            result(false, null)
        }
    }
}