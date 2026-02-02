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
    private val adminClient: SupabaseClient = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabaseKey
    ) {
        install(Auth) {
            // Configure Auth module to use longer timeout for token verification
            // This is needed because local Supabase can be slow
        }

        httpEngine = httpClient.engine
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

    /**
     * Get a specific tag by ID
     * Uses the Supabase Postgrest client which properly handles RLS policies
     */
    suspend fun getTagById(tagId: String): com.viaduct.services.TagEntity? {
        return client.from("tags")
            .select {
                filter {
                    eq("id", tagId)
                }
            }
            .decodeSingleOrNull<com.viaduct.services.TagEntity>()
    }

    /**
     * Create a new tag
     */
    suspend fun createTag(name: String, color: String?, createdById: String): com.viaduct.services.TagEntity {
        val input = com.viaduct.services.CreateTagInput(name = name, color = color, created_by_id = createdById)
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/tags") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(com.viaduct.services.CreateTagInput.serializer(), input))
        }
        val jsonString = response.bodyAsText()
        val tags = json.decodeFromString<List<com.viaduct.services.TagEntity>>(jsonString)
        return tags.first()
    }

    /**
     * Update an existing tag
     */
    suspend fun updateTag(tagId: String, name: String?, color: String?): com.viaduct.services.TagEntity {
        val updates = buildMap {
            if (name != null) put("name", name)
            if (color != null) put("color", color)
        }
        val response: HttpResponse = httpClient.patch("$supabaseUrl/rest/v1/tags?id=eq.$tagId") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(updates))
        }
        val jsonString = response.bodyAsText()
        val tags = json.decodeFromString<List<com.viaduct.services.TagEntity>>(jsonString)
        return tags.first()
    }

    /**
     * Delete a tag by ID
     */
    suspend fun deleteTag(tagId: String): Boolean {
        httpClient.delete("$supabaseUrl/rest/v1/tags?id=eq.$tagId") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
        }
        return true
    }

    /**
     * Get all tags for a specific group via the group_tags join table
     */
    suspend fun getTagsForGroup(groupId: String): List<com.viaduct.services.TagEntity> {
        // Get tag IDs from join table
        val groupTags = httpClient.get("$supabaseUrl/rest/v1/group_tags?group_id=eq.$groupId&select=tag_id") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
        }.bodyAsText().let { json.decodeFromString<List<com.viaduct.services.GroupTagEntity>>(it) }

        if (groupTags.isEmpty()) return emptyList()

        // Fetch actual tags
        val tagIds = groupTags.map { it.tag_id }
        val tagIdsParam = tagIds.joinToString(",") { "\"$it\"" }
        return httpClient.get("$supabaseUrl/rest/v1/tags?id=in.($tagIdsParam)") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
        }.bodyAsText().let { json.decodeFromString<List<com.viaduct.services.TagEntity>>(it) }
    }

    /**
     * Batch fetch tags for multiple groups.
     * Returns a map of groupId -> list of tags.
     */
    suspend fun getTagsForGroups(groupIds: List<String>): Map<String, List<com.viaduct.services.TagEntity>> {
        if (groupIds.isEmpty()) return emptyMap()

        // Get all group_tags for these groups in one query
        val groupIdsParam = groupIds.joinToString(",") { "\"$it\"" }
        val groupTags = httpClient.get("$supabaseUrl/rest/v1/group_tags?group_id=in.($groupIdsParam)&select=group_id,tag_id") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
        }.bodyAsText().let { json.decodeFromString<List<com.viaduct.services.GroupTagEntity>>(it) }

        if (groupTags.isEmpty()) {
            // Return empty list for each group
            return groupIds.associateWith { emptyList() }
        }

        // Get all unique tag IDs
        val tagIds = groupTags.map { it.tag_id }.distinct()
        val tagIdsParam = tagIds.joinToString(",") { "\"$it\"" }

        // Fetch all tags in one query
        val tags = httpClient.get("$supabaseUrl/rest/v1/tags?id=in.($tagIdsParam)") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
        }.bodyAsText().let { json.decodeFromString<List<com.viaduct.services.TagEntity>>(it) }

        // Create a map of tagId -> tag
        val tagMap = tags.associateBy { it.id }

        // Group by group_id and map to tags
        val groupedTagIds = groupTags.groupBy({ it.group_id }, { it.tag_id })

        // Return a map of groupId -> list of tags, ensuring all requested groups are in the result
        return groupIds.associateWith { groupId ->
            groupedTagIds[groupId]?.mapNotNull { tagId -> tagMap[tagId] } ?: emptyList()
        }
    }

    /**
     * Get usage count for a tag (how many groups use it).
     * Admin-only operation.
     */
    suspend fun getTagUsageCount(tagId: String): Int {
        val response = httpClient.get("$supabaseUrl/rest/v1/group_tags?tag_id=eq.$tagId&select=group_id") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "count=exact")
        }
        // Count from Content-Range header or parse response
        val contentRange = response.headers["Content-Range"]
        if (contentRange != null) {
            // Format: "*/total" or "0-9/total"
            val total = contentRange.substringAfterLast("/").toIntOrNull() ?: 0
            return total
        }
        // Fallback: count response array
        val groupTags = response.bodyAsText().let { json.decodeFromString<List<com.viaduct.services.GroupTagEntity>>(it) }
        return groupTags.size
    }

    /**
     * Delete all tags.
     * Admin-only operation.
     * Returns the number of tags deleted.
     */
    suspend fun deleteAllTags(): Int {
        // First count how many tags exist
        val countResponse = httpClient.get("$supabaseUrl/rest/v1/tags?select=id") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
        }
        val tags = countResponse.bodyAsText().let { json.decodeFromString<List<Map<String, String>>>(it) }
        val count = tags.size

        // Delete all tags (cascades to group_tags due to ON DELETE CASCADE)
        httpClient.delete("$supabaseUrl/rest/v1/tags?id=neq.00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
        }

        return count
    }
}
