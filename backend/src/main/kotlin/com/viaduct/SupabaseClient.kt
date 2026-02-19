package com.viaduct

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.call.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
data class UserEntity(
    val id: String,
    val email: String,
    val raw_app_meta_data: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val created_at: String
)

/**
 * Input for setting user admin status RPC call
 */
@Serializable
data class SetUserAdminInput(
    val target_user_id: String,
    val is_admin: Boolean
)

/**
 * Input for searching users RPC call
 */
@Serializable
data class SearchUsersInput(
    val search_query: String
)

/**
 * Input for deleting user RPC call
 */
@Serializable
data class DeleteUserInput(
    val user_id: String
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
        // Create a client with the anon key for apikey header
        // Use accessToken parameter to set Authorization header for all Postgrest requests
        val client = createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey // Use anon key for apikey header
        ) {
            // Provide the user's access token - this sets the Authorization header for Postgrest
            // Note: Cannot use install(Auth) with custom accessToken provider per Supabase SDK
            accessToken = { userAccessToken }

            install(Postgrest) {
                defaultSchema = "public"
            }
        }

        return AuthenticatedSupabaseClient(client, sharedHttpClient, userAccessToken, supabaseUrl, supabaseKey)
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

/**
 * Wrapper for an authenticated Supabase client
 * This client uses the user's JWT token, so RLS policies will be enforced automatically
 */
class AuthenticatedSupabaseClient(
    private val client: SupabaseClient,
    private val httpClient: HttpClient,
    private val accessToken: String,
    private val supabaseUrl: String,
    private val supabaseKey: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Set a user's admin status
     * Calls the set_user_admin PostgreSQL function
     * Only admins can call this (enforced by the database function)
     */
    suspend fun callSetUserAdmin(userId: String, isAdmin: Boolean) {
        // Call the PostgreSQL RPC function via HTTP
        val input = SetUserAdminInput(target_user_id = userId, is_admin = isAdmin)
        httpClient.post("$supabaseUrl/rest/v1/rpc/set_user_admin") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SetUserAdminInput.serializer(), input))
        }
    }

    /**
     * Get a user by ID
     * Available to all authenticated users
     */
    suspend fun getUserById(userId: String): UserEntity? {
        // Call the PostgreSQL RPC function via HTTP
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/rpc/get_user_by_id") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody("""{"user_id":"$userId"}""")
        }
        val jsonString = response.bodyAsText()
        // The RPC function returns a single user or empty array
        val users = json.decodeFromString<List<UserEntity>>(jsonString)
        return users.firstOrNull()
    }

    /**
     * Get all users in the system
     * Only admins can call this
     */
    suspend fun getAllUsers(): List<UserEntity> {
        // Call the PostgreSQL RPC function via HTTP
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/rpc/get_all_users") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
        }
        val jsonString = response.bodyAsText()
        return json.decodeFromString(jsonString)
    }

    /**
     * Search for users by email
     * Available to all authenticated users
     */
    suspend fun searchUsers(query: String): List<UserEntity> {
        // Call the PostgreSQL RPC function via HTTP
        val input = SearchUsersInput(search_query = query)
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/rpc/search_users") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SearchUsersInput.serializer(), input))
        }
        val jsonString = response.bodyAsText()
        return json.decodeFromString(jsonString)
    }

    /**
     * Delete a user from the system
     * Only admins can call this
     */
    suspend fun deleteUser(userId: String): Boolean {
        // Call the PostgreSQL RPC function via HTTP
        val input = DeleteUserInput(user_id = userId)
        httpClient.post("$supabaseUrl/rest/v1/rpc/delete_user_by_id") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(DeleteUserInput.serializer(), input))
        }
        return true
    }

    /**
     * Get all groups the user is a member of
     * Uses the Supabase Postgrest client which properly handles RLS policies
     */
    suspend fun getGroups(): List<com.viaduct.services.CheckboxGroupEntity> {
        return client.from("groups")
            .select()
            .decodeList<com.viaduct.services.CheckboxGroupEntity>()
    }

    /**
     * Get a specific group by ID
     * Uses the Supabase Postgrest client which properly handles RLS policies
     */
    suspend fun getGroupById(groupId: String): com.viaduct.services.CheckboxGroupEntity? {
        return client.from("groups")
            .select {
                filter {
                    eq("id", groupId)
                }
            }
            .decodeSingleOrNull<com.viaduct.services.CheckboxGroupEntity>()
    }

    /**
     * Create a new group
     */
    suspend fun createGroup(
        name: String,
        description: String?,
        ownerId: String
    ): com.viaduct.services.CheckboxGroupEntity {
        val input = com.viaduct.services.CreateGroupInput(
            name = name,
            description = description,
            owner_id = ownerId
        )
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/groups") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(com.viaduct.services.CreateGroupInput.serializer(), input))
        }
        val jsonString = response.bodyAsText()
        val groups = json.decodeFromString<List<com.viaduct.services.CheckboxGroupEntity>>(jsonString)
        return groups.first()
    }

    /**
     * Get all members of a group
     */
    suspend fun getGroupMembers(groupId: String): List<com.viaduct.services.GroupMemberEntity> {
        val response: HttpResponse = httpClient.get("$supabaseUrl/rest/v1/group_members") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("group_id", "eq.$groupId")
            parameter("select", "*")
        }
        val jsonString = response.bodyAsText()
        return json.decodeFromString(jsonString)
    }

    /**
     * Add a member to a group
     */
    suspend fun addGroupMember(groupId: String, userId: String): com.viaduct.services.GroupMemberEntity {
        val input = com.viaduct.services.AddMemberInput(
            group_id = groupId,
            user_id = userId
        )
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/group_members") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(com.viaduct.services.AddMemberInput.serializer(), input))
        }
        val jsonString = response.bodyAsText()
        val members = json.decodeFromString<List<com.viaduct.services.GroupMemberEntity>>(jsonString)
        return members.first()
    }

    /**
     * Remove a member from a group
     */
    suspend fun removeGroupMember(groupId: String, userId: String): Boolean {
        httpClient.delete("$supabaseUrl/rest/v1/group_members") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("group_id", "eq.$groupId")
            parameter("user_id", "eq.$userId")
        }
        return true
    }
}
