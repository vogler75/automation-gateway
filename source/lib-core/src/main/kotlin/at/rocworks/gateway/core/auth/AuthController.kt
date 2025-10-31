package at.rocworks.gateway.core.auth

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import java.util.logging.Logger

/**
 * REST controller for authentication endpoints.
 *
 * Provides:
 * - POST /auth/login - Authenticate and receive JWT token
 * - POST /auth/logout - Invalidate token (client-side)
 * - GET /auth/me - Get current user info
 * - POST /auth/users - Create new user (admin only)
 * - DELETE /auth/users/:username - Remove user (admin only)
 */
class AuthController(
    private val vertx: Vertx,
    private val authService: AuthService
) {
    private val logger = Logger.getLogger(javaClass.name)

    /**
     * Mount authentication routes to the given router.
     */
    fun mount(router: Router) {
        // Enable body parsing for POST requests
        router.route("/auth/*").handler(BodyHandler.create())

        // Public endpoints
        router.post("/auth/login").handler(this::handleLogin)

        // Protected endpoints (require authentication)
        val authHandler = JWTAuthHandler(authService, exemptPaths = setOf("/auth/login", "/healthz", "/ready"))
        router.get("/auth/me").handler(authHandler).handler(this::handleGetCurrentUser)
        router.post("/auth/logout").handler(authHandler).handler(this::handleLogout)

        // Admin-only endpoints
        router.post("/auth/users").handler(authHandler).handler(this::handleCreateUser)
        router.delete("/auth/users/:username").handler(authHandler).handler(this::handleDeleteUser)

        logger.info("Authentication routes mounted at /auth")
    }

    /**
     * POST /auth/login
     * Body: { "username": "admin", "password": "secret" }
     * Response: { "access_token": "...", "token_type": "Bearer", "expires_in": 900, "username": "admin", "roles": [...] }
     */
    private fun handleLogin(context: RoutingContext) {
        try {
            val body = context.body().asJsonObject()
            val username = body.getString("username")
            val password = body.getString("password")

            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                sendError(context, 400, "Username and password are required")
                return
            }

            authService.authenticate(username, password)
                .onSuccess { authToken ->
                    val response = JsonObject()
                        .put("access_token", authToken.accessToken)
                        .put("token_type", authToken.tokenType)
                        .put("expires_in", authToken.expiresIn)
                        .put("username", authToken.username)
                        .put("roles", authToken.roles.toList())

                    sendJson(context, 200, response)
                    logger.info("User logged in: $username")
                }
                .onFailure { error ->
                    logger.warning("Login failed for user $username: ${error.message}")
                    sendError(context, 401, "Invalid credentials")
                }

        } catch (e: Exception) {
            logger.severe("Login error: ${e.message}")
            sendError(context, 400, "Invalid request body")
        }
    }

    /**
     * POST /auth/logout
     * In a stateless JWT system, logout is client-side (delete token).
     * This endpoint can be used for logging/auditing.
     */
    private fun handleLogout(context: RoutingContext) {
        val userContext = JWTAuthHandler.getUserContext(context)
        if (userContext != null) {
            logger.info("User logged out: ${userContext.username}")
        }

        val response = JsonObject()
            .put("message", "Logout successful")
            .put("note", "Please delete the access token on the client side")

        sendJson(context, 200, response)
    }

    /**
     * GET /auth/me
     * Response: { "username": "admin", "roles": ["admin", "operator"], "email": "admin@example.com" }
     */
    private fun handleGetCurrentUser(context: RoutingContext) {
        try {
            val userContext = JWTAuthHandler.requireUserContext(context)

            val response = JsonObject()
                .put("username", userContext.username)
                .put("roles", userContext.roles.toList())
                .put("email", userContext.email)

            sendJson(context, 200, response)

        } catch (e: AuthenticationException) {
            sendError(context, 401, e.message ?: "Unauthorized")
        }
    }

    /**
     * POST /auth/users (admin only)
     * Body: { "username": "newuser", "password": "secret", "roles": ["operator"], "email": "user@example.com" }
     * Response: { "message": "User created", "username": "newuser" }
     */
    private fun handleCreateUser(context: RoutingContext) {
        try {
            // Check admin permission
            JWTAuthHandler.requireRole(context, "admin")

            val body = context.body().asJsonObject()
            val username = body.getString("username")
            val password = body.getString("password")
            val rolesArray = body.getJsonArray("roles")
            val email = body.getString("email")

            if (username.isNullOrBlank() || password.isNullOrBlank() || rolesArray == null) {
                sendError(context, 400, "Username, password, and roles are required")
                return
            }

            val roles = (0 until rolesArray.size())
                .map { rolesArray.getString(it) }
                .toSet()

            authService.addUser(username, password, roles, email)
                .onSuccess {
                    val response = JsonObject()
                        .put("message", "User created successfully")
                        .put("username", username)
                        .put("roles", roles.toList())

                    sendJson(context, 201, response)
                    logger.info("User created: $username by ${JWTAuthHandler.getUserContext(context)?.username}")
                }
                .onFailure { error ->
                    logger.warning("Failed to create user $username: ${error.message}")
                    sendError(context, 400, error.message ?: "Failed to create user")
                }

        } catch (e: AuthorizationException) {
            sendError(context, 403, "Forbidden - admin role required")
        } catch (e: Exception) {
            logger.severe("Error creating user: ${e.message}")
            sendError(context, 400, "Invalid request")
        }
    }

    /**
     * DELETE /auth/users/:username (admin only)
     * Response: { "message": "User deleted", "username": "olduser" }
     */
    private fun handleDeleteUser(context: RoutingContext) {
        try {
            // Check admin permission
            JWTAuthHandler.requireRole(context, "admin")

            val username = context.pathParam("username")

            if (username.isNullOrBlank()) {
                sendError(context, 400, "Username is required")
                return
            }

            // Prevent deleting yourself
            val currentUser = JWTAuthHandler.requireUserContext(context)
            if (currentUser.username == username) {
                sendError(context, 400, "Cannot delete your own account")
                return
            }

            authService.removeUser(username)
                .onSuccess { removed ->
                    if (removed) {
                        val response = JsonObject()
                            .put("message", "User deleted successfully")
                            .put("username", username)

                        sendJson(context, 200, response)
                        logger.info("User deleted: $username by ${currentUser.username}")
                    } else {
                        sendError(context, 404, "User not found")
                    }
                }
                .onFailure { error ->
                    logger.warning("Failed to delete user $username: ${error.message}")
                    sendError(context, 400, error.message ?: "Failed to delete user")
                }

        } catch (e: AuthorizationException) {
            sendError(context, 403, "Forbidden - admin role required")
        } catch (e: Exception) {
            logger.severe("Error deleting user: ${e.message}")
            sendError(context, 400, "Invalid request")
        }
    }

    private fun sendJson(context: RoutingContext, statusCode: Int, body: JsonObject) {
        context.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(body.encodePrettily())
    }

    private fun sendError(context: RoutingContext, statusCode: Int, message: String) {
        val error = JsonObject()
            .put("error", true)
            .put("message", message)
            .put("status", statusCode)

        context.response()
            .setStatusCode(statusCode)
            .putHeader("Content-Type", "application/json")
            .end(error.encodePrettily())
    }
}
