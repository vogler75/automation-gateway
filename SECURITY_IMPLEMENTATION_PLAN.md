# Automation Gateway Security Analysis & Implementation Plan

**Date:** October 31, 2025
**Repository:** https://github.com/LopeWale/automation-gateway
**Branch:** master
**Commit:** 65c1726

---

## Codebase Analysis Summary

### Technology Stack
- **Language:** Kotlin + Java
- **Framework:** Vert.x (reactive framework)
- **GraphQL:** graphql-java with Vert.x GraphQL handlers
- **Build:** Gradle (multi-module project)
- **Java Version:** 17+

### Project Structure
```
automation-gateway/
├── source/
│   ├── app/                    # Main application
│   │   └── src/main/kotlin/App.kt
│   ├── lib-core/               # Core functionality (GraphQL server here!)
│   │   └── src/main/kotlin/at/rocworks/gateway/core/
│   │       ├── graphql/GraphQLServer.kt  ⚠️ NO SECURITY
│   │       ├── opcua/          # OPC UA client
│   │       ├── service/        # Core services
│   │       └── data/           # Data models
│   ├── lib-influxdb/           # InfluxDB logger
│   ├── lib-neo4j/              # Neo4j logger
│   ├── lib-iotdb/              # IoTDB logger
│   └── ...other loggers
├── docker/                     # Docker configurations
└── doc/                        # Documentation
```

### GraphQL Server Implementation

**File:** `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/graphql/GraphQLServer.kt`

**Current Implementation (Lines 364-375):**
```kotlin
private fun startGraphQLServer(graphql: GraphQL) {
    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())
    router.route("/graphql").handler(GraphQLWSHandler.create(graphql))
    router.route("/graphql").handler(GraphQLHandler.create(graphql))

    val httpServerOptions = HttpServerOptions()
        .setWebSocketSubProtocols(listOf("graphql-transport-ws"))
    val httpServer = vertx.createHttpServer(httpServerOptions)
    val httpPort = config.getInteger("Port", 4000)
    httpServer.requestHandler(router).listen(httpPort)
}
```

**⚠️ CRITICAL SECURITY GAPS:**
1. **NO AUTHENTICATION** - No auth handler on router
2. **NO AUTHORIZATION** - No role checks
3. **OPEN GraphQL** - Anyone can query/mutate
4. **NO RATE LIMITING** - DoS vulnerable
5. **NO QUERY LIMITS** - Complex queries can crash server
6. **GraphiQL ENABLED** - Schema exploration open (line 35 import)
7. **NO TLS ENFORCEMENT** - HTTP only

### GraphQL API Surface

**Schema (Lines 261-326):**
```graphql
type Query {
  ServerInfo(System: String): ServerInfo
  NodeValue(Type: Type, System: String, NodeId: ID!): Value
  NodeValues(Type: Type, System: String, NodeIds: [ID!]): [Value]
  BrowseNode(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
  FindNodes(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
  QueryHistory(Log: ID, System: String, NodeId: ID!, From: String, To: String, LastSeconds: Int): [Value]
  ExecuteLogSQL(Log: ID, SQL: String): [[String]]  # ⚠️ SQL INJECTION RISK!
}

type Mutation {
  NodeValue(Type: Type, System: String, NodeId: ID!, Value: String!): Boolean
  NodeValues(Type: Type, System: String, NodeIds: [ID!]!, Values: [String!]!): [Boolean]
}

type Subscription {
  NodeValue(Type: Type, System: String, NodeId: ID!): Value
  NodeValues(Type: Type, System: String, NodeIds: [ID!]!): Value
}
```

**Exposed Operations:**
- **READ**: OPC UA/MQTT/PLC4X values, browse nodes, query history
- **WRITE**: Set tag values (mutations)
- **EXECUTE SQL**: Direct SQL execution on logger databases! ⚠️
- **SUBSCRIBE**: Real-time value streaming via WebSocket

