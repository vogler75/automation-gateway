package at.rocworks.gateway.logger.neo4j

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.logger.LoggerBase
import at.rocworks.gateway.core.service.ServiceHandler
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status

import java.util.concurrent.TimeUnit

import kotlin.Any
import kotlin.Boolean
import kotlin.Exception
import kotlin.Long
import kotlin.String
import kotlin.Unit

import org.neo4j.driver.*
import org.neo4j.driver.Values.parameters
import java.io.File
import kotlin.concurrent.thread

class Neo4jLogger(private val config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "bolt://localhost:7687")
    private val username = config.getString("Username", "neo4j")
    private val password = config.getString("Password", "password")

    private val driver : Driver = GraphDatabase.driver( url, AuthTokens.basic( username, password ) );

    private var session : Session? = null

    override fun start(startPromise: Promise<Void>) {
        super.start(startPromise)
        val schemas = config.getJsonArray("Schemas", JsonArray()) ?: JsonArray()
        schemas.filterIsInstance<JsonObject>().map { systemConfig ->
            val system = systemConfig.getString("System")
            val nodeIds =
                systemConfig.getJsonArray("RootNodes", JsonArray(listOf("i=85"))).filterIsInstance<String>()
            fetchSchema(system, nodeIds).onComplete {
                logger.info("Write Graph [{}] [{}]", system, it.result().first)
                File("${system}.json").writeText(it.result().second.encodePrettily())
                if (it.result().first) {
                    thread { writeSchemaToDb(system, it.result().second) }
                }
            }
        }
    }

    private fun fetchSchema(system: String, nodeIds: List<String>): Future<Pair<Boolean, JsonObject>> { // TODO: copied from GraphQLServer
        val promise = Promise.promise<Pair<Boolean, JsonObject>>()
        val serviceHandler = ServiceHandler(vertx, logger)
        val type = Topic.SystemType.Opc.name
        logger.info("Wait for service [{}]...", system)
        serviceHandler.observeService(type, system) { record ->
            if (record.status == Status.UP) {
                logger.info("Request schema [{}] [{}] ...", system, nodeIds.joinToString(","))
                vertx.eventBus().request<JsonObject>(
                    "${type}/${system}/Schema",
                    JsonObject().put("NodeIds", nodeIds),
                    DeliveryOptions().setSendTimeout(60000L*10)) // TODO: configurable?
                {
                    logger.info("Schema response [{}] [{}] [{}]", system, it.succeeded(), it.cause()?.message ?: "")
                    val result = (it.result().body()?: JsonObject())
                    promise.complete(Pair(it.succeeded(), result))
                }
            }
        }
        return promise.future()
    }

    private fun writeSchemaToDb(system: String, schema: JsonObject) {
        try {
            schema.forEach { rootNode ->
                session?.writeTransaction { tx ->
                    println("RootNode ${rootNode.key}")
                    val res = tx.run(
                        "MERGE (n:OpcUaNode {DisplayName: \$DisplayName, System: \$System, NodeId: \$NodeId}) RETURN id(n)",
                        parameters("DisplayName", rootNode.key, "System", system, "NodeId", rootNode.key)
                    )
                    val parent = res.single()[0]

                    fun addNodes(parent: Value, nodes: JsonArray) {
                        nodes.filterIsInstance<JsonObject>()?.forEach {
                            val node = HashMap<String, Any>()
                            node["NodeId"] = it.getString("NodeId")
                            node["NodeClass"] = it.getString("NodeClass")
                            node["BrowseName"] = it.getString("BrowseName")
                            node["BrowsePath"] = it.getString("BrowsePath")
                            node["DisplayName"] = it.getString("DisplayName")
                            val res = tx.run(
                                """
                                MATCH (n1:OpcUaNode) WHERE id(n1) = ${"$"}Parent
                                MERGE (n2:OpcUaNode {System: ${"$"}System, NodeId: ${"$"}NodeId})
                                SET n2 += ${"$"}Node
                                MERGE (n1)-[:has]->(n2)
                                RETURN id(n2)
                                """.trimIndent(),
                                parameters(
                                    "Parent", parent,
                                    "System", system,
                                    "NodeId", node["NodeId"],
                                    "Node", node
                                )
                            )
                            val child = res.single()[0]
                            print(".")
                            addNodes(child, it.getJsonArray("Nodes", JsonArray()))
                        }
                    }

                    addNodes(parent, rootNode.value as? JsonArray ?: JsonArray())
                    println(".done")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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
              System : row.System,
              NodeId : row.NodeId
            }) 
            SET n += {
              Status : row.Status,
              Value : row.Value,
              DataType: row.DataType,
              ServerTime : row.ServerTime,
              SourceTime : row.SourceTime
            }  
            """.trimIndent()

        val rows = mutableListOf<Map<String, Any?>>()
        var point: DataPoint? = writeValueQueue.poll(10, TimeUnit.MILLISECONDS)
        while (point != null && ++counter <= writeParameterBlockSize) {
            val row = mapOf<String, Any?>(
                "System" to point.topic.systemName,
                "NodeId" to point.topic.address,
                "Status" to point.value.statusAsString(),
                "Value" to point.value.valueAsObject(),
                "DataType" to point.value.dataTypeName(),
                "ServerTime" to point.value.serverTimeAsISO(),
                "SourceTime" to point.value.sourceTimeAsISO())
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