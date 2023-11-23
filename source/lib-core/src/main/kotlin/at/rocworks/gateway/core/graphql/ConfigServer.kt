package at.rocworks.gateway.core.graphql

import at.rocworks.gateway.core.service.Common
import at.rocworks.gateway.core.service.Component
import at.rocworks.gateway.core.service.ComponentHandler
import at.rocworks.gateway.core.service.ComponentLogger
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.TypeResolver
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeRuntimeWiring
import io.vertx.core.AbstractVerticle
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.graphql.GraphQLHandler
import io.vertx.ext.web.handler.graphql.ws.GraphQLWSHandler
import java.time.Instant
import java.util.*


class ConfigServer(private val componentHandler: ComponentHandler, private val port: Int=9999): AbstractVerticle() {
    private val logger = ComponentLogger.getLogger(this::class.java.simpleName)
    private val tokens = hashMapOf<String, Instant>()

    companion object {
        const val GQL_DESCRIPTION = "\"\"\""
        const val GQL_LINEBREAK = "  " // Markdown two spaces for a new line
    }

    override fun start() {
        try {
            val (schema, wiring) = getSchema()
            val typeRegistry = SchemaParser().parse(schema)
            val graphQLSchema = SchemaGenerator().makeExecutableSchema(typeRegistry, wiring.build())
            val graphQLBuilder = GraphQL.newGraphQL(graphQLSchema)
            startGraphQLServer(graphQLBuilder.build())
            vertx.setPeriodic(60000) {// every minute
                // remove token after 30min of inactivity
                val removeTokens = tokens.filter {
                    it.value.plusSeconds(60*30) < Instant.now()
                }
                removeTokens.forEach {
                    logger.info("Remove access key ${it.key} [${it.value}].")
                    tokens.remove(it.key)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun stop() {
        super.stop()
    }

    private fun startGraphQLServer(graphql: GraphQL) {
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())

        val pathWithCors = "/admin/api"
        val pathWithoutCors = "/graphql" // needed for  access with tools like Altair-GraphQL-Client origin [electron://altair]

        // CORS Handler
        router.route(pathWithCors).handler(CorsHandler.create().addOrigins(listOf("*")))
        router.errorHandler(403) { routingContext: RoutingContext ->
            val failure = routingContext.failure()
            val origin = routingContext.request().getHeader("Origin")
            logger.severe("${failure.message} [$origin]")
            val response = routingContext.response()
            response.setStatusCode(403).end(failure.message)
        }

        // GraphQL Handler with CORS
        router.route(pathWithCors).handler(this::setAuthorizationHandler)
        router.route(pathWithCors).handler(GraphQLWSHandler.create(graphql))
        router.route(pathWithCors).handler(GraphQLHandler.create(graphql))

        // GraphQL Handler without CORS
        router.route(pathWithoutCors).handler(this::setAuthorizationHandler)
        router.route(pathWithoutCors).handler(GraphQLWSHandler.create(graphql))
        router.route(pathWithoutCors).handler(GraphQLHandler.create(graphql))

        // Websocket support for subscription
        val httpServerOptions = HttpServerOptions()
            .setWebSocketSubProtocols(listOf("graphql-transport-ws"))

        // Create HTTP server
        val httpServer = vertx.createHttpServer(httpServerOptions)
        httpServer.requestHandler(router).listen(port)
    }

    private fun setAuthorizationHandler(context: RoutingContext ) {
        val request = context.request();
        val token = request.getHeader ("Authorization");
        if (token != null) {
            context.put("Authorization", token);
        }
        context.next()
    }

    private fun createAccessToken(env: DataFetchingEnvironment): GqlResult {
        val username = env.getArgumentOrDefault("username", "")
        val password = env.getArgumentOrDefault("password", "")
        val token = UUID.randomUUID().toString()
        tokens[token] = Instant.now()
        // TODO: check username and password
        return GqlResult(true, 0, token)
    }

    private fun deleteAccessToken(env: DataFetchingEnvironment): GqlResult {
        val token = env.getArgumentOrDefault("token", "")
        if (tokens.containsKey(token)) {
            tokens.remove(token)
            return GqlResult(true, 0, "")
        } else {
            return GqlResult(false, -1, "")
        }
    }

    private fun checkAuthorization(env: DataFetchingEnvironment) {
        val routingContext: RoutingContext = env.graphQlContext.get(RoutingContext::class.java)
        val token = routingContext.get<String>("Authorization")
        if (!tokens.containsKey(token)) {
            throw Exception("Unauthorized!")
        } else {
            tokens[token] = Instant.now()
        }
    }

    private val commonSchema = """
    | enum ComponentStatus {
    |   None
    |   Enabled
    |   Disabled
    | }  
    | 
    | enum ComponentGroup {
    |   Server
    |   Driver
    |   Logger
    | }
    | 
    | enum ComponentType {
    |   GraphQLServer
    |   MqttServer
    |   OpcUaServer
    |   
    |   OpcUaDriver
    |   MqttDriver
    |   Plc4xDriver
    |   
    |   InfluxDBLogger
    |   IoTDBLogger
    |   JdbcLogger
    |   KafkaLogger
    |   MqttLogger
    | }
    | 
    | type Result {
    |   ok: Boolean
    |   code: Int
    |   message: String
    | }
    |  
    | $GQL_DESCRIPTION
    | A log message
    | $GQL_DESCRIPTION  
    | type Message {
    |   time: String
    |   level: String
    |   name: String
    |   message: String
    | } 
    """

    // Data classes
    data class GqlResult(val ok: Boolean, val code: Int, val message: String)
    data class GqlMessage(val time: String, val level: String, val name: String, val message: String)

    private val componentsSchema = """
    | interface Component {
    |   id: ID
    |   type: ComponentType
    |   "Current status of the component."
    |   status: ComponentStatus
    |   "Configuration as JSON string."
    |   config: String
    |   "Log messages of the component."
    |   messages(last: Int): [Message]
    | }
    |  
    | type ServerComponent implements Component {
    |   id: ID
    |   type: ComponentType
    |   status: ComponentStatus
    |   config: String
    |   messages(last: Int): [Message]
    | }
    | 
    | type DriverComponent implements Component {
    |   id: ID
    |   type: ComponentType
    |   status: ComponentStatus
    |   config: String
    |   messages(last: Int): [Message]
    | }
    |           
    | type LoggerComponent implements Component {
    |   id: ID
    |   type: ComponentType
    |   status: ComponentStatus
    |   config: String
    |   messages(last: Int): [Message]
    | }
    | 
    """

    data class GqlComponent(
        val id: String,
        val group: Component.ComponentGroup,
        val type: Component.ComponentType,
        val status: Component.ComponentStatus,
        val config: String
    )

    // -----------------------------------------------------------------------------------------------------------------
    // Query Fetchers
    // -----------------------------------------------------------------------------------------------------------------
    private val querySchema = """
    | $GQL_DESCRIPTION
    | Access token must be set as value in the header key 'Authorization'.  
    | Access token can be created with the mutation "createAccessToken" function.  
    | $GQL_DESCRIPTION
    | type Query {
    |   getComponents(group: ComponentGroup type: ComponentType id: ID): [Component]
    |   getMessages(type: ComponentType! id: ID!, last: Int): [Message]
    | }        
    """
    private val getComponentsFetcher: DataFetcher<List<GqlComponent>> = DataFetcher { env ->
        checkAuthorization(env)
        getComponents(env)
    }

    private val getMessagesFetcher: DataFetcher<List<GqlMessage>> = DataFetcher { env ->
        checkAuthorization(env)
        getMessages(env)
    }

    private fun getComponents(env: DataFetchingEnvironment): List<GqlComponent> {
        val group = env.getArgumentOrDefault("group", "")
        val type = env.getArgumentOrDefault("type", "")
        val id = env.getArgumentOrDefault("id", "")
        return componentHandler.getComponents().filter {
            (group.isEmpty() || it.group.toString() == group) &&
            (type.isEmpty() || it.type.toString() == type) &&
            (id.isEmpty() || it.id == id)
        }.map {
            GqlComponent(
                it.id,
                it.group,
                it.type,
                if (it.isEnabled()) Component.ComponentStatus.Enabled else Component.ComponentStatus.Disabled,
                it.config.toString()
            )
        }
    }

    private fun getMessages(env: DataFetchingEnvironment) : List<GqlMessage> {
        val last=env.getArgumentOrDefault("last", 100)
        val type=env.getArgumentOrDefault("type", "")
        val id=env.getArgumentOrDefault("id", "")
        val log = if (type.isNotEmpty() && id.isNotEmpty()) {
            ComponentLogger.getMessages(type, id, last)
        } else {
            val component = env.getSource<GqlComponent>()
            ComponentLogger.getMessages(component.type.toString(), component.id, last)
        }
        return log.map {
            GqlMessage(it.instant.toString(), it.level.toString(), it.loggerName, it.message)
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Mutation Fetchers
    // -----------------------------------------------------------------------------------------------------------------
    private val mutationSchema = """
    | "Access token must be set as value in the header key 'Authorization'."
    | type Mutation {
    |   "Create a access token (login)."
    |   createAccessToken(username: String! password: String!): Result
    |
    |   "Delete an access token (logout)."   
    |   deleteAccessToken(token: String!): Result
    |
    |   "Create a new component with given type and JSON config string."
    |   createComponent(type: ComponentType! config: String!): Result
    |
    |   "Delete an existing component."
    |   deleteComponent(type: ComponentType! id: ID!): Result
    |
    |   "Enable (start) an existing component."
    |   enableComponent(type: ComponentType! id: ID!): Result
    |
    |   "Disable (stop) an existing component."
    |   disableComponent(type: ComponentType! id: ID!): Result
    |
    |   "Write current configuration to configuration file."
    |   writeConfigurationToFile: Result
    | }        
    """.trimIndent()
    private val createAccessTokenFetcher: DataFetcher<GqlResult> = DataFetcher { env ->
        createAccessToken(env)
    }
    private val deleteAccessTokenFetcher: DataFetcher<GqlResult> = DataFetcher { env ->
        deleteAccessToken(env)
    }
    private val createComponentFetcher: DataFetcher<GqlResult> = DataFetcher { env ->
        checkAuthorization(env)
        createComponent(env)
    }
    private val deleteComponentFetcher: DataFetcher<GqlResult> = DataFetcher { env ->
        checkAuthorization(env)
        deleteComponent(env)
    }
    private val enableComponentFetcher: DataFetcher<GqlResult> = DataFetcher { env ->
        checkAuthorization(env)
        componentCommand("enable", env)
    }
    private val disableComponentFetcher: DataFetcher<GqlResult> = DataFetcher { env ->
        checkAuthorization(env)
        componentCommand("disable", env)
    }

    private val componentTypeResolver = TypeResolver { env ->
        val component = env.getObject<Any>() as GqlComponent
        when (component.group) {
            Component.ComponentGroup.Server-> env.schema.getType("ServerComponent") as GraphQLObjectType
            Component.ComponentGroup.Driver-> env.schema.getType("DriverComponent") as GraphQLObjectType
            Component.ComponentGroup.Logger-> env.schema.getType("LoggerComponent") as GraphQLObjectType
            Component.ComponentGroup.None -> null
        }
    }

    private fun createComponent(env: DataFetchingEnvironment): GqlResult {
        val type = Component.ComponentType.valueOf(env.getArgumentOrDefault("type", "None"))
        val config = JsonObject(env.getArgumentOrDefault("config", ""))
        componentHandler.createComponent(type, config)
        return GqlResult(true, 0, "ok")
    }

    private fun deleteComponent(env: DataFetchingEnvironment): GqlResult {
        val type = Component.ComponentType.valueOf(env.getArgumentOrDefault("type", "None"))
        val id = env.getArgumentOrDefault("id", "")
        componentHandler.deleteComponent(type, id)
        return GqlResult(true, 0, "ok")
    }

    private fun componentCommand(command: String, env: DataFetchingEnvironment): GqlResult {
        val type = Component.ComponentType.valueOf(env.getArgumentOrDefault("type", "None"))
        val id = env.getArgumentOrDefault("id", "")
        when (command) {
            "enable" -> componentHandler.deployComponent(type, id)
            "disable" -> componentHandler.undeployComponent(type, id)
        }
        return GqlResult(true, 0, "ok")
    }

    private val writeConfigurationToFile: DataFetcher<GqlResult> = DataFetcher { env ->
        checkAuthorization(env)
        Common.saveConfigToFile(componentHandler.getConfig())
        GqlResult(true, 0, "ok")
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Schema
    // -----------------------------------------------------------------------------------------------------------------
    private fun getSchema(): Pair<String, RuntimeWiring.Builder> {
        val schema = commonSchema
            .plus(componentsSchema)
            .plus(mutationSchema)
            .plus(querySchema)
            .trimMargin()
        //println(schema)

        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
            .type(
                TypeRuntimeWiring.newTypeWiring("Query")
                    .dataFetcher("getComponents", getComponentsFetcher)
                    .dataFetcher("getMessages", getMessagesFetcher)
            )
            .type(
                TypeRuntimeWiring.newTypeWiring("Mutation")
                    .dataFetcher("createAccessToken", createAccessTokenFetcher)
                    .dataFetcher("deleteAccessToken", deleteAccessTokenFetcher)
                    .dataFetcher("createComponent", createComponentFetcher)
                    .dataFetcher("deleteComponent", deleteComponentFetcher)
                    .dataFetcher("enableComponent", enableComponentFetcher)
                    .dataFetcher("disableComponent", disableComponentFetcher)
                    .dataFetcher("writeConfigurationToFile", writeConfigurationToFile)
            )
            .type(TypeRuntimeWiring.newTypeWiring("Component").typeResolver(componentTypeResolver))
            .type(TypeRuntimeWiring.newTypeWiring("ServerComponent").dataFetcher("messages", getMessagesFetcher))
            .type(TypeRuntimeWiring.newTypeWiring("DriverComponent").dataFetcher("messages", getMessagesFetcher))
            .type(TypeRuntimeWiring.newTypeWiring("LoggerComponent").dataFetcher("messages", getMessagesFetcher))
        return Pair(schema, runtimeWiring)
    }
}