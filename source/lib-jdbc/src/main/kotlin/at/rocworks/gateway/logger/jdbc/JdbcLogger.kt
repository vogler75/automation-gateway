package at.rocworks.gateway.logger.jdbc

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject
import java.sql.*
import java.time.OffsetDateTime


class JdbcLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "jdbc:postgresql://localhost:5432/postgres")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")

    private val sqlTableName = config.getString("SqlTableName", "frankenstein")

    // --------------------------------------------------------------------------------------------------------------

    private val sqlCreateTablePostgreSQL = listOf("""
        CREATE TABLE IF NOT EXISTS $sqlTableName
        (
            sys character varying(1000) NOT NULL,
            nodeid character varying(1000) NOT NULL,
            address text NULL,
            sourcetime timestamp with time zone NOT NULL,
            servertime timestamp with time zone NOT NULL,
            numericvalue numeric,
            stringvalue text,
            status character varying(30) ,
            CONSTRAINT ${sqlTableName}_pk PRIMARY KEY (sys, nodeid, sourcetime)
        );
    """.trimIndent(),
        "CREATE INDEX ${sqlTableName}_sourcetime_ix ON ${sqlTableName}(sourcetime);")

    private val sqlCreateTableMySQL = listOf("""
        CREATE TABLE IF NOT EXISTS $sqlTableName
        (
            sys varchar(1000) NOT NULL,
            nodeid varchar(1000) NOT NULL,
            address text,
            sourcetime timestamp(6) NOT NULL,
            servertime timestamp(6) NOT NULL,
            numericvalue double,
            stringvalue text,
            status varchar(30) ,
            CONSTRAINT ${sqlTableName}_pk PRIMARY KEY (sys, nodeid, sourcetime)
        );        
    """.trimIndent(),
        "CREATE INDEX ${sqlTableName}_sourcetime_ix ON ${sqlTableName}(sourcetime);")

    private val sqlCreateTableMsSQL = listOf("""
        CREATE TABLE $sqlTableName
        (
            sys character varying(1000) NOT NULL,
            nodeid character varying(1000) NOT NULL,
            address varchar(MAX),
            sourcetime datetime2 NOT NULL,
            servertime datetime2 NOT NULL,
            numericvalue real,
            stringvalue varchar(MAX),
            status character varying(30) ,
            CONSTRAINT ${sqlTableName}_pk PRIMARY KEY (sys, nodeid, sourcetime) WITH (IGNORE_DUP_KEY = ON)
        );        
    """.trimIndent(),
        "CREATE INDEX ${sqlTableName}_sourcetime_ix ON ${sqlTableName}(sourcetime);")

    private val sqlCreateTableCrate = listOf("""
        CREATE TABLE IF NOT EXISTS $sqlTableName (
          "sys" TEXT, 
          "nodeid" TEXT,      
          "address" TEXT,
          "sourcetime" TIMESTAMP WITH TIME ZONE,
          "servertime" TIMESTAMP WITH TIME ZONE,
          "sourcetime_month" TIMESTAMP WITH TIME ZONE GENERATED ALWAYS AS date_trunc('month', "sourcetime"),
          "numericvalue" DOUBLE,
          "stringvalue" TEXT,
          "status" TEXT,
          PRIMARY KEY (sourcetime_month, sourcetime, sys, nodeid)
        ) CLUSTERED INTO 4 SHARDS PARTITIONED BY ("sourcetime_month");   
    """.trimIndent())

    private val sqlCreateTableHSQL = listOf("""
        CREATE TABLE IF NOT EXISTS $sqlTableName
        (
            sys varchar(30) NOT NULL,
            nodeid varchar(30) NOT NULL,
            address longvarchar,
            sourcetime timestamp with time zone NOT NULL,
            servertime timestamp with time zone NOT NULL,
            numericvalue numeric,
            stringvalue longvarchar,
            status varchar(30) ,
            CONSTRAINT ${sqlTableName}_pk PRIMARY KEY (sys, nodeid, sourcetime)
        );
    """.trimIndent())

    // --------------------------------------------------------------------------------------------------------------

    private var sqlInsertStatement = config.getString("SqlInsertStatement", "")

    private val sqlInsertStatementPostgreSQL =  """
        INSERT INTO $sqlTableName (sys, nodeid, address, sourcetime, servertime, numericvalue, stringvalue, status)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT DO NOTHING 
        """.trimIndent()

    private val sqlInsertStatementMySQL = """
        INSERT IGNORE INTO $sqlTableName (sys, address, nodeid, sourcetime, servertime, numericvalue, stringvalue, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)        
        """.trimIndent()

    private val sqlInsertStatementMsSQL = """
        INSERT INTO $sqlTableName (sys, nodeid, address, sourcetime, servertime, numericvalue, stringvalue, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)        
        """.trimIndent()

    private val sqlInsertStatementCrate =  """
        INSERT INTO $sqlTableName (sys, nodeid, address, sourcetime, servertime, numericvalue, stringvalue, status)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT DO NOTHING
        """.trimIndent()

    private val sqlInsertStatementHSQL = """
        INSERT INTO $sqlTableName (sys, nodeid, address, sourcetime, servertime, numericvalue, stringvalue, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)        
        """.trimIndent()

    // --------------------------------------------------------------------------------------------------------------

    private val sqlQueryStatement = config.getString("SqlQueryStatement", """
        SELECT sourcetime, servertime, numericvalue, stringvalue, status
         FROM $sqlTableName 
         WHERE sys = ? AND nodeid = ? AND sourcetime >= ? AND sourcetime <= ? 
        """.trimIndent())

    // --------------------------------------------------------------------------------------------------------------

    @Volatile
    private var writeConnection: Connection? = null
    private var readConnection: Connection? = null

    override fun open(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        try {
            logger.info("Open connection...")
            getConnection().let { connection ->
                logger.info("Database product name [${connection.metaData.databaseProductName}]")
                connection.autoCommit = false

                // Set write connection
                this.writeConnection = connection

                // Create a  read Connection
                this.readConnection = getConnection()

                // Insert SQL
                sqlInsertStatement = when (connection.metaData.databaseProductName) {
                    "MySQL" -> sqlInsertStatementMySQL
                    "Crate" -> sqlInsertStatementCrate
                    "PostgreSQL" -> sqlInsertStatementPostgreSQL
                    "Microsoft SQL Server" -> sqlInsertStatementMsSQL
                    "HSQL Database Engine" -> sqlInsertStatementHSQL
                    else -> sqlInsertStatement
                }

                if (sqlInsertStatement == "") {
                    logger.severe("Please provide a sqlInsertStatement in config file!")
                }

                // Create Table
                when (connection.metaData.databaseProductName) {
                    "MySQL" -> sqlCreateTableMySQL
                    "Crate" -> sqlCreateTableCrate
                    "PostgreSQL" -> sqlCreateTablePostgreSQL
                    "Microsoft SQL Server" -> sqlCreateTableMsSQL
                    "HSQL Database Engine" -> sqlCreateTableHSQL
                    else -> listOf<String>()
                }.forEach { sql ->
                    connection.createStatement().use { statement ->
                        try {
                            statement.execute(sql)
                        } catch (e: Exception) {
                            logger.warning("Create table exception [${e.message}]", )
                        }
                        connection.commit()
                    }
                }

                promise.complete()
                logger.info("Open connection done [${connection.metaData.databaseProductName}] [${connection.metaData.databaseProductVersion}]")
            }
        } catch (e: SQLException) {
            e.printStackTrace()
            promise.fail(e)
        }
        return promise.future()
    }

    private fun getConnection(): Connection = (if (username == "" && password == "")
        DriverManager.getConnection(url)
    else
        DriverManager.getConnection(url, username, password))

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        readConnection?.let { if (!it.isClosed) it.close() }
        writeConnection?.let { if (!it.isClosed) it.close() }
        promise.complete()
        return promise.future()
    }

    override fun writeExecutor() {
        val connection = this.writeConnection
        if (connection != null) {
            if (!connection.isClosed) {
                try {
                    connection.prepareStatement(sqlInsertStatement).use(::writeBatch)
                    commitDatapointBlock()
                } catch (e: Exception) {
                    logger.severe("Error writing batch [${e.message}]")
                }
            } else {
                Thread.sleep(1000)
                reconnect()
            }
        } else {
            Thread.sleep(1000)
        }
    }

    private fun writeBatch(batch: PreparedStatement) {
        val size = pollDatapointBlock {
            batch.setString(1, it.topic.systemName)
            batch.setString(2, it.topic.topicNode)
            batch.setString(3, it.topic.getBrowsePathOrNode().toString())
            batch.setTimestamp(4, Timestamp.from(it.value.sourceTime()))
            batch.setTimestamp(5, Timestamp.from(it.value.serverTime()))
            val doubleValue = it.value.valueAsDouble()
            if (doubleValue != null && !doubleValue.isNaN()) {
                batch.setDouble(6, doubleValue)
                batch.setNull(7, Types.VARCHAR)
            } else {
                batch.setNull(6, Types.DOUBLE)
                batch.setString(7, it.value.valueAsString())
            }
            batch.setString(8, it.value.statusAsString())
            batch.addBatch()
        }
        if (size > 0) {
            batch.executeBatch()
            batch.connection.commit()
            valueCounterOutput+=size
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any?>>?) -> Unit
    ) {
        val connection = this.readConnection
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
                        // source time, server time, numeric value, string value, status
                        val value = if (rs.getObject(3) != null) {
                            rs.getDouble(3)
                        } else {
                            rs.getString(4)
                        }
                        data.add(
                            listOf(
                                rs.getTimestamp(1).toInstant(), // sourcetime
                                rs.getTimestamp(2).toInstant(), // servertime
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
        val connection = this.readConnection
        if (connection != null)
        {
            try {
                connection.prepareStatement(sql).use { stmt ->
                    val data = mutableListOf<List<Any?>>()
                    val rs = stmt.executeQuery()
                    val range = 1..rs.metaData.columnCount
                    data.add(range.map { rs.metaData.getColumnName(it) })
                    while (rs.next()) {
                        data.add(range.map { index ->
                            val value = rs.getObject(index)
                            if (value == null) null
                            else when (value) {
                                is OffsetDateTime -> value.toString()
                                else -> value
                            }
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