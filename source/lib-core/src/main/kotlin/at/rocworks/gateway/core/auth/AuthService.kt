package at.rocworks.gateway.core.auth

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.mindrot.jbcrypt.BCrypt
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.logging.Logger
import javax.crypto.SecretKey

/**
 * Authentication service responsible for user authentication and JWT token management.
 *
 * Features:
 * - BCrypt password hashing for secure credential storage
 * - JWT token generation with configurable expiration
 * - Token validation and claims extraction
 * - Role-based access control support
 * - In-memory user store (production should use external identity provider)
 */
class AuthService(
    private val vertx: Vertx,
    private val secretKey: String,
    private val tokenExpirationMinutes: Int = 15
) {
    private val logger = Logger.getLogger(javaClass.name)
    private val signingKey: SecretKey = Keys.hmacShaKeyFor(secretKey.toByteArray(StandardCharsets.UTF_8))

    // In-memory user store - In production, integrate with external identity provider
    private val users = mutableMapOf<String, User>()

    data class User(
        val username: String,
        val passwordHash: String,
        val roles: Set<String>,
        val email: String? = null
    )

    data class AuthToken(
        val accessToken: String,
        val tokenType: String = "Bearer",
        val expiresIn: Long,
        val username: String,
        val roles: Set<String>
    )

    init {
        // Initialize with default admin user if no users exist
        // In production, this should be configured via environment variables
        val adminPassword = System.getenv("ADMIN_PASSWORD") ?: "changeme"
        val adminUser = User(
            username = "admin",
            passwordHash = BCrypt.hashpw(adminPassword, BCrypt.gensalt()),
            roles = setOf("admin", "operator", "viewer"),
            email = "admin@automation-gateway.local"
        )
        users[adminUser.username] = adminUser

        logger.info("AuthService initialized with ${users.size} user(s)")
        logger.warning("Default admin user created - change password immediately in production!")
    }

    /**
     * Authenticate user with username and password.
     * Returns a JWT token on success.
     */
    fun authenticate(username: String, password: String): Future<AuthToken> {
        return vertx.executeBlocking(java.util.concurrent.Callable {
            val user = users[username]
                ?: throw AuthenticationException("Invalid credentials")

            if (!BCrypt.checkpw(password, user.passwordHash)) {
                logger.warning("Failed login attempt for user: $username")
                throw AuthenticationException("Invalid credentials")
            }

            val token = generateToken(user)
            logger.info("Successful authentication for user: $username")
            token
        })
    }

    /**
     * Generate JWT token for authenticated user.
     */
    private fun generateToken(user: User): AuthToken {
        val now = Instant.now()
        val expiration = now.plus(tokenExpirationMinutes.toLong(), ChronoUnit.MINUTES)

        val token = Jwts.builder()
            .subject(user.username)
            .claim("roles", user.roles.toList())
            .claim("email", user.email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiration))
            .signWith(signingKey)
            .compact()

        return AuthToken(
            accessToken = token,
            expiresIn = tokenExpirationMinutes * 60L,
            username = user.username,
            roles = user.roles
        )
    }

    /**
     * Validate JWT token and extract claims.
     */
    fun validateToken(token: String): Future<Claims> {
        return vertx.executeBlocking(java.util.concurrent.Callable {
            try {
                Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .payload
            } catch (e: Exception) {
                logger.warning("Token validation failed: ${e.message}")
                throw AuthenticationException("Invalid or expired token", e)
            }
        })
    }

    /**
     * Extract user context from validated claims.
     */
    fun getUserContext(claims: Claims): UserContext {
        val username = claims.subject
        @Suppress("UNCHECKED_CAST")
        val roles = (claims["roles"] as? List<String>)?.toSet() ?: emptySet()
        val email = claims["email"] as? String

        return UserContext(
            username = username,
            roles = roles,
            email = email
        )
    }

    /**
     * Add a new user to the system (admin only).
     * In production, this would integrate with external identity provider.
     */
    fun addUser(username: String, password: String, roles: Set<String>, email: String? = null): Future<User> {
        return vertx.executeBlocking(java.util.concurrent.Callable {
            if (users.containsKey(username)) {
                throw AuthenticationException("User already exists")
            }

            val user = User(
                username = username,
                passwordHash = BCrypt.hashpw(password, BCrypt.gensalt()),
                roles = roles,
                email = email
            )

            users[username] = user
            logger.info("Created user: $username with roles: $roles")
            user
        })
    }

    /**
     * Remove a user from the system (admin only).
     */
    fun removeUser(username: String): Future<Boolean> {
        return vertx.executeBlocking(java.util.concurrent.Callable {
            val removed = users.remove(username) != null
            if (removed) {
                logger.info("Removed user: $username")
            }
            removed
        })
    }

    /**
     * Load users from configuration.
     * Expected format:
     * {
     *   "users": [
     *     {
     *       "username": "admin",
     *       "password": "hashed_password",
     *       "roles": ["admin", "operator"],
     *       "email": "admin@example.com"
     *     }
     *   ]
     * }
     */
    fun loadUsersFromConfig(config: JsonObject): Future<Int> {
        return vertx.executeBlocking(java.util.concurrent.Callable {
            val usersArray = config.getJsonArray("users") ?: JsonArray()
            var count = 0

            for (i in 0 until usersArray.size()) {
                val userJson = usersArray.getJsonObject(i)
                val username = userJson.getString("username")
                val passwordHash = userJson.getString("password")
                val rolesArray = userJson.getJsonArray("roles")
                val roles = (0 until rolesArray.size())
                    .map { rolesArray.getString(it) }
                    .toSet()
                val email = userJson.getString("email")

                val user = User(username, passwordHash, roles, email)
                users[username] = user
                count++
            }

            logger.info("Loaded $count users from configuration")
            count
        })
    }
}

/**
 * User context extracted from authenticated request.
 */
data class UserContext(
    val username: String,
    val roles: Set<String>,
    val email: String?
) {
    fun hasRole(role: String): Boolean = roles.contains(role)

    fun hasAnyRole(vararg requiredRoles: String): Boolean =
        requiredRoles.any { roles.contains(it) }
}

/**
 * Authentication exception for auth-related errors.
 */
class AuthenticationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
