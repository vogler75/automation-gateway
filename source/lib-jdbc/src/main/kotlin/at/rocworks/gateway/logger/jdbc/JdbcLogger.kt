package at.rocworks.gateway.logger.jdbc

import at.rocworks.gateway.core.logger.LoggerBase
import at.rocworks.gateway.core.service.Common
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import java.sql.*
import java.util.concurrent.TimeUnit


class JdbcLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "jdbc:postgresql://localhost:5432/scada")
    private val username = config.getString("Username", "system")
    private val password = config.getString("Password", "manager")

    private val sqlTableName = config.getString("SqlTableName", "events")

    // --------------------------------------------------------------------------------------------------------------

    private val sqlCreateTablePostgreSQL = """
        CREATE TABLE IF NOT EXISTS $sqlTableName
        (
            sys character varying(30) NOT NULL,
            nodeid character varying(30) NOT NULL,
            sourcetime timestamp without time zone NOT NULL,
            servertime timestamp without time zone NOT NULL,
            numericvalue numeric,
            stringvalue text,
            status character varying(30) ,
            CONSTRAINT pk_events PRIMARY KEY (sys, nodeid, sourcetime)
        );        
    """.trimIndent()

    private val sqlCreateTableMySQL = """
        CREATE TABLE IF NOT EXISTS $sqlTableName
        (
            sys varchar(30) NOT NULL,
            nodeid varchar(30) NOT NULL,
            sourcetime timestamp(6) NOT NULL,
            servertime timestamp(6) NOT NULL,
            numericvalue double,
            stringvalue text,
            status varchar(30) ,
            CONSTRAINT pk_events PRIMARY KEY (sys, nodeid, sourcetime)
        );        
    """.trimIndent()

    private val sqlCreateTableMsSQL = """
        CREATE TABLE $sqlTableName
        (
            sys character varying(30) NOT NULL,
            nodeid character varying(30) NOT NULL,
            sourcetime datetime2 NOT NULL,
            servertime datetime2 NOT NULL,
            numericvalue numeric,
            stringvalue text,
            status character varying(30) ,
            CONSTRAINT pk_events PRIMARY KEY (sys, nodeid, sourcetime) WITH (IGNORE_DUP_KEY = ON)
        );        
    """.trimIndent()

    // --------------------------------------------------------------------------------------------------------------

    private var sqlInsertStatement = config.getString("SqlInsertStatement", "")

    private val sqlInsertStatementPostgreSQL =  """
        INSERT INTO $sqlTableName (sys, nodeid, sourcetime, servertime, numericvalue, stringvalue, status)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT ON CONSTRAINT PK_EVENTS DO NOTHING 
        """.trimIndent()

    private val sqlInsertStatementMySQL = """
        INSERT IGNORE INTO $sqlTableName (sys, nodeid, sourcetime, servertime, numericvalue, stringvalue, status)
        VALUES (?, ?, ?, ?, ?, ?, ?)        
        """.trimIndent()

    private val sqlInsertStatementMsSQL = """
        INSERT INTO $sqlTableName (sys, nodeid, sourcetime, servertime, numericvalue, stringvalue, status)
        VALUES (?, ?, ?, ?, ?, ?, ?)        
        """.trimIndent()

    // --------------------------------------------------------------------------------------------------------------

    private val sqlQueryStatement = config.getString("SqlQueryStatement", """
        SELECT sourcetime, servertime, numericvalue, stringvalue, status
         FROM $sqlTableName 
         WHERE sys = ? AND nodeid = ? AND sourcetime >= ? AND sourcetime <= ? 
        """.trimIndent())

    // --------------------------------------------------------------------------------------------------------------

    @Volatile
    private var connection: Connection? = null

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        try {
            logger.info("Open connection...")
            DriverManager.getConnection(url, username, password).let {
                it.autoCommit = false

                // Insert SQL
                sqlInsertStatement = when (it.metaData.databaseProductName) {
                    "PostgreSQL" -> sqlInsertStatementPostgreSQL
                    "MySQL" -> sqlInsertStatementMySQL
                    "Microsoft SQL Server" -> sqlInsertStatementMsSQL
                    else -> sqlInsertStatement
                }

                if (sqlInsertStatement == "") {
                    logger.error("Please provide a sqlInsertStatement in config file!")
                }

                // Create Table
                when (it.metaData.databaseProductName) {
                    "PostgreSQL" -> sqlCreateTablePostgreSQL
                    "MySQL" -> sqlCreateTableMySQL
                    "Microsoft SQL Server" -> sqlCreateTableMsSQL
                    else -> null
                }?.let { sql ->
                    it.createStatement().use { statement ->
                        try {
                            statement.execute(sql)
                        } catch (e: Exception) {
                            logger.warn("Create table exception [{}]", e.message)
                        }
                    }
                }

                connection = it
                promise.complete()
                logger.info("Open connection done [{}] [{}]", it.metaData.databaseProductName, it.metaData.databaseProductVersion)
            }
        } catch (e: SQLException) {
            e.printStackTrace()
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
                    connection.prepareStatement(sqlInsertStatement).use(::writeBatch)
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
                batch.setString(1, it.topic.systemName)
                batch.setString(2, it.topic.address)
                batch.setTimestamp(3, Timestamp.from(it.value.sourceTime()))
                batch.setTimestamp(4, Timestamp.from(it.value.serverTime()))
                val doubleValue = it.value.valueAsDouble()
                if (doubleValue != null) {
                    batch.setDouble(5, doubleValue)
                    batch.setNull(6, Types.VARCHAR)
                } else {
                    batch.setNull(5, Types.NUMERIC)
                    batch.setString(6, it.value.valueAsString())
                }
                batch.setString(7, it.value.statusAsString())
                batch.addBatch()
            }
            batch.executeBatch()
            batch.connection.commit()
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
        val connection = this.connection
        if (connection != null)
        {
            try {
                connection.prepareStatement(sqlQueryStatement) .use { stmt ->
                    val result = mutableListOf<List<Any>>()
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
                        result.add(
                            listOf(
                                rs.getTimestamp(1).toInstant(), // sourcetime
                                rs.getTimestamp(2).toInstant(), // servertime
                                value, // value
                                rs.getString(5)  // status
                            )
                        )
                    }
                    result(true, result)
                }
            } catch (e: SQLException) {
                logger.error("Error executing query [{}]", e.message)
                result(false, null)
            }
        } else {
            result(false, null)
        }
    }
}