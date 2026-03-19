package com.example.plugins

import com.fasterxml.jackson.databind.ObjectMapper
import com.example.config.RequestContext
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.util.*
import org.koin.ktor.plugin.scope

/**
 * Ktor plugin for GraphQL authentication
 *
 * This plugin intercepts requests and:
 * 1. Extracts authentication from the request scope (via Koin)
 * 2. Stores the RequestContext in request attributes for route handlers
 * 3. Returns 401 Unauthorized if authentication fails
 */
val GraphQLAuthentication = createApplicationPlugin(
    name = "GraphQLAuthentication",
    createConfiguration = ::GraphQLAuthenticationConfiguration
) {
    val objectMapper = pluginConfig.objectMapper

    onCall { call ->
        // Only apply authentication to GraphQL endpoints, but skip OPTIONS (CORS preflight)
        if (!call.request.local.uri.startsWith("/graphql") || call.request.local.method == HttpMethod.Options) {
            return@onCall
        }

        try {
            // Get RequestContext from Koin's request scope
            // The requestScope factory automatically:
            // 1. Extracts the token from ApplicationCall headers
            // 2. Verifies the token with AuthService
            // 3. Creates GraphQLRequestContext, AuthenticatedSupabaseClient, and RequestContext
            val requestContext = call.scope.get<RequestContext>()

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
            // Koin may wrap exceptions, so check the root cause for authentication-specific messages
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
 * Configuration for GraphQL authentication plugin
 */
class GraphQLAuthenticationConfiguration {
    var objectMapper: ObjectMapper = ObjectMapper()
}

/**
 * Attribute key for storing RequestContext in the call
 */
val RequestContextKey = AttributeKey<RequestContext>("RequestContext")

/**
 * Extension to get RequestContext from the call attributes
 */
val ApplicationCall.requestContext: RequestContext
    get() = attributes[RequestContextKey]
