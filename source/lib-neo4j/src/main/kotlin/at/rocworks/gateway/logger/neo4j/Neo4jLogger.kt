package at.rocworks.gateway.logger.neo4j

import at.rocworks.gateway.core.data.DataPoint
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.logger.LoggerBase
import at.rocworks.gateway.core.service.ServiceHandler
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.impl.BufferImpl
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.impl.ConcurrentHashSet
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.servicediscovery.Status

import java.util.concurrent.TimeUnit

import kotlin.Any
import kotlin.Boolean
import kotlin.Exception
import kotlin.String
import kotlin.Unit

import org.neo4j.driver.*
import org.neo4j.driver.Values.parameters

import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

import kotlin.concurrent.thread

class Neo4jLogger(config: JsonObject) : LoggerBase(config) {
    private val url = config.getString("Url", "bolt://localhost:7687")
    private val username = config.getString("Username", "neo4j")
    private val password = config.getString("Password", "password")

    private val driver : Driver = GraphDatabase.driver( url, AuthTokens.basic( username, password ) )

    private var session : Session? = null

    private val writePathStop = AtomicBoolean(false)
    private val writePathIds = ConcurrentHashSet<Value>()
    private val writePathQueue = LinkedBlockingQueue<Pair<Value, DataPoint>>()

    private val opcWriteNodesQuery = """
            UNWIND ${"$"}records AS record
            MERGE (n:OpcUaNode {
              Name: record.Name,
              System : record.System,
              NodeId : record.NodeId
            }) 
            SET n += {
              Status : record.Status,
              Value : record.Value,
              DataType: record.DataType,
              ServerTime : record.ServerTime,
              SourceTime : record.SourceTime
            }  
            RETURN ID(n)
            """.trimIndent()

    private val mqttWriteValuesQuery = """
            UNWIND ${"$"}records AS record
            MERGE (n:MqttValue {
              Name: record.Name,
              System : record.System,
              NodeId : record.NodeId
            }) 
            SET n += {
              Value : record.Value,
              Status : record.Status,
              DataType: record.DataType,
              ServerTime :record.ServerTime,
              SourceTime : record.SourceTime              
            } 
            RETURN ID(n)
            """.trimIndent()

    init {
    }

    override fun getAdditionalMetrics(): JsonObject {
        return JsonObject().put("Path Queue Size", writePathQueue.size)
    }

    override fun start(startPromise: Promise<Void>) {
        super.start(startPromise)
        val session = driver.session()
        createIndexes(session)

        val schemas = config.getJsonArray("Schemas", JsonArray()) ?: JsonArray()
        schemas.filterIsInstance<JsonObject>().map { systemConfig ->
            val system = systemConfig.getString("System")
            val nodeIds = systemConfig.getJsonArray("RootNodes", JsonArray(listOf("i=85"))).filterIsInstance<String>()
            fetchSchema(system, nodeIds).onComplete {
                logger.info("Write Graph [${system}] [${it.result().first}]")
                //File("${system}.json").writeText(it.result().second.encodePrettily())
                if (it.result().first) {
                    thread { writeSchemaToDb(session, system, it.result().second) }
                }
            }
        }
    }

    private fun fetchSchema(system: String, nodeIds: List<String>): Future<Pair<Boolean, JsonArray>> { // TODO: copied from GraphQLServer
        val promise = Promise.promise<Pair<Boolean, JsonArray>>()
        val serviceHandler = ServiceHandler(vertx, logger)
        val type = Topic.SystemType.Opc.name
        logger.info("Wait for service [${system}]...")
        var done = false
        serviceHandler.observeService(type, system) { record ->
            if (record.status == Status.UP && !done) {
                logger.info("Request schema [${system}] [${nodeIds.joinToString(",")}] ...")
                vertx.eventBus().request<JsonArray>(
                    "${type}/${system}/Schema",
                    JsonObject().put("NodeIds", nodeIds),
                    DeliveryOptions().setSendTimeout(60000L*10)) // TODO: configurable?
                {
                    done = true
                    logger.info("Schema response [${system}] [${it.succeeded()}] [${it.cause()?.message ?: ""}]")
                    val result = (it.result().body()?: JsonArray())
                    promise.complete(Pair(it.succeeded(), result))
                }
            }
        }
        return promise.future()
    }

    private fun createIndexes(session: Session) {
        session.run("""
            CREATE INDEX IF NOT EXISTS
            FOR (n:MqttNode)
            ON (n.System, n.NodeId)
            """.trimIndent())
        session.run("""
            CREATE INDEX IF NOT EXISTS
            FOR (n:MqttValue)
            ON (n.System, n.NodeId)
            """.trimIndent())
        session.run("""
            CREATE INDEX IF NOT EXISTS
            FOR (n:OpcUaNode)
            ON (n.System, n.NodeId)
            """.trimIndent())
    }

