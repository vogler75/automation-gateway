package at.rocworks.gateway.core.graphql

import at.rocworks.gateway.core.data.*
import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.ServiceHandler

import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.*

import io.reactivex.*
import io.vertx.core.*

import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.graphql.ApolloWSHandler
import io.vertx.ext.web.handler.graphql.GraphQLHandler

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap

import java.time.format.DateTimeFormatter
import java.util.logging.Level
import java.util.logging.Logger
import io.vertx.ext.web.handler.graphql.GraphiQLHandler
import io.vertx.ext.web.handler.graphql.GraphiQLHandlerOptions
import io.vertx.servicediscovery.Status
import kotlin.concurrent.thread

class GraphQLServer(private val config: JsonObject, private val defaultSystem: String) : AbstractVerticle() {
    private val defaultType = Topic.SystemType.Opc.name

    companion object {
        fun create(vertx: Vertx, config: JsonObject, defaultSystem: String) {
            vertx.deployVerticle(GraphQLServer(config, defaultSystem))
        }
    }

    private val id = this.javaClass.simpleName
    private val logger = LoggerFactory.getLogger(id)

    private val schemas: JsonObject = JsonObject()
    private val defaultFieldName: String = "DisplayName"
    private val enableGraphiQL: Boolean = config.getBoolean("GraphiQL", false)
    private val writeSchemaFiles: Boolean = config.getBoolean("WriteSchemaToFile", false)
    private val timeFormatterISO = DateTimeFormatter.ISO_DATE_TIME

    init {
        Logger.getLogger(id).level = Level.parse(config.getString("LogLevel", "INFO"))
    }

