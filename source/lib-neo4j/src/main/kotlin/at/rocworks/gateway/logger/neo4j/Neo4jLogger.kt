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

import java.time.Duration
import java.time.Instant

import kotlin.concurrent.thread

class Neo4jLogger(private val config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "bolt://localhost:7687")
    private val username = config.getString("Username", "neo4j")
    private val password = config.getString("Password", "password")

    private val driver : Driver = GraphDatabase.driver( url, AuthTokens.basic( username, password ) )

    private var session : Session? = null

    override fun start(startPromise: Promise<Void>) {
        super.start(startPromise)
        val schemas = config.getJsonArray("Schemas", JsonArray()) ?: JsonArray()
        schemas.filterIsInstance<JsonObject>().map { systemConfig ->
            val system = systemConfig.getString("System")
            val nodeIds =
                systemConfig.getJsonArray("RootNodes", JsonArray(listOf("i=85"))).filterIsInstance<String>()
            fetchSchema(system, nodeIds).onComplete {
                logger.info("Write Graph [${system}] [${it.result().first}]")
                //File("${system}.json").writeText(it.result().second.encodePrettily())
                if (it.result().first) {
                    thread { writeSchemaToDb(system, it.result().second) }
                }
            }
        }
    }

    private fun fetchSchema(system: String, nodeIds: List<String>): Future<Pair<Boolean, JsonArray>> { // TODO: copied from GraphQLServer
        val promise = Promise.promise<Pair<Boolean, JsonArray>>()
        val serviceHandler = ServiceHandler(vertx, logger)
        val type = Topic.SystemType.Opc.name
        logger.info("Wait for service [${system}]...")
        serviceHandler.observeService(type, system) { record ->
            if (record.status == Status.UP) {
                logger.info("Request schema [${system}] [${nodeIds.joinToString(",")}] ...")
                vertx.eventBus().request<JsonArray>(
                    "${type}/${system}/Schema",
                    JsonObject().put("NodeIds", nodeIds),
                    DeliveryOptions().setSendTimeout(60000L*10)) // TODO: configurable?
                {
                    logger.info("Schema response [${system}] [${it.succeeded()}] [${it.cause()?.message ?: ""}]")
                    val result = (it.result().body()?: JsonArray())
                    promise.complete(Pair(it.succeeded(), result))
                }
            }
        }
        return promise.future()
    }

    private fun writeSchemaToDb(system: String, schema: JsonArray) {
        try {
            logger.info("Write schema to graph database...")
            val tStart = Instant.now()
            val session = driver.session()

            session.run("""
                CREATE INDEX IF NOT EXISTS
                FOR (n:OpcUaNode)
                ON (n.System, n.NodeId)
                """.trimIndent())

            // Create and get system
            val systemId = session.run("MERGE (s:System { DisplayName: \$DisplayName }) RETURN ID(s)",
                parameters("DisplayName", system)).single()[0]

            schema.filterIsInstance<JsonObject>().forEach { rootNode ->
                session?.writeTransaction { tx ->
                    // Create root node
                    val rootId = tx.run(
                        """
                        MERGE (n:OpcUaRoot {System: ${"$"}System, NodeId: ${"$"}NodeId}) 
                        SET n += {
                          NodeClass: ${"$"}NodeClass,
                          BrowseName: ${"$"}BrowseName,
                          DisplayName: ${"$"}DisplayName
                        }
                        RETURN ID(n)
                        """.trimIndent(),
                        parameters(
                            "System", system,
                            "NodeId", rootNode.getValue("NodeId"),
                            "NodeClass", rootNode.getValue("NodeClass"),
                            "BrowseName", rootNode.getValue("BrowseName"),
                            "DisplayName", rootNode.getValue("DisplayName"))
                    ).single()[0]

                    // System to root node relation
                    tx.run("""
                        MATCH (n1:System) WHERE ID(n1) = ${"$"}SystemId
                        MATCH (n2:OpcUaRoot) WHERE ID(n2) = ${"$"}RootId
                        MERGE (n1)-[:HAS]->(n2)
                        """.trimIndent(),
                        parameters("SystemId", systemId, "RootId", rootId))

                    fun addNodes(parent: Value, nodes: JsonArray) {
                        val rows = mutableListOf<Map<String, Any?>>()
                        val items = nodes.filterIsInstance<JsonObject>()
                        items.forEach {
                            val node = HashMap<String, Any>()
                            node["NodeId"] = it.getString("NodeId")
                            node["NodeClass"] = it.getString("NodeClass")
                            node["BrowseName"] = it.getString("BrowseName")
                            node["BrowsePath"] = it.getString("BrowsePath")
                            node["DisplayName"] = it.getString("DisplayName")
                            node["ReferenceType"] = it.getString("ReferenceType")
                            rows.add(node)
                        }
                        val res = tx.run(
                            """
                            UNWIND ${"$"}rows AS node
                            MATCH (n1) WHERE ID(n1) = ${"$"}Parent
                            MERGE (n2:OpcUaNode {System: ${"$"}System, NodeId: node.NodeId})
                            SET n2 += {
                              NodeClass : node.NodeClass,
                              BrowseName : node.BrowseName,
                              BrowsePath : node.BrowsePath,
                              DisplayName : node.DisplayName
                            }
                            MERGE (n1)-[:HAS {ReferenceType: node.ReferenceType}]->(n2)
                            RETURN ID(n2)
                            """.trimIndent(),
                            parameters(
                                "Parent", parent,
                                "System", system,
                                "rows", rows
                            )
                        )
                        //logger.info("Wrote ${rows.size} nodes.")
                        items.zip(res.list()).forEach {
                            val nextNodes = it.first.getJsonArray("Nodes")
                            if (nextNodes != null && !nextNodes.isEmpty) {
                                addNodes(it.second[0], nextNodes)
                            }
                        }
                    }

                    addNodes(rootId, rootNode.getJsonArray("Nodes", JsonArray()))
                }
            }
            val duration = Duration.between(tStart, Instant.now())
            val seconds = duration.seconds + duration.nano/1_000_000_000.0
            logger.warning("Writing schema to graph database took [${seconds}]s")

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
            val row = mapOf(
                "System" to point.topic.systemName,
                "NodeId" to point.topic.node,
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