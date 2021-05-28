package at.rocworks.gateway.logger.jdbc

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import java.lang.Exception
import java.sql.*
import java.util.concurrent.TimeUnit


class JdbcLogger(config: JsonObject) : LoggerBase(config) {
    private val defaultSqlInsert = """
        INSERT INTO EVENTS(nodeid, sourcetime, servertime, numericvalue, stringvalue, status)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT ON CONSTRAINT PK_EVENTS DO NOTHING 
    """.trimIndent()
    private val url = config.getString("Url", "jdbc:postgresql://localhost:5432/scada")
    private val username = config.getString("Username", "system")
    private val password = config.getString("Password", "manager")
    private val sqlInsert = config.getString("InsertSql", defaultSqlInsert)

    /*
    CREATE TABLE IF NOT EXISTS public.events
    (
        nodeid character varying(30) NOT NULL,
        sourcetime timestamp without time zone NOT NULL,
        servertime timestamp without time zone NOT NULL,
        numericvalue numeric,
        stringvalue text,
        status character varying(30) ,
        CONSTRAINT pk_events PRIMARY KEY (nodeid, sourcetime)
    )
    TABLESPACE ts_scada;
    */

    @Volatile
    private var connection: Connection? = null

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        try {
            logger.info("Open connection...")
            DriverManager.getConnection(url, username, password).let {
                it.autoCommit = false
                connection = it
                promise.complete()
                logger.info("Open connection done.")
            }
        } catch (e: SQLException) {
            promise.fail(e)
        }
        return promise.future()
    }

    override fun close() {
        connection?.let {
            if (!it.isClosed)
                it.close()
        }
    }

    override fun writeExecutor() {
        val connection = this.connection
        if (connection != null) {
            if (!connection.isClosed) {
                try {
                    connection.prepareStatement(sqlInsert).use(::writeBatch)
                    connection.commit()
                } catch (e: Exception) {
                    logger.error("Error writing batch [{}]", e.message)
                }
            } else {
                reconnect()
            }
        } else {
            Thread.sleep(1000)
        }
    }

    private val batchPoints = mutableListOf<DataPoint>()
    private fun writeBatch(batch: PreparedStatement) {
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && batchPoints.size < writeParameterBlockSize) {
            batchPoints.add(point)
            point = writeValueQueue.poll()
        }
        if (batchPoints.size > 0) {
            batchPoints.forEach {
                batch.setString(1, it.topic.address)
                batch.setTimestamp(2, Timestamp.from(it.value.sourceTime()))
                batch.setTimestamp(3, Timestamp.from(it.value.serverTime()))
                val doubleValue = it.value.valueAsDouble()
                if (doubleValue != null) {
                    batch.setDouble(4, doubleValue)
                    batch.setNull(5, Types.VARCHAR)
                } else {
                    batch.setNull(4, Types.NUMERIC)
                    batch.setString(5, it.value.valueAsString())
                }
                batch.setString(6, it.value.statusAsString())
                batch.addBatch()
            }
            batch.execute()
            batchPoints.clear()
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeNano: Long,
        toTimeNano: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        result(false, null)
    }
}