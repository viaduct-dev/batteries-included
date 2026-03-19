package com.example.services

import com.example.AuthenticatedSupabaseClient
import com.example.GraphQLRequestContext
import com.example.SupabaseService
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Service for handling authentication and authorization
 */
class AuthService(
    private val supabaseService: SupabaseService
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verify a JWT access token with Supabase Auth
     * Returns the user info if valid, throws exception if invalid
     */
    suspend fun verifyToken(accessToken: String): UserInfo {
        return supabaseService.verifyToken(accessToken)
    }

    /**
     * Decode JWT token locally to extract user ID and admin status
     * This avoids making a network call to Supabase Auth on every request
     */
    private fun decodeJwtToken(accessToken: String): Pair<String, Boolean> {
        try {
            // JWT format: header.payload.signature
            val parts = accessToken.split(".")
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid JWT token format")
            }

            // Decode the payload (second part)
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            val jsonPayload = json.parseToJsonElement(payload).jsonObject

            // Extract user ID from 'sub' claim
            val userId = jsonPayload["sub"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Missing 'sub' claim in JWT")

            // Extract admin status from app_metadata
            val appMetadata = jsonPayload["app_metadata"]?.jsonObject
            val isAdmin = appMetadata?.get("is_admin")?.jsonPrimitive?.booleanOrNull ?: false

            return Pair(userId, isAdmin)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to decode JWT token: ${e.message}", e)
        }
    }

    /**
     * Create a GraphQL request context from an access token
     * This extracts user information from the JWT without making a network call
     */
    fun createRequestContext(accessToken: String): GraphQLRequestContext {
        val (userId, isAdmin) = decodeJwtToken(accessToken)

        return GraphQLRequestContext(
            userId = userId,
            accessToken = accessToken,
            isAdmin = isAdmin
        )
    }

    /**
     * Get an authenticated Supabase client for the given request context
     */
    fun getAuthenticatedClient(requestContext: Any?): AuthenticatedSupabaseClient {
        return supabaseService.getAuthenticatedClient(requestContext)
    }

    /**
     * Extract the schema ID based on whether the user is an admin
     */
    fun getSchemaId(requestContext: GraphQLRequestContext): String {
        return if (requestContext.isAdmin) "admin" else "default"
    }
}
