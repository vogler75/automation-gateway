package at.rocworks.gateway.logger.cassandra

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.oss.driver.api.core.cql.PreparedStatement

import java.net.InetSocketAddress

/*
  CREATE TABLE IF NOT EXISTS frankenstein (
    sys TEXT,
    nodeid TEXT,
    address TEXT,
    sourcetime TIMESTAMP,
    servertime TIMESTAMP,
    numericvalue DECIMAL,
    stringvalue TEXT,
    status TEXT,
    PRIMARY KEY ((sys, nodeid), sourcetime)
);
 */

class CassandraLogger(config: JsonObject) : LoggerBase(config) {

    private val host = config.getString("Host", "localhost")
    private val port = config.getInteger("Port", 9042)
    private val datacenter = config.getString("Datacenter", "datacenter1")
    private val keyspace = config.getString("Keyspace", "gateway")
    private val table = config.getString("Table", "frankenstein")
    private val username = config.getString("Username", "")
    private val password = config.getString("Password", "")

    private var session: CqlSession? = null

    override fun open(): Future<Unit> {
        val result = Promise.promise<Unit>()
        try {
            // Create a Cassandra session
            val builder = CqlSession.builder()
                .addContactPoint(InetSocketAddress(host, port))
                .withKeyspace(keyspace)
                .withLocalDatacenter(datacenter)
            if (username.isNotEmpty() && password.isNotEmpty())
                builder.withAuthCredentials(username, password)
            session = builder.build()
            logger.info("Cassandra built.")
            result.complete()
        } catch (e: Exception) {
            logger.severe("Cassandra built failed! [${e.message}]")
            e.printStackTrace()
            result.fail(e)
        }
        return result.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        session?.closeAsync()?.whenComplete {
            _, e ->
            if (e != null) {
                logger.severe("Cassandra close failed! [${e.message}]")
                promise.fail(e)
            } else {
                session = null
                logger.info("Cassandra closed.")
                promise.complete()
            }
        }
        return promise.future()
    }

    override fun isEnabled(): Boolean {
        return session != null
    }

    override fun writeExecutor() {
        session?.let { session ->
            // Prepare the INSERT statement
            val insertQuery = """
                INSERT INTO $table (sys, nodeid, address, sourcetime, servertime, numericvalue, stringvalue, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            val preparedStatement: PreparedStatement = session.prepare(insertQuery)

            // Create a batch statement builder
            val batchBuilder = BatchStatement.builder(BatchType.UNLOGGED)

            val size = pollDatapointBlock { it ->
                val boundStatement = preparedStatement.bind(
                    it.topic.systemName,
                    it.topic.getNodeOrBrowsePath(),
                    it.topic.getBrowsePathOrNode().toString(),
                    it.value.sourceTime,
                    it.value.serverTime,
                    it.value.valueAsDouble()?.toBigDecimal(),
                    it.value.valueAsString(),
                    it.value.statusCode
                )
                batchBuilder.addStatement(boundStatement)
            }

            if (size > 0) {
                session.execute(batchBuilder.build())
                valueCounterOutput+=size
            }

            commitDatapointBlock()
        }
    }
}