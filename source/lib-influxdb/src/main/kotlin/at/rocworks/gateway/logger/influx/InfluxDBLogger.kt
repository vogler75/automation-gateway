package at.rocworks.gateway.logger.influx

import at.rocworks.gateway.core.logger.LoggerBase

import io.vertx.core.json.JsonObject
import java.util.concurrent.TimeUnit

import org.influxdb.BatchOptions
import org.influxdb.InfluxDB
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.*

class InfluxDBLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")
    private val database = config.getString("Database", "scada")

    private val session: InfluxDB = if (username == null || username == "")
        InfluxDBFactory.connect(url)
    else
        InfluxDBFactory.connect(url, username, password)

    override fun open(): Boolean {
        return try {
            val response: Pong = session.ping()
            if (!response.isGood) {
                false
            } else {
                session.setLogLevel(InfluxDB.LogLevel.NONE)
                session.query(Query("CREATE DATABASE $database"))
                session.setDatabase(database)
                var options = BatchOptions.DEFAULTS
                options = options.bufferLimit(writeParameterBlockSize)
                session.enableBatch(options)
                logger.info("InfluxDB connected.")
                true
            }
        } catch (e: Exception) {
            logger.error("InfluxDB connect failed! [{}]", e.message)
            false
        }
    }

    override fun close() {
        session.close()
    }

    private fun influxPointOf(dp: DataPoint): Point {
        val point = Point.measurement(dp.topic.systemName)
            .time(dp.value.sourceTime().toEpochMilli(), TimeUnit.MILLISECONDS)
            .tag("tag", dp.topic.browsePath)
            .tag("address", dp.topic.address)
            .tag("status", dp.value.statusAsString())

        if (dp.value.hasStruct()) {
            dp.value.asFlatMap().forEach { (k, v) ->
                val d = v.toString().toDoubleOrNull()
                if (d!=null) point.addField(k, d)
                else point.addField(k, v.toString())
            }
        } else {
            val numeric: Double? = dp.value.valueAsDouble()
            if (numeric != null) {
                //logger.debug("topic [$topic] numeric [$numeric]")
                point.addField("value", numeric)
            } else {
                //logger.debug("topic [$topic] text [${value.valueAsString()}]")
                point.addField("text", dp.value.valueAsString())
            }
        }
        return point.build()
    }

    override fun writeExecutor() {
        val batch = BatchPoints.database(database).build()
        var point = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && batch.points.size <= writeParameterBlockSize) {
            batch.point(influxPointOf(point))
            point = writeValueQueue.poll()
        }
        if (batch.points.size > 0) {
            session.write(batch)
            valueCounterOutput+=batch.points.size
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeNano: Long,
        toTimeNano: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        try {
            val sql = "SELECT value, status FROM \"${system}\" WHERE \"tag\" = '$nodeId' AND time >= $fromTimeNano AND time <= $toTimeNano"
            val data = session.query(Query(sql)).let {
                it.results.getOrNull(0)?.series?.getOrNull(0)?.values
            }
            result(data != null, data)
        } catch (e: Exception) {
            result(false, null)
            e.printStackTrace()
        }
    }
}