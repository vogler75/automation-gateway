package at.rocworks.gateway.logger.neo4j

import at.rocworks.gateway.core.logger.LoggerBase
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.json.JsonObject

import java.util.concurrent.TimeUnit

import kotlin.Any
import kotlin.Boolean
import kotlin.Exception
import kotlin.Long
import kotlin.String
import kotlin.Unit

import org.neo4j.driver.*
import org.neo4j.driver.Values.parameters

class Neo4jLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "bolt://localhost:7687")
    private val username = config.getString("Username", "neo4j")
    private val password = config.getString("Password", "password")

    private val driver : Driver = GraphDatabase.driver( url, AuthTokens.basic( username, password ) );

    private var session : Session? = null

    override fun open(): Future<Unit> {
        logger.info("Open $username $password")
        val promise = Promise.promise<Unit>()
        try {
            this.session = driver.session()
            promise.complete()
        } catch (e: Exception) {
            promise.fail(e.message)
        }
        return promise.future()
    }

    override fun close() {
        driver.close()
    }

    override fun writeExecutor() {
        var counter = 0
        val query = """
            UNWIND ${"$"}rows AS row
            MERGE (n:OpcUaNode {
              system : row.system,
              address : row.address
            }) 
            SET n += {
              status : row.status,
              value : row.value,
              dataType: row.dataType,
              serverTime : row.serverTime,
              sourceTime : row.sourceTime
            }  
            """.trimIndent()

        val rows = mutableListOf<Map<String, Any?>>()
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && ++counter <= writeParameterBlockSize) {
            val row = mapOf<String, Any?>(
                "system" to point.topic.systemName,
                "address" to point.topic.address,
                "status" to point.value.statusAsString(),
                //"doubleValue" to point.value.valueAsDouble(),
                //"stringValue" to point.value.valueAsString(),
                "value" to point.value.valueAsObject(),
                "dataType" to point.value.dataTypeName(),
                "serverTime" to point.value.serverTimeAsISO(),
                "sourceTime" to point.value.sourceTimeAsISO())
            rows.add(row)
            point = writeValueQueue.poll()
        }
        if (counter > 0) {
            session?.writeTransaction { tx ->
                tx.run(query, parameters("rows", rows))
                valueCounterOutput += counter
            }
        }
    }

    override fun queryExecutor(
        system: String,
        nodeId: String,
        fromTimeMS: Long,
        toTimeMS: Long,
        result: (Boolean, List<List<Any>>?) -> Unit
    ) {
        result(false, null)
    }
}