**Risk Assessment:**
- **Queries:** Read sensitive process data
- **Mutations:** Write to industrial devices (dangerous!)
- **ExecuteLogSQL:** SQL injection vulnerability
- **Subscriptions:** DoS via many subscriptions

---

## Security Implementation Plan

### Phase 1: GraphQL Authentication (CRITICAL - Week 1)

#### Goal
Add JWT-based authentication to all GraphQL operations

#### Implementation Steps

**1. Add Dependencies** (`source/lib-core/build.gradle`)
```gradle
dependencies {
    // Existing dependencies...

    // Authentication
    implementation "io.vertx:vertx-auth-jwt:$vertxVersion"
    implementation "io.jsonwebtoken:jjwt-api:0.11.5"
    runtimeOnly "io.jsonwebtoken:jjwt-impl:0.11.5"
    runtimeOnly "io.jsonwebtoken:jjwt-jackson:0.11.5"

    // Password hashing
    implementation "org.mindrot:jbcrypt:0.4"
}
```

**2. Create Authentication Service**

**File:** `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/AuthService.kt`
```kotlin
package at.rocworks.gateway.core.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.mindrot.jbcrypt.BCrypt
import java.security.Key
import java.util.*
import java.util.logging.Logger

class AuthService(private val vertx: Vertx, private val secretKey: String) {
    private val logger = Logger.getLogger(this::class.java.simpleName)
    private val key: Key = Keys.hmacShaKeyFor(secretKey.toByteArray())
    private val users = mutableMapOf<String, User>()

    data class User(
        val username: String,
        val passwordHash: String,
        val roles: Set<String>
    )

    init {
        // Load users from config (in production, use database)
        // Default admin user for development
        users["admin"] = User(
            username = "admin",
            passwordHash = BCrypt.hashpw("admin", BCrypt.gensalt()),
            roles = setOf("admin")
        )
    }

    fun authenticate(username: String, password: String): Future<String> {
        val promise = Promise.promise<String>()

        vertx.executeBlocking<String> { blockingPromise ->
            val user = users[username]
            if (user != null && BCrypt.checkpw(password, user.passwordHash)) {
                val token = generateToken(user)
                blockingPromise.complete(token)
            } else {
                blockingPromise.fail("Invalid credentials")
            }
        }.onComplete {
            if (it.succeeded()) {
                promise.complete(it.result())
            } else {
                promise.fail(it.cause())
            }
        }

        return promise.future()
    }

    fun validateToken(token: String): Future<Claims> {
        val promise = Promise.promise<Claims>()
        try {
            val claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body
            promise.complete(claims)
        } catch (e: Exception) {
            logger.warning("Token validation failed: ${e.message}")
            promise.fail("Invalid token")
        }
        return promise.future()
    }

    private fun generateToken(user: User): String {
        val now = Date()
        val expiration = Date(now.time + 3600000) // 1 hour

        return Jwts.builder()
            .setSubject(user.username)
            .claim("roles", user.roles.joinToString(","))
            .setIssuedAt(now)
            .setExpiration(expiration)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun hasRole(claims: Claims, requiredRole: String): Boolean {
        val roles = claims.get("roles", String::class.java)?.split(",") ?: emptyList()
        return when (requiredRole) {
            "viewer" -> roles.any { it in setOf("viewer", "operator", "admin") }
            "operator" -> roles.any { it in setOf("operator", "admin") }
            "admin" -> "admin" in roles
            else -> false
        }
    }
}
```

**3. Create Authentication Handler**

