package at.rocworks.gateway.logger.duckdb

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import org.duckdb.DuckDBConnection
import java.sql.*
import java.time.*

class DuckDBLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "jdbc:duckdb:")

    private val sqlTableName = config.getString("SqlTableName", "events")

    private val sqlCreateTable = listOf("""
        CREATE TABLE IF NOT EXISTS $sqlTableName
        (
            sys varchar not null,
            nodeid varchar not null,
            browsepath varchar not null,
            sourcetime timestamp with time zone not null,
            servertime timestamp with time zone not null,
            numericvalue double,
            stringvalue text,
            status varchar
        );
    """.trimIndent())

    private val sqlQueryStatement = config.getString("SqlQueryStatement", """
        SELECT epoch_ms(sourcetime), epoch_ms(servertime), numericvalue, stringvalue, status
         FROM $sqlTableName 
         WHERE sys = ? AND nodeid = ? AND sourcetime >= ?::timestamptz AND sourcetime <= ?::timestamptz
        """.trimIndent())

    private var connection: DuckDBConnection? = null

    init {
    }

    override fun start(startPromise: Promise<Void>) {
        super.start(startPromise)
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
                promise.complete()
            }
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

    private fun writeBatch(connection: DuckDBConnection) {
        connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, sqlTableName).use { appender ->
            valueCounterOutput += pollDatapointBlock {
                appender.beginRow()
                appender.append(it.topic.systemName)
                appender.append(it.topic.topicNode)
                appender.append(it.topic.getBrowsePathOrNode().toString())
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
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any?>>?) -> Unit
    ) {
        val connection = this.connection
        if (connection != null)
        {
            try {
                connection.prepareStatement(sqlQueryStatement).use { stmt ->
                    val data = mutableListOf<List<Any>>()
                    stmt.setString(1, system)
                    stmt.setString(2, nodeId)
                    stmt.setTimestamp(3, Timestamp(fromTimeMS))
                    stmt.setTimestamp(4, Timestamp(toTimeMS))
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        // sourcetime, servertime, numericvalue, stringvalue, status
                        val value = if (rs.getObject(3) != null) {
                            rs.getDouble(3)
                        } else {
                            rs.getString(4)
                        }
                        //println(rs.getTimestamp(1).toString() + " = " + rs.getTimestamp(1).toInstant().toString())
                        data.add(
                            listOf(
                                Instant.ofEpochMilli(rs.getLong(1)), // sourcetime
                                Instant.ofEpochMilli(rs.getLong(2)), // servertime
                                value, // value
                                rs.getString(5)  // status
                            )
                        )
                    }
                    result(true, data)
                }
            } catch (e: SQLException) {
                logger.severe("Error executing query [${e.message}]")
                result(false, null)
            }
        } else {
            result(false, null)
        }
    }

    override fun sqlExecutor(sql: String, result: (Boolean, List<List<Any?>>) -> Unit) {
        val connection = this.connection
        if (connection != null)
        {
            try {
                connection.prepareStatement(sql).use { stmt ->
                    val data = mutableListOf<List<Any>>()
                    val rs = stmt.executeQuery()
                    val range = 1..rs.metaData.columnCount
                    data.add(range.map { rs.metaData.getColumnName(it) })
                    while (rs.next()) {
                        data.add(range.map { index ->
                            rs.getObject(index)
                        })
                    }
                    result(true, data)
                }
            } catch (e: SQLException) {
                logger.severe("Error executing query [${e.message}]")
                result(true, listOf(listOf(e.message ?: "Unknown error")))
            }
        } else {
            result(true, listOf(listOf("No connection")))
        }
    }
}