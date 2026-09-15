package com.example

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserEntity(
    val id: String,
    val email: String,
    val raw_app_meta_data: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val created_at: String
)

/**
 * Request context that can be safely serialized
 * Contains only the user ID, not the authenticated client (which is not serializable)
 */
@Serializable
data class GraphQLRequestContext(
    val userId: String,
    val accessToken: String,
    val isAdmin: Boolean = false
)

/**
 * Auth session response from Supabase GoTrue API
 */
@Serializable
data class AuthSessionResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    val user: AuthUserResponse
)

/**
 * User info from Supabase GoTrue API
 */
@Serializable
data class AuthUserResponse(
    val id: String,
    val email: String? = null
)

/**
 * Sign in/up request body
 */
@Serializable
data class AuthCredentials(
    val email: String,
    val password: String
)

/**
 * Refresh token request body
 */
@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String
)

open class SupabaseService(
    val supabaseUrl: String,
    val supabaseKey: String,
    private val httpClient: HttpClient
) {
    // Admin client for token verification only
    // Uses the shared HttpClient injected from Koin for connection pooling
    // Lazy-initialized to avoid opening HTTP connections at construction time (CRaC-safe)
    private val adminClient: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey
        ) {
            install(Auth) {
                // Configure Auth module to use longer timeout for token verification
                // This is needed because local Supabase can be slow
            }

            httpEngine = httpClient.engine
        }
    }

    /**
     * Verify a JWT access token with Supabase Auth
     * Returns the user info if valid, throws exception if invalid
     */
    suspend fun verifyToken(accessToken: String): UserInfo {
        // Use the admin client to verify the token by fetching user info
        // This makes a request to Supabase Auth to validate the JWT
        val response = adminClient.auth.retrieveUser(accessToken)
        return response
    }

    /**
     * Create an authenticated Supabase client for a specific user
     * This client will use the user's JWT token, enabling RLS policies
     */
    fun createAuthenticatedClient(userAccessToken: String, sharedHttpClient: HttpClient): AuthenticatedSupabaseClient {
        return AuthenticatedSupabaseClient(sharedHttpClient, userAccessToken, supabaseUrl, supabaseKey)
    }

    /**
     * Helper function to extract authenticated client from request context
     * This should be called by resolvers to get a client for database operations
     * Uses the shared HttpClient for connection pooling
     */
    open fun getAuthenticatedClient(requestContext: Any?): AuthenticatedSupabaseClient {
        val context = requestContext as? GraphQLRequestContext
            ?: throw IllegalArgumentException("Authentication required: invalid or missing request context")

        // Use the shared HttpClient injected via constructor
        return createAuthenticatedClient(context.accessToken, httpClient)
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sign in with email and password.
     * Calls Supabase GoTrue API directly.
     */
    suspend fun signIn(email: String, password: String): AuthSessionResponse {
        val response: HttpResponse = httpClient.post("$supabaseUrl/auth/v1/token?grant_type=password") {
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthCredentials.serializer(), AuthCredentials(email, password)))
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            throw IllegalArgumentException("Authentication failed: $errorBody")
        }

        return json.decodeFromString(AuthSessionResponse.serializer(), response.bodyAsText())
    }

    /**
     * Sign up with email and password.
     * Calls Supabase GoTrue API directly.
     */
    suspend fun signUp(email: String, password: String): AuthSessionResponse {
        val response: HttpResponse = httpClient.post("$supabaseUrl/auth/v1/signup") {
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AuthCredentials.serializer(), AuthCredentials(email, password)))
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            throw IllegalArgumentException("Sign up failed: $errorBody")
        }

        return json.decodeFromString(AuthSessionResponse.serializer(), response.bodyAsText())
    }

    /**
     * Refresh an access token using a refresh token.
     * Calls Supabase GoTrue API directly.
     */
    suspend fun refreshToken(refreshToken: String): AuthSessionResponse {
        val response: HttpResponse = httpClient.post("$supabaseUrl/auth/v1/token?grant_type=refresh_token") {
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RefreshTokenRequest.serializer(), RefreshTokenRequest(refreshToken)))
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            throw IllegalArgumentException("Token refresh failed: $errorBody")
        }

        return json.decodeFromString(AuthSessionResponse.serializer(), response.bodyAsText())
    }
}