**File:** `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/JWTAuthHandler.kt`
```kotlin
package at.rocworks.gateway.core.auth

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import java.util.logging.Logger

class JWTAuthHandler(private val authService: AuthService) : Handler<RoutingContext> {
    private val logger = Logger.getLogger(this::class.java.simpleName)

    override fun handle(ctx: RoutingContext) {
        val authHeader = ctx.request().getHeader("Authorization")

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warning("Missing or invalid Authorization header")
            ctx.response()
                .setStatusCode(401)
                .end("""{"error": "Authentication required"}""")
            return
        }

        val token = authHeader.substring(7)
        authService.validateToken(token).onComplete { result ->
            if (result.succeeded()) {
                // Store claims in context for later use
                ctx.put("jwt-claims", result.result())
                ctx.next()
            } else {
                logger.warning("Token validation failed: ${result.cause().message}")
                ctx.response()
                    .setStatusCode(401)
                    .end("""{"error": "Invalid token"}""")
            }
        }
    }
}
```

**4. Add Login Endpoint**

**File:** `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/AuthController.kt`
```kotlin
package at.rocworks.gateway.core.auth

import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import java.util.logging.Logger

class AuthController(private val authService: AuthService) {
    private val logger = Logger.getLogger(this::class.java.simpleName)

    fun registerRoutes(router: Router) {
        router.post("/auth/login").handler(loginHandler())
    }

    private fun loginHandler(): Handler<RoutingContext> {
        return Handler { ctx ->
            val body = ctx.body().asJsonObject()
            val username = body.getString("username")
            val password = body.getString("password")

            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                ctx.response()
                    .setStatusCode(400)
                    .end("""{"error": "Username and password required"}""")
                return@Handler
            }

            authService.authenticate(username, password).onComplete { result ->
                if (result.succeeded()) {
                    val response = JsonObject()
                        .put("token", result.result())
                        .put("expires_in", 3600)
                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(response.encode())
                } else {
                    logger.warning("Authentication failed for user: $username")
                    ctx.response()
                        .setStatusCode(401)
                        .end("""{"error": "Invalid credentials"}""")
                }
            }
        }
    }
}
```

**5. Update GraphQLServer.kt**

**Modify:** `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/graphql/GraphQLServer.kt`

Add imports (after line 40):
```kotlin
import at.rocworks.gateway.core.auth.AuthService
import at.rocworks.gateway.core.auth.AuthController
import at.rocworks.gateway.core.auth.JWTAuthHandler
```

Add fields (after line 50):
```kotlin
private val authService: AuthService by lazy {
    val secret = config.getString("JWTSecret") ?: System.getenv("JWT_SECRET") ?: "CHANGE_ME_IN_PRODUCTION"
    if (secret == "CHANGE_ME_IN_PRODUCTION") {
        logger.warning("⚠️ Using default JWT secret! Set JWT_SECRET environment variable in production!")
    }
    AuthService(vertx, secret)
}
private val authEnabled: Boolean = config.getBoolean("AuthEnabled", true)
```

Replace `startGraphQLServer()` method (lines 364-375):
```kotlin
private fun startGraphQLServer(graphql: GraphQL) {
    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())

    // Health endpoint (no auth required)
    router.get("/healthz").handler { ctx ->
        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end("""{"status":"UP","timestamp":"${Instant.now()}"}""")
    }

    // Login endpoint (no auth required)
    val authController = AuthController(authService)
    authController.registerRoutes(router)

    // GraphQL endpoints (auth required if enabled)
    if (authEnabled) {
        logger.info("🔒 Authentication ENABLED for GraphQL")
        val authHandler = JWTAuthHandler(authService)
        router.route("/graphql").handler(authHandler)
        router.route("/graphiql/*").handler(authHandler)
    } else {
        logger.warning("⚠️ Authentication DISABLED - NOT FOR PRODUCTION!")
    }

    router.route("/graphql").handler(GraphQLWSHandler.create(graphql))
    router.route("/graphql").handler(GraphQLHandler.create(graphql))

    // GraphiQL conditionally
    val graphiqlEnabled = config.getBoolean("GraphiQLEnabled", false)
    if (graphiqlEnabled) {
        logger.info("GraphiQL enabled at /graphiql/")
        val options = GraphiQLHandlerOptions().setEnabled(true)
        router.route("/graphiql/*").handler(GraphiQLHandler.create(options))
    }

    val httpServerOptions = HttpServerOptions()
        .setWebSocketSubProtocols(listOf("graphql-transport-ws"))
    val httpServer = vertx.createHttpServer(httpServerOptions)
    val httpPort = config.getInteger("Port", 4000)
    httpServer.requestHandler(router).listen(httpPort)
    logger.info("GraphQL server listening on port $httpPort")
}
```

