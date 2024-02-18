package at.rocworks.gateway.logger.duckdb

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import org.duckdb.DuckDBConnection
import java.sql.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class DuckDBLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "jdbc:duckdb:")

    private val sqlTableName = config.getString("SqlTableName", "events")

    private val sqlCreateTable = listOf("""
        CREATE TABLE IF NOT EXISTS $sqlTableName
        (
            sys varchar not null,
            nodeid varchar not null,
            browsepath varchar not null,
            sourcetime timestamptz not null,
            servertime timestamptz,
            numericvalue double,
            stringvalue text,
            status varchar
        );
    """.trimIndent())

    private var connection: DuckDBConnection? = null

    init {
    }

    override fun start(startPromise: Promise<Void>) {
        super.start(startPromise)
        logger.info("Start")
    }

    override fun open(): Future<Unit> {
        logger.info("Open: $url")
        val promise = Promise.promise<Unit>()

        try {
            Class.forName("org.duckdb.DuckDBDriver")
            DriverManager.getConnection(url).let { connection ->
                this.connection = connection as DuckDBConnection
                sqlCreateTable.forEach { sql ->
                    connection.createStatement().use { statement ->
                        try {
                            statement.execute(sql)
                        } catch (e: Exception) {
                            logger.warning("Create table exception [${e.message}]")
                        }
                    }
                }
            }
            promise.complete()
        } catch (e: SQLException) {
            e.printStackTrace()
            promise.fail(e)
        }
        return promise.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        connection?.let {
            if (!it.isClosed) it.close()
        }

        promise.complete()
        return promise.future()
    }

    override fun writeExecutor() {
        val connection = this.connection
        if (connection != null) {
            if (!connection.isClosed) {
                try {
                    writeBatch(connection)
                } catch (e: Exception) {
                    logger.severe("Error writing batch [${e.message}]")
                }
            } else {
                reconnect()
            }
        } else {
            Thread.sleep(1000)
        }
    }

    private val batchPoints = mutableListOf<DataPoint>()
    private fun writeBatch(connection: DuckDBConnection) {
        var point: DataPoint? = pollDatapointWait()
        while (point != null && batchPoints.size < writeParameterBlockSize) {
            batchPoints.add(point)
            point = pollDatapointNoWait()
        }
        if (batchPoints.size > 0) {
            connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, sqlTableName).use { appender ->
                batchPoints.forEach {
                    //println(it.topic.encodeToJson().toString())
                    appender.beginRow()
                    appender.append(it.topic.systemName)
                    appender.append(it.topic.node)
                    appender.append(it.topic.browsePath)
                    appender.appendLocalDateTime(LocalDateTime.ofInstant(it.value.sourceTime, ZoneOffset.UTC))
                    appender.appendLocalDateTime(LocalDateTime.ofInstant(it.value.serverTime, ZoneOffset.UTC))
                    val doubleValue = it.value.valueAsDouble()
                    if (doubleValue != null && !doubleValue.isNaN()) {
                        appender.append(doubleValue)
                        appender.append(null)
                    } else {
                        appender.append(null)
                        appender.append(it.value.valueAsString())
                    }
                    appender.append(it.value.statusAsString())
                    appender.endRow()
                }
            }
            valueCounterOutput+=batchPoints.size
            batchPoints.clear()
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        TODO("Not yet implemented")
    }

    override fun getComponentGroup(): ComponentGroup {
        return ComponentGroup.Logger
    }
}