    private fun writeSchemaToDb(session: Session, system: String, schema: JsonArray) {
        try {
            logger.info("Write schema to graph database...")
            val tStart = Instant.now()

            // Create and get system
            val systemId = session.run("MERGE (s:OpcUaSystem { DisplayName: \$DisplayName }) RETURN ID(s)",
                parameters("DisplayName", system)).single()[0]

            schema.filterIsInstance<JsonObject>().forEach { rootNode ->
                session.executeWrite { tx ->
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
                        MATCH (n1:OpcUaSystem) WHERE ID(n1) = ${"$"}SystemId
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
            thread(start = true, block = ::writeMqttNodesThread)
            promise.complete()
        } catch (e: Exception) {
            promise.fail(e.message)
        }
        return promise.future()
    }

    override fun close(): Future<Unit> {
        val promise = Promise.promise<Unit>()
        writePathStop.set(true)
        driver.closeAsync().whenComplete { _, _ ->
            promise.complete()
        }
        return promise.future()
    }

    override fun writeExecutor() {
        val opcNodes = mutableListOf<DataPoint>()
        val mqttNodes = mutableListOf<DataPoint>()
        var point: DataPoint? = pollDatapointWait()
        while (point != null && opcNodes.size < writeParameterBlockSize && mqttNodes.size < writeParameterBlockSize) {
            logger.finest { "Write topic ${point?.topic}" }
            when (val systemType = point.topic.systemType) {
                Topic.SystemType.Opc -> {
                    opcNodes.add(point)
                    valueCounterOutput ++
                }
                Topic.SystemType.Mqtt -> {
                    mqttNodes.add(point)
                    valueCounterOutput ++
                }
                else -> {
                    logger.warning("$systemType not supported!")
                }
            }
            point = pollDatapointNoWait()
        }
        try {
            if (opcNodes.isNotEmpty()) writeOpcNodes(opcNodes)
            if (mqttNodes.isNotEmpty()) writeMqttNodes(mqttNodes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeOpcNodes(dataPoints: List<DataPoint>) {
        val records = dataPoints.map { point ->
            mapOf("Name" to point.topic.browsePath.getMetric(),
                "System" to point.topic.systemName,
                "NodeId" to point.topic.topicNode,
                "Status" to point.value.statusAsString(),
                "Value" to point.value.valueAsObject(),
                "DataType" to point.value.dataTypeName(),
                "ServerTime" to point.value.serverTimeAsISO(),
                "SourceTime" to point.value.sourceTimeAsISO()
            )
        }
        session?.executeWrite { tx ->
            val result = tx.run(opcWriteNodesQuery, parameters("records", records))
            val results = result.list()
        }
    }


    private fun writeMqttNodesThread() {
        logger.info("Path writer thread started with queue size [${writePathQueue.remainingCapacity()}]")
        val session = driver.session()
        writePathStop.set(false)
        while (!writePathStop.get()) {
            var data = writePathQueue.poll(10, TimeUnit.MILLISECONDS)
            data?.let { (value, point) ->
                session.executeWrite { tx ->
                    writePathIds.remove(value)
                    writeMqttValuePath(tx, value, point)
                }
            }
        }
    }

    private fun writeMqttNodes(dataPoints: List<DataPoint>) {
        val records = dataPoints.map { point ->
            val name = point.topic.browsePath.getMetric()
            mapOf("Name" to name,
                "System" to point.topic.systemName,
                "NodeId" to point.topic.topicNode,
                "Status" to point.value.statusAsString(),
                "Value" to if (point.value.value is BufferImpl) point.value.valueAsString() else point.value.valueAsObject(),
                "DataType" to point.value.dataTypeName(),
                "ServerTime" to point.value.serverTimeAsISO(),
                "SourceTime" to point.value.sourceTimeAsISO())
        }
        session?.executeWrite { tx ->
            val result = tx.run(mqttWriteValuesQuery, parameters("records", records))
            val results = result.list()
            val isNewValue = result.consume().counters().nodesCreated() > 0
            if (isNewValue) {
                results.zip(dataPoints).forEach {
                    val id = it.first[0]
                    if (!writePathIds.contains(id)) {
                        writePathIds.add(id)
                        writePathQueue.add(Pair(it.first[0], it.second))
                    }
                }
            }
        }
    }

    private fun writeMqttValuePath(
        tx: TransactionContext,
        mqttValueId: Value,
        point: DataPoint
    ) {
        val browsePath = point.topic.browsePath.toList()
        val connectQuery =
            "MATCH (n1) WHERE ID(n1) = \$parentId \n" +
            "MATCH (n2) WHERE ID(n2) = \$folderId \n" +
            "MERGE (n1)-[:HAS]->(n2)"

        val (parentId, _) = (listOf(point.topic.systemType.toString(), point.topic.systemName) + browsePath)
            .dropLast(1) // remove the value node
            .fold(Pair(Values.NULL, "")) { pair, name ->
                val path = if (pair.second.isNotEmpty()) pair.second + "/" + name else name
                val folderId = tx.run(
                    "MERGE (n:MqttNode { System: \$System, Path: \$Path, Name: \$Name }) RETURN ID(n)",
                    parameters("System", point.topic.systemName, "Path", path, "Name", name)
                ).single()[0]

                if (!pair.first.isNull) {
                    val parentId = pair.first
                    tx.run(
                        connectQuery,
                        parameters("parentId", parentId, "folderId", folderId)
                    )
                }
                Pair(folderId, path)
            }
        tx.run(
            connectQuery,
            parameters("parentId", parentId, "folderId", mqttValueId)
        )
    }
}