**6. Update Configuration**

**File:** `source/app/config.yaml` (example)
```yaml
Servers:
  GraphQL:
    - Id: Main
      Port: 4000
      AuthEnabled: true              # Enable authentication
      JWTSecret: "${JWT_SECRET}"     # From environment variable
      GraphiQLEnabled: false         # Disable in production
      LogLevel: INFO
```

#### Testing Authentication

```bash
# 1. Start the gateway
cd /home/user/automation-gateway/source/app
JWT_SECRET="my-super-secret-key" ../gradlew run

# 2. Login
curl -X POST http://localhost:4000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'
# Returns: {"token":"eyJhbGc...","expires_in":3600}

# 3. Query GraphQL with token
TOKEN="eyJhbGc..."
curl -X POST http://localhost:4000/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"{ ServerInfo(System:\"Test\") { Server } }"}'

# 4. Query without token (should fail)
curl -X POST http://localhost:4000/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ ServerInfo(System:\"Test\") { Server } }"}'
# Returns: {"error":"Authentication required"}
```

---

### Phase 2: RBAC Implementation (Week 1-2)

#### Goal
Enforce role-based access control on GraphQL operations

#### Roles
- **admin**: Full access (queries, mutations, schema, SQL)
- **operator**: Read/write operations (no SQL, no schema changes)
- **viewer**: Read-only access

#### Implementation

**File:** `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/RBACDirective.kt`

```kotlin
package at.rocworks.gateway.core.auth

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.idl.SchemaDirectiveWiring
import graphql.schema.idl.SchemaDirectiveWiringEnvironment
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class RequiresRoleDirective : SchemaDirectiveWiring {
    private val logger = Logger.getLogger(this::class.java.simpleName)

    override fun onField(env: SchemaDirectiveWiringEnvironment<GraphQLFieldDefinition>): GraphQLFieldDefinition {
        val field = env.element
        val requiredRole = env.directive.getArgument("role").argumentValue.value as String
        val originalDataFetcher = env.codeRegistry.getDataFetcher(env.fieldsContainer, field)

        val authDataFetcher = DataFetcher<CompletableFuture<Any?>> { fetchEnv ->
            val claims = fetchEnv.getContext<Map<String, Any>>()["jwt-claims"] as io.jsonwebtoken.Claims?

            if (claims == null) {
                val promise = CompletableFuture<Any?>()
                promise.completeExceptionally(SecurityException("Not authenticated"))
                return@DataFetcher promise
            }

            val authService = fetchEnv.getContext<Map<String, Any>>()["authService"] as AuthService
            if (!authService.hasRole(claims, requiredRole)) {
                val promise = CompletableFuture<Any?>()
                promise.completeExceptionally(SecurityException("Insufficient permissions. Required role: $requiredRole"))
                return@DataFetcher promise
            }

            originalDataFetcher.get(fetchEnv)
        }

        env.codeRegistry.dataFetcher(env.fieldsContainer, field, authDataFetcher)
        return field
    }
}
```

