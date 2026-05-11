package com.example.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import com.example.SupabaseService
import com.example.config.RequestContext
import com.example.services.AuthService
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*

/**
 * Public operations that don't require authentication.
 * These include auth mutations and config queries.
 */
private val PUBLIC_OPERATIONS = setOf(
    "signIn", "signUp", "refreshToken", "supabaseConfig"
)

/**
 * Check if a GraphQL request is for a public operation.
 * Parses the request body to extract mutation/query names.
 */
private fun isPublicOperation(requestBody: String, objectMapper: ObjectMapper): Boolean {
    try {
        val json = objectMapper.readTree(requestBody)
        val query = json.get("query")?.asText() ?: return false

        // Check if the query contains any public operation names as the primary operation
        return PUBLIC_OPERATIONS.any { op ->
            // Match mutation { signIn or query { supabaseConfig patterns
            query.contains(Regex("""(mutation|query)\s*[^{]*\{\s*$op\b"""))
        }
    } catch (e: Exception) {
        return false
    }
}

/**
 * Ktor plugin for GraphQL authentication
 *
 * This plugin intercepts requests and:
 * 1. Checks if the request is for a public operation (signIn, signUp, etc.)
 * 2. If public, marks the request as unauthenticated and allows it through
 * 3. Otherwise, creates a RequestContext directly from the injected services
 * 4. Stores the RequestContext in request attributes for route handlers
 * 5. Returns 401 Unauthorized if authentication fails
 */
val GraphQLAuthentication = createApplicationPlugin(
    name = "GraphQLAuthentication",
    createConfiguration = ::GraphQLAuthenticationConfiguration
) {
    val objectMapper = pluginConfig.objectMapper
    val authService = pluginConfig.authService
    val supabaseService = pluginConfig.supabaseService
    val httpClient = pluginConfig.httpClient

    onCall { call ->
        // Only apply authentication to GraphQL endpoints, but skip OPTIONS (CORS preflight)
        if (!call.request.local.uri.startsWith("/graphql") || call.request.local.method == HttpMethod.Options) {
            return@onCall
        }

        // Read the request body to check if it's a public operation
        // Note: We need to save it for later since receiveText() consumes the body
        val requestBody = call.receiveText()
        call.attributes.put(RequestBodyKey, requestBody)

        // Check if this is a public operation (no auth required)
        if (isPublicOperation(requestBody, objectMapper)) {
            call.attributes.put(IsPublicOperationKey, true)
            return@onCall
        }

        try {
            // Extract the access token from the Authorization header
            val authHeader = call.request.headers["Authorization"]
            val accessToken = authHeader?.removePrefix("Bearer ")?.trim()
                ?: throw IllegalArgumentException("Authorization header required")

            // Create the request context directly from services
            val graphQLContext = authService.createRequestContext(accessToken)
            val authenticatedClient = supabaseService.createAuthenticatedClient(accessToken, httpClient)
            val requestContext = RequestContext(
                graphQLContext = graphQLContext,
                authenticatedClient = authenticatedClient
            )

            // Store in call attributes for route handlers to access
            call.attributes.put(RequestContextKey, requestContext)

        } catch (e: IllegalArgumentException) {
            // Expected authentication failures (missing token, invalid format, etc.)
            // These are client errors that should return 401 Unauthorized
            call.respond(
                HttpStatusCode.Unauthorized,
                objectMapper.writeValueAsString(mapOf("error" to (e.message ?: "Authentication failed")))
            )
        } catch (e: Exception) {
            // Authentication-related errors (invalid token, token verification failures)
            val rootCause = generateSequence(e as Throwable) { it.cause }.last()
            val rootMessage = rootCause.message ?: e.message

            // Log for monitoring
            call.application.log.warn("Authentication failed: $rootMessage", e)

            // Return user-friendly error message based on the root cause
            val errorMessage = when {
                rootMessage?.contains("Authorization header required", ignoreCase = true) == true ->
                    "Authorization header required"
                rootMessage?.contains("token", ignoreCase = true) == true ->
                    "Invalid or expired token"
                rootMessage?.contains("auth", ignoreCase = true) == true ->
                    "Authentication failed"
                else ->
                    "Invalid or expired token"
            }

            call.respond(
                HttpStatusCode.Unauthorized,
                objectMapper.writeValueAsString(mapOf("error" to errorMessage))
            )
        }
    }
}

/**
 * Configuration for GraphQL authentication plugin.
 * Services are injected from the external Koin container.
 */
class GraphQLAuthenticationConfiguration {
    var objectMapper: ObjectMapper = ObjectMapper()
    lateinit var authService: AuthService
    lateinit var supabaseService: SupabaseService
    lateinit var httpClient: HttpClient
}

/**
 * Attribute key for storing RequestContext in the call
 */
val RequestContextKey = AttributeKey<RequestContext>("RequestContext")

/**
 * Attribute key for storing the request body (consumed by receiveText)
 */
val RequestBodyKey = AttributeKey<String>("RequestBody")

/**
 * Attribute key to mark a request as a public operation
 */
val IsPublicOperationKey = AttributeKey<Boolean>("IsPublicOperation")

/**
 * Extension to get RequestContext from the call attributes
 */
val ApplicationCall.requestContext: RequestContext
    get() = attributes[RequestContextKey]

/**
 * Extension to get the cached request body
 */
val ApplicationCall.cachedRequestBody: String?
    get() = attributes.getOrNull(RequestBodyKey)

/**
 * Extension to check if this is a public operation
 */
val ApplicationCall.isPublicOperation: Boolean
    get() = attributes.getOrNull(IsPublicOperationKey) ?: false
