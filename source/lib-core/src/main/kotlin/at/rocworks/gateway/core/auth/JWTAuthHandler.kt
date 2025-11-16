package at.rocworks.gateway.core.auth

import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import java.util.logging.Logger

/**
 * Vert.x handler that intercepts HTTP requests and validates JWT tokens.
 *
 * This handler:
 * 1. Extracts JWT token from Authorization header
 * 2. Validates the token using AuthService
 * 3. Adds user context to the routing context
 * 4. Rejects unauthenticated requests with 401
 *
 * Usage:
 * ```
 * val authHandler = JWTAuthHandler(authService)
 * router.route("/graphql").handler(authHandler)
 * router.route("/graphql").handler(GraphQLHandler.create(graphql))
 * ```
 */
class JWTAuthHandler(
    private val authService: AuthService,
    private val exemptPaths: Set<String> = setOf("/auth/login", "/healthz", "/ready")
) : Handler<RoutingContext> {

    private val logger = Logger.getLogger(javaClass.name)

    companion object {
        const val USER_CONTEXT_KEY = "user_context"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }

    override fun handle(context: RoutingContext) {
        val path = context.request().path()

        // Skip authentication for exempt paths
        if (exemptPaths.contains(path)) {
            context.next()
            return
        }

        val authHeader = context.request().getHeader(AUTHORIZATION_HEADER)

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            logger.warning("Missing or invalid Authorization header for path: $path")
            context.response()
                .setStatusCode(401)
                .putHeader("Content-Type", "application/json")
                .end("""{"error": "Unauthorized", "message": "Missing or invalid authentication token"}""")
            return
        }

        val token = authHeader.substring(BEARER_PREFIX.length)

        authService.validateToken(token)
            .onSuccess { claims ->
                try {
                    val userContext = authService.getUserContext(claims)
                    context.put(USER_CONTEXT_KEY, userContext)

                    logger.fine("Authenticated user: ${userContext.username} with roles: ${userContext.roles}")
                    context.next()

                } catch (e: Exception) {
                    logger.severe("Failed to extract user context: ${e.message}")
                    sendUnauthorized(context, "Invalid token claims")
                }
            }
            .onFailure { error ->
                logger.warning("Token validation failed for path $path: ${error.message}")
                sendUnauthorized(context, "Invalid or expired token")
            }
    }

    private fun sendUnauthorized(context: RoutingContext, message: String) {
        context.response()
            .setStatusCode(401)
            .putHeader("Content-Type", "application/json")
            .end("""{"error": "Unauthorized", "message": "$message"}""")
    }

    /**
     * Extract user context from routing context.
     * Should be called from GraphQL resolvers after authentication.
     */
    companion object {
        fun getUserContext(context: RoutingContext): UserContext? {
            return context.get(USER_CONTEXT_KEY)
        }

        fun requireUserContext(context: RoutingContext): UserContext {
            return getUserContext(context)
                ?: throw AuthenticationException("User context not found - authentication required")
        }

        fun requireRole(context: RoutingContext, role: String) {
            val userContext = requireUserContext(context)
            if (!userContext.hasRole(role)) {
                throw AuthorizationException("Insufficient permissions - role '$role' required")
            }
        }

        fun requireAnyRole(context: RoutingContext, vararg roles: String) {
            val userContext = requireUserContext(context)
            if (!userContext.hasAnyRole(*roles)) {
                throw AuthorizationException("Insufficient permissions - one of roles ${roles.joinToString()} required")
            }
        }
    }
}

/**
 * Authorization exception for permission-related errors.
 */
class AuthorizationException(message: String) : Exception(message)