**Update Schema** (in `GraphQLServer.kt` lines 261-326):
```graphql
directive @requiresRole(role: String!) on FIELD_DEFINITION

type Query {
  ServerInfo(System: String): ServerInfo @requiresRole(role: "viewer")
  NodeValue(Type: Type, System: String, NodeId: ID!): Value @requiresRole(role: "viewer")
  NodeValues(Type: Type, System: String, NodeIds: [ID!]): [Value] @requiresRole(role: "viewer")
  BrowseNode(Type: Type, System: String, NodeId: ID, Filter: String): [Node] @requiresRole(role: "viewer")
  FindNodes(Type: Type, System: String, NodeId: ID, Filter: String): [Node] @requiresRole(role: "viewer")
  QueryHistory(Log: ID, System: String, NodeId: ID!, From: String, To: String, LastSeconds: Int): [Value] @requiresRole(role: "viewer")
  ExecuteLogSQL(Log: ID, SQL: String): [[String]] @requiresRole(role: "admin")
}

type Mutation {
  NodeValue(Type: Type, System: String, NodeId: ID!, Value: String!): Boolean @requiresRole(role: "operator")
  NodeValues(Type: Type, System: String, NodeIds: [ID!]!, Values: [String!]!): [Boolean] @requiresRole(role: "operator")
}
```

---

### Phase 3: Query Security (Week 2)

#### Goal
Prevent DoS attacks via complex queries

#### Implementation

**1. Add Dependencies**
```gradle
implementation "com.graphql-java:graphql-java-extended-validation:21.0"
```

**2. Add Query Complexity Analysis**

Update `GraphQLServer.kt build()` method:
```kotlin
import graphql.analysis.MaxQueryComplexityInstrumentation
import graphql.analysis.MaxQueryDepthInstrumentation

fun build(schema: String, wiring: RuntimeWiring.Builder): GraphQL {
    try {
        val typeDefinitionRegistry = SchemaParser().parse(schema)
        val graphQLSchema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, wiring.build())

        return GraphQL.newGraphQL(graphQLSchema)
            .instrumentation(MaxQueryDepthInstrumentation(10))          // Max depth: 10
            .instrumentation(MaxQueryComplexityInstrumentation(1000))   // Max complexity: 1000
            .build()
    } catch (e: Exception) {
        e.printStackTrace()
        throw Exception(e.message)
    }
}
```

---

### Phase 4: TLS Configuration (Week 2)

**Update `startGraphQLServer()`:**
```kotlin
val httpServerOptions = HttpServerOptions()
    .setWebSocketSubProtocols(listOf("graphql-transport-ws"))

// TLS configuration
val tlsEnabled = config.getBoolean("TLSEnabled", false)
if (tlsEnabled) {
    val tlsKeyPath = config.getString("TLSKeyPath") ?: System.getenv("TLS_KEY_PATH")
    val tlsCertPath = config.getString("TLSCertPath") ?: System.getenv("TLS_CERT_PATH")

    if (tlsKeyPath != null && tlsCertPath != null) {
        httpServerOptions
            .setSsl(true)
            .setKeyCertOptions(
                io.vertx.core.net.PemKeyCertOptions()
                    .setKeyPath(tlsKeyPath)
                    .setCertPath(tlsCertPath)
            )
        logger.info("🔒 TLS ENABLED")
    } else {
        logger.warning("⚠️ TLS enabled but key/cert paths not configured!")
    }
}
```

---

## Next Steps

1. **Implement Phase 1** (Authentication) - HIGHEST PRIORITY
2. **Test locally** with sample queries
3. **Implement Phase 2** (RBAC)
4. **Add query limits** (Phase 3)
5. **Configure TLS** (Phase 4)
6. **Create Dockerfile**
7. **Set up CI/CD**
8. **Integrate with control plane**

---

## Files to Create/Modify

### New Files
- `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/AuthService.kt`
- `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/JWTAuthHandler.kt`
- `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/AuthController.kt`
- `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/auth/RBACDirective.kt`

### Modified Files
- `source/lib-core/build.gradle` - Add dependencies
- `source/lib-core/src/main/kotlin/at/rocworks/gateway/core/graphql/GraphQLServer.kt` - Add auth
- `source/app/config.yaml` - Add auth config

### Timeline
- **Week 1:** Authentication + RBAC
- **Week 2:** Query limits + TLS
- **Week 3:** Docker + Health endpoints
- **Week 4:** CI/CD + Integration

---

**Ready to start implementation!**
