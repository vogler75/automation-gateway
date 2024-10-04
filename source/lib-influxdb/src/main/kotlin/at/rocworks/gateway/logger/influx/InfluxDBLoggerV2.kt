package at.rocworks.gateway.logger.influx

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerBase
import com.influxdb.LogLevel
import com.influxdb.client.InfluxDBClient
import com.influxdb.client.InfluxDBClientFactory
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.write.Point

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import java.util.concurrent.Callable

class InfluxDBLoggerV2(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val token = config.getString("Token", "")
    private val org = config.getString("Org", "")
    private val bucket = config.getString("Bucket", "")
    private val measurement = config.getString("Measurement", "")

    private var enabled = false

    private var session: InfluxDBClient? = null

    private fun connect() = if (username.isEmpty() && token.isEmpty())
        InfluxDBClientFactory.create(url)
    else if (username.isNotEmpty())
        InfluxDBClientFactory.create(url, username, password.toCharArray())
    else if (token.isNotEmpty())
        InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket)
    else null

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        vertx.executeBlocking(Callable {
            try {
                connect()?.let {
                    session = it
                    val response = it.ping()
                    if (response) {
                        enabled = true
                        result.complete()
                    } else {
                        it.logLevel = LogLevel.NONE
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
        val point = Point.measurement(if (measurement.isNotEmpty()) measurement else dp.topic.systemName)
            .time(dp.value.sourceTime(), WritePrecision.MS)
            .addTag("tag", dp.topic.getBrowsePathOrNode().toString()) // TODO: add topicName, topicType, topicNode, ...
            .addTag("address", dp.topic.topicNode)
            .addTag("status", dp.value.statusAsString())

        val numeric: Double? = dp.value.valueAsDouble()
        if (numeric != null) {
            //logger.debug("topic [$topic] numeric [$numeric]")
            point.addField("value", numeric)
        } else {
            //logger.debug("topic [$topic] text [${value.valueAsString()}]")
            point.addField("text", dp.value.valueAsString())
        }

        return point
    }

    override fun writeExecutor() {
        session?.writeApiBlocking?.let { api ->
            val batch = mutableListOf<Point>()
            pollDatapointBlock {
                batch.add(influxPointOf(it))
            }
            if (batch.isNotEmpty()) {
                try {
                    api.writePoints(batch)
                    commitDatapointBlock()
                    valueCounterOutput+=batch.size
                } catch (e: Exception) {
                    logger.severe("Error writing batch [${e.message}]")
                    commitDatapointBlock()

                }
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
            session?.queryApi?.let { api ->
                val data = api.query(
                    """
                    from(bucket: "$bucket")
                        |> range(start: $fromTimeNano, stop: $toTimeNano)
                        |> filter(fn: (r) => r["_measurement"] == "$system")
                        |> filter(fn: (r) => r["address"] == "$nodeId")
                        |> keep(columns: ["_time", "servertime", "_value", "text", "status"])
                    """.trimIndent()
                ).firstOrNull()?.let { table ->
                    table.records.map { record ->
                        listOf(
                            record.getValueByKey("_time"),
                            record.getValueByKey("servertime"),
                            record.getValueByKey("_value"),
                            record.getValueByKey("text"),
                            record.getValueByKey("status")
                        )
                    }
                }
                result(data != null, data)
            } ?: result(false, null)
        } catch (e: Exception) {
            logger.severe("Error executing query [${e.message}]")
            result(false, null)
        }
    }
}