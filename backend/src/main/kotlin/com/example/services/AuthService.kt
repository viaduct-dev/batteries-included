package com.example.services

import com.example.AuthenticatedSupabaseClient
import com.example.AuthSessionResponse
import com.example.GraphQLRequestContext
import com.example.SupabaseService
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.serialization.json.JsonPrimitive

/**
 * Service for handling authentication and authorization
 */
class AuthService(
    private val supabaseService: SupabaseService
) {
    /**
     * Verify a JWT access token with Supabase Auth
     * Returns the user info if valid, throws exception if invalid
     */
    suspend fun verifyToken(accessToken: String): UserInfo {
        return supabaseService.verifyToken(accessToken)
    }

    /**
     * Use Supabase Auth's verified identity and server-controlled app metadata.
     * Decoding a JWT alone does not authenticate its claims.
     */
    suspend fun createRequestContext(accessToken: String): GraphQLRequestContext {
        val user = verifyToken(accessToken)
        return GraphQLRequestContext(
            userId = user.id,
            accessToken = accessToken,
            isAdmin = user.appMetadata?.get("is_admin") == JsonPrimitive(true),
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

    /**
     * Sign in with email and password.
     * Returns auth session with tokens.
     */
    suspend fun signIn(email: String, password: String): AuthSessionResponse {
        return supabaseService.signIn(email, password)
    }

    /**
     * Sign up with email and password.
     * Returns auth session with tokens.
     */
    suspend fun signUp(email: String, password: String): AuthSessionResponse {
        return supabaseService.signUp(email, password)
    }

    /**
     * Refresh an access token using a refresh token.
     * Returns new auth session with tokens.
     */
    suspend fun refreshToken(refreshToken: String): AuthSessionResponse {
        return supabaseService.refreshToken(refreshToken)
    }

    /**
     * Get the Supabase URL (for public config endpoint)
     */
    fun getSupabaseUrl(): String = supabaseService.supabaseUrl

    /**
     * Get the Supabase anon key (for public config endpoint)
     */
    fun getSupabaseAnonKey(): String = supabaseService.supabaseKey
}
