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
        val dollar = "$"

        session?.writeTransaction { tx ->
            val query = """
                    MERGE (n:OpcUaNode {
                      system : ${dollar}system,
                      address : ${dollar}address
                    }) 
                    SET n += {
                      status : ${dollar}status,
                      doubleValue : ${dollar}doubleValue,                           
                      serverTime : ${dollar}serverTime,
                      sourceTime : ${dollar}sourceTime
                    }  
                """.trimIndent()

            var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
            while (point != null && ++counter <= writeParameterBlockSize) {
                tx.run(query,
                    parameters(
                        "system", point.topic.systemName,
                        "address", point.topic.address,
                        "status", point.value.statusAsString(),
                        "doubleValue", point.value.valueAsDouble(),
                        "serverTime", point.value.serverTimeAsISO(),
                        "sourceTime", point.value.sourceTimeAsISO()
                    )
                )
                point = writeValueQueue.poll()
            }
            valueCounterOutput += counter
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