    override fun start(startPromise: Promise<Void>) {
        fun build(schema: String, wiring: RuntimeWiring.Builder): GraphQL{
            try {
                val typeDefinitionRegistry = SchemaParser().parse(schema)
                val graphQLSchema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, wiring.build())
                return GraphQL.newGraphQL(graphQLSchema).build()
            } catch (e: Exception) {
                e.printStackTrace()
                throw java.lang.Exception(e.message)
            }
        }
        thread {
            val schemas = config.getJsonArray("Schemas", JsonArray()) ?: JsonArray()
            if (schemas.isEmpty) {
                logger.info("Default schema...")
                getGenericSchema().let { (schema, wiring) ->
                    startGraphQLServer(build(schema, wiring))
                    logger.info("GraphQL ready")
                    startPromise.complete()
                }
            } else {
                logger.info("Build schema...")
                val (generic, wiring) = getGenericSchema(withSystems = true)

                val results = schemas.filterIsInstance<JsonObject>().map { systemConfig ->
                    val promise = Promise.promise<String>()
                    val system = systemConfig.getString("System")
                    val fieldName = systemConfig.getString("FieldName", defaultFieldName) // DisplayName or BrowseName
                    val nodeIds =
                        systemConfig.getJsonArray("RootNodes", JsonArray(listOf("i=85"))).filterIsInstance<String>()
                    fetchSchema(system, nodeIds).onComplete {
                        logger.info("Build GraphQL [{}] ...", system)
                        val result = getSystemSchema(system, fieldName, wiring)
                        logger.info("Build GraphQL [{}] [{}]...complete", system, result.length)
                        promise.complete(result)
                    }
                    promise.future()
                }

                val systems = schemas.filterIsInstance<JsonObject>().map { it.getString("System") }
                val systemTypes = "type Systems {\n" + systems.joinToString(separator = "\n") { "  $it: $it" } + "\n}\n"

                val dataFetcher = getSchemaNode("", "", "", "", "", "")
                wiring.type(
                    TypeRuntimeWiring.newTypeWiring("Query")
                        .dataFetcher("Systems", dataFetcher)
                )

                CompositeFuture.all(results).onComplete {
                    val schema = generic + systemTypes + (results.joinToString(separator = "\n") { it.result() })

                    if (writeSchemaFiles)
                        File("graphql.gql").writeText(schema)

                    try {
                        logger.info("Generate GraphQL schema...")
                        val graphql = build(schema, wiring)
                        logger.info("Startup GraphQL server...")
                        startGraphQLServer(graphql)
                        logger.info("GraphQL ready")
                        startPromise.complete()
                    } catch (e: Exception) {
                        logger.error(e.message)
                        startPromise.fail(e)
                    }
                }
            }
        }
    }

    private fun fetchSchema(system: String, nodeIds: List<String>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        val serviceHandler = ServiceHandler(vertx, logger)
        val type = Topic.SystemType.Opc.name
        logger.info("Wait for service [{}]...", system)
        var done = false
        serviceHandler.observeService(type, system) { record ->
            if (record.status == Status.UP && !done) {
                logger.info("Request schema [{}] [{}] ...", system, nodeIds.joinToString(","))
                vertx.eventBus().request<JsonObject>(
                    "${type}/${system}/Schema",
                    JsonObject().put("NodeIds", nodeIds),
                    DeliveryOptions().setSendTimeout(60000L*10)) // TODO: configurable?
                {
                    done = true
                    logger.info("Schema response [{}] [{}] [{}]", system, it.succeeded(), it.cause()?.message ?: "")
                    val result = it.result().body() ?: JsonObject()
                    schemas.put(system, result)
                    promise.complete(it.succeeded())
                }
            }
        }
        return promise.future()
    }

    private fun getSystemSchema(system: String, fieldName: String, wiring: RuntimeWiring.Builder): String {
        val typePaths = mutableSetOf<String>()
        val typeDefinitions = mutableListOf<String>()

        class Recursion { // needed, otherwise inline functions cannot be called from each other
            fun addNode(node: JsonObject, path: String, wiring: TypeRuntimeWiring.Builder): String? {
                val browseName = node.getString("BrowseName")
                val browsePath = node.getString("BrowsePath")
                val displayName = node.getString("DisplayName")
                val nodeId = node.getString("NodeId")
                val nodeClass = node.getString("NodeClass")
                val nodes = node.getJsonArray("Nodes", JsonArray())

                val graphqlName0 = node.getString(fieldName, browseName)
                val graphqlName1 = "[^A-Za-z0-9]".toRegex().replace(graphqlName0, "_")
                val graphqlName2 = if (Character.isDigit(graphqlName1[0])) "_$graphqlName1" else graphqlName1
                val graphqlName = "^__".toRegex().replace(graphqlName2, "_") // __ is reserved GraphQL internal

                // TODO: what happens when the names are not unique anymore (because of substitutions...)
                val dataFetcher = getSchemaNode(system, nodeId, nodeClass, browseName, browsePath, displayName)
                wiring.dataFetcher(graphqlName, dataFetcher)

                return when (nodeClass) {
                    "Variable" -> {
                        "$graphqlName : Node"
                    }
                    "Object" -> {
                        val newTypeName = "${path}_${graphqlName}"
                        if (addNodes(nodes, newTypeName))
                           "$graphqlName : $newTypeName"
                        else {
                            logger.debug("Cannot add nodes of $newTypeName")
                            null
                        }
                    }
                    else -> {
                        logger.error("Unhandled node class $nodeClass")
                        null
                    }
                }
            }

            fun addNodes(nodes: JsonArray, path: String): Boolean {
                if (typePaths.contains(path)) {
                    logger.warn("Type path [{}] already used, dismiss the second node.", path)
                    return false
                } else {
                    typePaths.add(path)
                    val items = mutableListOf<String>()

                    val validNodes = nodes.filterIsInstance<JsonObject>()
                    if (validNodes.isEmpty()) return false

                    val newTypeWiring = TypeRuntimeWiring.newTypeWiring(path)
                    val addedNodes = validNodes.mapNotNull { node -> addNode(node, path, newTypeWiring) }
                    if (addedNodes.isEmpty()) return false

                    addedNodes.forEach { items.add(it) }
                    wiring.type(newTypeWiring)
                    typeDefinitions.add("type $path { \n ${items.joinToString(separator = "\n ")} \n}")
                    return true
                }
            }
        }

        try {
            val dataFetcher = getSchemaNode(system, "", "", "", "", "")
            wiring.type(
                TypeRuntimeWiring.newTypeWiring("Systems")
                    .dataFetcher(system, dataFetcher))

            schemas
                .getJsonObject(system, JsonObject())
                .mapNotNull { (it.value as? JsonArray)?.toList() }
                .flatten()
                .let {
                    Recursion().addNodes(JsonArray(it), system)
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val schema = typeDefinitions.joinToString(separator = "\n")
        if (writeSchemaFiles)
            File("graphql-${system}.gql".toLowerCase()).writeText(schema)

        return schema
    }

    private fun getGenericSchema(withSystems: Boolean = false): Pair<String, RuntimeWiring.Builder> {
        // enum Type must match the Globals.BUS_ROOT_URI_*
        val schema = """
            | enum Type { 
            |   Opc
            |   Plc
            |   Mqtt
            | }  
            | 
            | type Query {
            |   ServerInfo(System: String): ServerInfo
            |   
            |   NodeValue(Type: Type, System: String, NodeId: ID!): Value 
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]): [Value]
            |   BrowseNode(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
            |   FindNodes(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
            |   
            |   ${if (withSystems) "Systems: Systems" else ""}
            | }
            | 
            | type Mutation {
            |   NodeValue(Type: Type, System: String, NodeId: ID!, Value: String!): Boolean
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]!, Values: [String!]!): [Boolean]
            | }
            | 
            | type Subscription {
            |   NodeValue(Type: Type, System: String, NodeId: ID!): Value
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]!): Value
            | }
            | 
            | type Value {
            |   System: String
            |   NodeId: ID
            |   Value: String
            |   DataType: String
            |   StatusCode: String
            |   SourceTime: String
            |   ServerTime: String
            |   History(Log: ID, From: String, To: String, LastSeconds: Int): [Value]   
            | }
            | 
            | type Node {
            |   System: String
            |   NodeId: ID
            |   BrowseName: String
            |   BrowsePath(Full: Boolean): String
            |   DisplayName: String
            |   NodeClass: String
            |   Value: Value
            |   Nodes(Filter: String): [Node]
            |   History(Log: ID, From: String, To: String, LastSeconds: Int): [Value]
            |   SetValue(Value: String): Boolean
            | }
            | 
            | type ServerInfo {
            |   Server: [String]
            |   Namespace: [String]
            |   BuildInfo: String
            |   StartTime: String
            |   CurrentTime: String
            |   ServerStatus: String
            | }
            |
            |
            """.trimMargin()

        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
            .type(
                TypeRuntimeWiring.newTypeWiring("Query")
                    .dataFetcher("ServerInfo", getServerInfo())
                    .dataFetcher("NodeValue", getNodeValue())
                    .dataFetcher("NodeValues", getNodeValues())
                    .dataFetcher("BrowseNode", getBrowseNode())
                    .dataFetcher("FindNodes", getFindNodes())
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Mutation")
                    .dataFetcher("NodeValue", setNodeValue())
                    .dataFetcher("NodeValues", setNodeValues())
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Subscription")
                    .dataFetcher("NodeValue", this::subNodeValue)
                    .dataFetcher("NodeValues", this::subNodeValues)
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Node")
                    .dataFetcher("Value", getNodeValue())
                    .dataFetcher("Nodes", getBrowseNode())
                    .dataFetcher("History", getValueHistory())
                    .dataFetcher("SetValue", setNodeValue())
                    .dataFetcher("BrowsePath", getBrowsePath())
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Value")
                    .dataFetcher("History", getValueHistory())
            )
        return Pair(schema, runtimeWiring)
    }

    private fun startGraphQLServer(graphql: GraphQL) {
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())
        router.route("/graphql").handler(ApolloWSHandler.create(graphql))
        router.route("/graphql").handler(GraphQLHandler.create(graphql))

        if (enableGraphiQL) {
            logger.info("Enable GraphiQL")
            val options = GraphiQLHandlerOptions().setEnabled(true)
            router.route("/graphiql/*").handler(GraphiQLHandler.create(options))
        }

        val httpServerOptions = HttpServerOptions()
            .setWebSocketSubProtocols(listOf("graphql-ws"))
        val httpServer = vertx.createHttpServer(httpServerOptions)
        val httpPort = config.getInteger("Port", 4000)
        httpServer.requestHandler(router).listen(httpPort)
    }

    private fun getServerInfo(): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> { env ->
            val promise = CompletableFuture<Map<String, Any?>>()
            val (type, system) = getEnvTypeAndSystem(env)
            try {
                vertx.eventBus().request<JsonObject>("$type/$system/ServerInfo", JsonObject()) {
                    logger.debug("getServerInfo read response [{}] [{}]", it.succeeded(), it.result()?.body())
                    if (it.succeeded()) {
                        val result = it.result().body().getJsonObject("Result")
                        val map = HashMap<String, Any>()
                        map["Server"] = result.getJsonArray("Server").toList()
                        map["Namespace"] = result.getJsonArray("Namespace").toList()
                        map["BuildInfo"] = result.getString("BuildInfo")
                        map["StartTime"] = result.getString("StartTime")
                        map["CurrentTime"] = result.getString("CurrentTime")
                        map["ServerStatus"] = result.getString("ServerStatus")
                        promise.complete(map)
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }
            promise
        }
    }

    private fun getEnvArgument(env: DataFetchingEnvironment, name: String): String? {
        val ctx: Map<String, Any>? = env.getSource()
        return env.getArgumentOrDefault(name, ctx?.get(name) as String?)
    }

    private fun getEnvTypeAndSystem(env: DataFetchingEnvironment): Pair<String, String> {
        val ctx: Map<String, Any>? = env.getSource()
        val type: String = env.getArgument("Type")
            ?: ctx?.get("Type") as String?
            ?: defaultType

        val system: String = env.getArgument("System")
            ?: ctx?.get("System") as String?
            ?: defaultSystem

        return Pair(type, system)
    }

    private fun getNodeValue(): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> { env ->
            val promise = CompletableFuture<Map<String, Any?>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env,"NodeId") ?: ""

            val request = JsonObject()
            request.put("NodeId", nodeId)

            try {
                logger.debug("getNodeValue read request...")
                val address = "$type/$system/Read"
                vertx.eventBus().request<JsonObject>(address, request) { response ->
                    logger.debug("getNodeValue read response [{}] [{}]", response.succeeded(), response.result()?.body())
                    if (response.succeeded()) {
                        try {
                            val data = response.result().body().getJsonObject("Result")
                            if (data!=null) {
                                val input = TopicValue.fromJsonObject(data)
                                val result = valueToGraphQL(type, system, nodeId, input)
                                promise.complete(result)
                            } else {
                                logger.warn("No result in read response!")
                                promise.complete(null)
                            }
                        } catch (e: Exception) {
                            logger.error(e.message)
                            promise.complete(null)
                        }
                    } else {
                        response.cause().printStackTrace()
                        promise.complete(null)
                    }
                }
            } catch (e: Exception) {
                logger.warn(e.message)
                //e.printStackTrace()
            }

            promise
        }
    }

    private fun getNodeValues(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeIds = env?.getArgument("NodeIds") ?: listOf<String>()

            val request = JsonObject()
            val nodes = JsonArray()
            nodeIds.forEach { nodes.add(it) }
            request.put("NodeId", nodes)

            try {
                logger.debug("getNodeValues read request...")
                val address = "$type/$system/Read"
                vertx.eventBus().request<JsonObject>(address, request) { response ->
                    logger.debug("getNodeValues read response [{}] [{}]", response.succeeded(), response.result()?.body())
                    if (response.succeeded()) {
                        val list = response.result().body().getJsonArray("Result")
                        val result = nodeIds.zip(list.filterIsInstance<JsonObject>()).map {
                            valueToGraphQL(type, system, it.first, TopicValue.fromJsonObject(it.second))
                        }
                        promise.complete(result)
                    } else {
                        response.cause().printStackTrace()
                        promise.complete(null)
                    }
                }
            } catch (e: Exception) {
                logger.warn(e.message)
                //e.printStackTrace()
            }

            promise
        }
    }

    private fun setNodeValue(): DataFetcher<CompletableFuture<Boolean>> {
        return DataFetcher<CompletableFuture<Boolean>> { env ->
            val promise = CompletableFuture<Boolean>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env, "NodeId") ?: ""
            val value: String = getEnvArgument(env, "Value") ?: ""

            val request = JsonObject()
            request.put("NodeId", nodeId)
            request.put("Value", value)

            logger.debug("setNodeValue $type $system $nodeId $value")
            try {
                val address = "$type/$system/Write"
                vertx.eventBus().request<JsonObject>(address, request) { response ->
                    logger.debug("setNodeValue write response [{}] [{}]", response.succeeded(), response.result()?.body())
                    promise.complete(
                        if (response.succeeded()) {
                            response.result().body().getBoolean("Ok")
                        } else {
                            response.cause().printStackTrace()
                            false
                        })
                }
            } catch (e: Exception) {
                logger.warn(e.message)
                //e.printStackTrace()
            }

            promise
        }
    }

    private fun setNodeValues(): DataFetcher<CompletableFuture<List<Boolean>>> {
        return DataFetcher<CompletableFuture<List<Boolean>>> { env ->
            val promise = CompletableFuture<List<Boolean>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeIds = env.getArgument("NodeIds") as List<String>
            val values = env.getArgument("Values") as List<String>

            val request = JsonObject()
            request.put("NodeId", nodeIds)
            request.put("Value", values)

            try {
                val address = "$type/$system/Write"
                vertx.eventBus().request<JsonObject>(address, request) { response ->
                    logger.debug("setNodeValues write response [{}] [{}]", response.succeeded(), response.result()?.body())
                    promise.complete(
                        if (response.succeeded()) {
                            response.result()
                                .body()
                                .getJsonArray("Ok")
                                .map { ok -> ok as? Boolean ?: false }
                        } else {
                            response.cause().printStackTrace()
                            nodeIds.map { false }
                        })
                }
            } catch (e: Exception) {
                logger.warn(e.message)
                //e.printStackTrace()
            }

            promise
        }
    }

    private fun getBrowseNode(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId = getEnvArgument(env, "NodeId") ?: "i=85"
            val filter: String? = getEnvArgument(env,"Filter")

            val request = JsonObject()
            request.put("NodeId", nodeId)

            try {
                val address = "$type/$system/Browse"
                vertx.eventBus().request<JsonObject>(address, request) { response ->
                    logger.debug("Browse response [{}] [{}]", response.succeeded(), response.result()?.body())
                    if (response.succeeded()) {
                        try {
                            val list = response.result().body().getJsonArray("Result")
                            val result = list
                                .filterIsInstance<JsonObject>()
                                .filter { filter == null || filter.toRegex().matches(it.getString("BrowseName")) }
                                .map { input ->
                                    val item = HashMap<String, Any>()
                                    item["System"] = system
                                    item["NodeId"] = input.getString("NodeId")
                                    item["BrowseName"] = input.getString("BrowseName")
                                    item["BrowsePath"] = input.getString("BrowsePath")
                                    item["DisplayName"] = input.getString("DisplayName")
                                    item["NodeClass"] = input.getString("NodeClass")
                                    item
                                }
                            promise.complete(result)
                        } catch (e: Exception) {
                            promise.completeExceptionally(e)
                        }
                    } else {
                        response.cause().printStackTrace()
                        promise.complete(null)
                    }
                }
            } catch (e: Exception) {
                logger.warn(e.message)
                //e.printStackTrace()
            }
            promise
        }
    }

    private fun getBrowsePath(): DataFetcher<CompletableFuture<String>> {
        return DataFetcher<CompletableFuture<String>> { env ->
            val promise = CompletableFuture<String>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId = getEnvArgument(env, "NodeId")
            val browseName = getEnvArgument(env, "BrowseName")
            val browsePath = getEnvArgument(env, "BrowsePath")
            val optionFull = env.getArgumentOrDefault("Full", false)

            if (nodeId==null) {
                promise.complete("")
            } else if (!optionFull) {
                promise.complete(browsePath)
            } else {
                val request = JsonObject()
                request.put("NodeId", nodeId)
                request.put("Reverse", true)
                try {
                    val address = "$type/$system/Browse"
                    vertx.eventBus().request<JsonObject>(address, request) { response ->
                        logger.debug("Browse response [{}] [{}]", response.succeeded(), response.result()?.body())
                        if (response.succeeded()) {
                            try {
                                val list = response.result().body().getJsonArray("Result")
                                fun flatten(list: JsonArray): String? {
                                    return when (val first = list.firstOrNull()) {
                                        is JsonObject -> {
                                            val left = first.getJsonArray("Nodes")?.let { flatten(it) }
                                            return (if (left != null) "$left/" else "") + first.getString("BrowseName")
                                        }
                                        else -> null
                                    }
                                }
                                val result = flatten(list) + "/" + browseName
                                promise.complete(result)
                            } catch (e: Exception) {
                                promise.completeExceptionally(e)
                            }
                        } else {
                            response.cause().printStackTrace()
                            promise.complete(null)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(e.message)
                    //e.printStackTrace()
                }
            }
            promise
        }
    }

    private fun getFindNodes(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env,"NodeId") ?: "i=85"
            val filter: String? = getEnvArgument(env,"Filter")

            val overallResult =  mutableListOf<HashMap<String, Any>>()

            fun find(nodeId: String): CompletableFuture<Boolean> {
                val promise = CompletableFuture<Boolean>()
                val request = JsonObject()
                request.put("NodeId", nodeId)
                val address = "$type/$system/Browse"
                vertx.eventBus()
                    .request<JsonObject>(address, request) { response ->
                        logger.debug("getNodes browse response [{}] [{}]", response.succeeded(), response.result()?.body())
                        if (response.succeeded()) {
                            val result = response.result().body().getJsonArray("Result")?.filterIsInstance<JsonObject>()
                            logger.debug("FindNodes result [{}]", result?.size)
                            if (result!=null) {
                                overallResult.addAll(result
                                    .filter { filter == null || filter.toRegex().matches(it.getString("BrowseName"))}
                                    .map { input ->
                                        val item = HashMap<String, Any>()
                                        item["System"] = system // TODO: don't repeat yourself, create function!
                                        item["NodeId"] = input.getString("NodeId")
                                        item["BrowseName"] = input.getString("BrowseName")
                                        item["BrowsePath"] = input.getString("BrowsePath")
                                        item["DisplayName"] = input.getString("DisplayName")
                                        item["NodeClass"] = input.getString("NodeClass")
                                        item
                                    }
                                )
                                val next = result
                                    .filter { it.getString("NodeClass") == "Object" }
                                    .map { find(it.getString("NodeId")) }

                                if (next.isNotEmpty()) {
                                    CompletableFuture.allOf(*next.toTypedArray()).thenAccept { promise.complete(true) }
                                } else {
                                    promise.complete(true)
                                }
                            } else promise.complete(false)
                        } else promise.complete(false)
                    }
                return promise
            }

            val promise = CompletableFuture<List<Map<String, Any?>>>()
            find(nodeId).thenAccept { promise.complete(overallResult) }
            promise
        }
    }

    private fun subNodeValue(env: DataFetchingEnvironment): Flowable<Map<String, Any?>> {
        val uuid = UUID.randomUUID().toString()

        val (type, system) = getEnvTypeAndSystem(env)
        val nodeId: String = env.getArgument("NodeId") ?: ""

        val topic = Topic.parseTopic("$type/$system/node:json/$nodeId")
        val flowable = Flowable.create(FlowableOnSubscribe<Map<String, Any?>> { emitter ->
            val consumer = vertx.eventBus().consumer<Buffer>(topic.topicName) { message ->
                try {
                    val data = message.body().toJsonObject()
                    val output = TopicValue.fromJsonObject(data.getJsonObject("Value"))
                    if (!emitter.isCancelled) emitter.onNext(valueToGraphQL(type, system, nodeId, output))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            emitter.setCancellable {
                logger.info("Unsubscribe [{}] [{}]", consumer.address(), uuid)
                consumer.unregister()
                val request = JsonObject().put("ClientId", uuid).put("Topics", listOf(topic.encodeToJson()))
                vertx.eventBus().request<JsonObject>("${topic.systemType}/${topic.systemName}/Unsubscribe", request) {
                    logger.info("Unsubscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
                }
            }
        }, BackpressureStrategy.BUFFER)

        val request = JsonObject().put("ClientId", uuid).put("Topic", topic.encodeToJson())
        val address = "${topic.systemType}/${topic.systemName}/Subscribe"
        logger.info("Subscribe [{}] [{}]...", address, uuid)
        vertx.eventBus().request<JsonObject>(address, request) {
            if (it.succeeded()) {
                logger.info("Subscribe response [{}] [{}] [{}]", topic.topicName, it.result().body().getBoolean("Ok"), uuid)
            } else {
                logger.info("Subscribe not succeeded!")
            }
        }

        return flowable
    }

    private fun subNodeValues(env: DataFetchingEnvironment): Flowable<Map<String, Any?>> {
        val uuid = UUID.randomUUID().toString()

        val (type, system) = getEnvTypeAndSystem(env)
        val nodeIds = env.getArgument("NodeIds") ?: listOf<String>()

        val flowable = Flowable.create(FlowableOnSubscribe<Map<String, Any?>> { emitter ->
            val consumers = nodeIds.map { nodeId ->
                val topic = "$type/$system/node:json/$nodeId"
                vertx.eventBus().consumer<Buffer>(topic) { message ->
                    try {
                        val data = message.body().toJsonObject()
                        val output = TopicValue.fromJsonObject(data.getJsonObject("Value"))
                        if (!emitter.isCancelled) emitter.onNext(valueToGraphQL(type, system, nodeId, output))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            emitter.setCancellable {
                consumers.forEach { consumer ->
                    logger.info("Unsubscribe [{}] [{}]", consumer.address(), uuid)
                    consumer.unregister()
                    val topic = Topic.parseTopic(consumer.address())
                    val request = JsonObject().put("ClientId", uuid).put("Topics", listOf(topic.encodeToJson()))
                    vertx.eventBus().request<JsonObject>("${topic.systemType}/${system}/Unsubscribe", request) {
                        logger.info("Unsubscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
                    }
                }
            }
        }, BackpressureStrategy.BUFFER)

        nodeIds.forEach { nodeId ->
            val topic = Topic.parseTopic("$type/$system/node:json/$nodeId")
            val request = JsonObject().put("ClientId", uuid).put("Topic", topic.encodeToJson())
            val address = "${topic.systemType}/${topic.systemName}/Subscribe"
            logger.info("Subscribe [{}] [{}]...", address, uuid)
            vertx.eventBus().request<JsonObject>(address, request) {
                if (it.succeeded()) {
                    logger.info("Subscribe response [{}] [{}] [{}]", topic.topicName, it.result().body().getBoolean("Ok"), uuid)
                } else {
                    logger.info("Subscribe not succeeded!")
                }
            }
        }
        return flowable
    }

    private fun getValueHistory(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()
            if (env==null) {
                promise.complete(listOf())
            } else {
                val log: String = getEnvArgument(env,"Log") ?: "default"

                val (_, system) = getEnvTypeAndSystem(env)
                val nodeId: String = getEnvArgument(env, "NodeId") ?: ""

                var t1 = Instant.now()
                var t2 = Instant.now()

                val t1arg = env.getArgument<String>("From")
                if (t1arg!=null)
                    t1 = Instant.from(timeFormatterISO.parse(t1arg))

                val t2arg = env.getArgument<String>("To")
                if (t2arg!=null)
                    t2 = Instant.from(timeFormatterISO.parse(t2arg))

                val lastSeconds: Int? = env.getArgument("LastSeconds")
                if (lastSeconds!=null) {
                    t1 = (Instant.now()).minusSeconds(lastSeconds.toLong())
                }

                val request = JsonObject()
                request.put("System", system)
                request.put("NodeId", nodeId)
                request.put("T1", t1.toEpochMilli())
                request.put("T2", t2.toEpochMilli())

                vertx.eventBus()
                    .request<JsonObject>("${Common.BUS_ROOT_URI_LOG}/$log/QueryHistory", request) { message ->
                        val list =  message.result()?.body()?.getJsonArray("Result") ?: JsonArray()
                        logger.info("Query response [{}] size [{}]", message.succeeded(), list.size())
                        val result = list.filterIsInstance<JsonArray>().map {
                            val item = HashMap<String, Any?>()
                            item["System"] = system
                            item["NodeId"] = nodeId
                            item["SourceTime"] = it.getValue(0)
                            item["ServerTime"] = it.getValue(1)
                            item["Value"] = it.getValue(2)
                            item["DataType"] = it.getValue(2)?.javaClass?.simpleName ?: ""
                            item["StatusCode"] = it.getValue(3)
                            item
                        }
                        promise.complete(result)
                    }
            }
            promise
        }
    }

    private fun getSchemaNode(system: String, nodeId: String, nodeClass: String, browseName: String, browsePath: String, displayName: String): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> {
            val promise = CompletableFuture<Map<String, Any?>>()
            val item = HashMap<String, Any>()
            if (nodeClass=="Variable") {
                item["System"] = system
                item["NodeId"] = nodeId
                item["BrowseName"] = browseName
                item["BrowsePath"] = browsePath
                item["DisplayName"] = displayName
                item["NodeClass"] = nodeClass
            }
            promise.complete(item)
            promise
        }
    }

    private fun valueToGraphQL(type: String, system: String, nodeId: String, input: TopicValue): HashMap<String, Any?> {
        val item = HashMap<String, Any?>()
        item["Type"] = type
        item["System"] = system
        item["NodeId"] = nodeId
        item["Value"] = input.valueAsString()
        item["DataType"] = input.dataTypeName()
        item["StatusCode"] = input.statusAsString()
        item["SourceTime"] = input.sourceTimeAsISO()
        item["ServerTime"] = input.serverTimeAsISO()
        return item
    }
}