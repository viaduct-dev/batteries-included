package com.example

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
data class ChecklistItemEntity(
    val id: String,
    val title: String,
    val completed: Boolean,
    val user_id: String,
    val group_id: String? = null,
    val created_at: String,
    val updated_at: String
)

@Serializable
data class BlogPostEntity(
    val id: String,
    val group_id: String,
    val user_id: String,
    val title: String,
    val slug: String,
    val content: String,
    val published: Boolean,
    val created_at: String,
    val updated_at: String
)

@Serializable
data class UserEntity(
    val id: String,
    val email: String,
    val raw_app_meta_data: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    val created_at: String
)

/**
 * Input for creating a new checklist item
 */
@Serializable
data class CreateChecklistItemInput(
    val title: String,
    val user_id: String,
    val group_id: String? = null,
    val completed: Boolean = false
)

/**
 * Input for updating a checklist item
 */
@Serializable
data class UpdateChecklistItemInput(
    val completed: Boolean? = null,
    val title: String? = null
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
 * Input for creating a new blog post
 */
@Serializable
data class CreateBlogPostInput(
    val group_id: String,
    val user_id: String,
    val title: String,
    val slug: String,
    val content: String,
    val published: Boolean = false
)

/**
 * Input for updating a blog post
 */
@Serializable
data class UpdateBlogPostInput(
    val title: String? = null,
    val slug: String? = null,
    val content: String? = null,
    val published: Boolean? = null
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
    fun createAuthenticatedClient(accessToken: String, sharedHttpClient: HttpClient): AuthenticatedSupabaseClient {
        // Create a client that will use the user's access token for all requests
        // By using the token as the supabaseKey, Postgrest will automatically add it to headers
        val client = createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = accessToken // Pass JWT as the key for Postgrest requests
        ) {
            install(Postgrest)
            httpEngine = httpClient.engine  // Use the injected shared HttpClient
        }

        return AuthenticatedSupabaseClient(client, sharedHttpClient, accessToken, supabaseUrl, supabaseKey)
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
     * Get all checklist items for the authenticated user
     * RLS policies will automatically filter by the user's ID from the JWT
     */
    suspend fun getChecklistItems(): List<ChecklistItemEntity> {
        return client.from("checklist_items")
            .select()
            .decodeList<ChecklistItemEntity>()
    }

    /**
     * Get a checklist item by ID
     * RLS policies will ensure the user can only access their own items
     */
    suspend fun getChecklistItemById(id: String): ChecklistItemEntity? {
        return client.from("checklist_items")
            .select {
                filter {
                    eq("id", id)
                }
            }
            .decodeSingleOrNull<ChecklistItemEntity>()
    }

    /**
     * Create a new checklist item
     * RLS policies will automatically set user_id from the JWT
     */
    suspend fun createChecklistItem(title: String, userId: String, groupId: String? = null): ChecklistItemEntity {
        return client.from("checklist_items")
            .insert(
                CreateChecklistItemInput(
                    title = title,
                    user_id = userId,
                    group_id = groupId,
                    completed = false
                )
            ) {
                select()
            }
            .decodeSingle<ChecklistItemEntity>()
    }

    /**
     * Update a checklist item
     * RLS policies will ensure the user can only update their own items
     */
    suspend fun updateChecklistItem(
        id: String,
        completed: Boolean? = null,
        title: String? = null
    ): ChecklistItemEntity {
        return client.from("checklist_items")
            .update(
                UpdateChecklistItemInput(completed = completed, title = title)
            ) {
                filter {
                    eq("id", id)
                }
                select()
            }
            .decodeSingle<ChecklistItemEntity>()
    }

    /**
     * Delete a checklist item
     * RLS policies will ensure the user can only delete their own items
     */
    suspend fun deleteChecklistItem(id: String): Boolean {
        client.from("checklist_items")
            .delete {
                filter {
                    eq("id", id)
                }
            }
        return true
    }

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
     * Get all checkbox groups the user is a member of
     * Uses the Supabase Postgrest client which properly handles RLS policies
     */
    suspend fun getCheckboxGroups(): List<com.example.services.CheckboxGroupEntity> {
        return client.from("groups")
            .select()
            .decodeList<com.example.services.CheckboxGroupEntity>()
    }

    /**
     * Get a specific checkbox group by ID
     * Uses the Supabase Postgrest client which properly handles RLS policies
     */
    suspend fun getCheckboxGroupById(groupId: String): com.example.services.CheckboxGroupEntity? {
        return client.from("groups")
            .select {
                filter {
                    eq("id", groupId)
                }
            }
            .decodeSingleOrNull<com.example.services.CheckboxGroupEntity>()
    }

    /**
     * Create a new checkbox group
     */
    suspend fun createCheckboxGroup(
        name: String,
        description: String?,
        ownerId: String
    ): com.example.services.CheckboxGroupEntity {
        val input = com.example.services.CreateGroupInput(
            name = name,
            description = description,
            owner_id = ownerId
        )
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/groups") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(com.example.services.CreateGroupInput.serializer(), input))
        }
        val jsonString = response.bodyAsText()
        val groups = json.decodeFromString<List<com.example.services.CheckboxGroupEntity>>(jsonString)
        return groups.first()
    }

    /**
     * Get all members of a group
     */
    suspend fun getGroupMembers(groupId: String): List<com.example.services.GroupMemberEntity> {
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
    suspend fun addGroupMember(groupId: String, userId: String): com.example.services.GroupMemberEntity {
        val input = com.example.services.AddMemberInput(
            group_id = groupId,
            user_id = userId
        )
        val response: HttpResponse = httpClient.post("$supabaseUrl/rest/v1/group_members") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(com.example.services.AddMemberInput.serializer(), input))
        }
        val jsonString = response.bodyAsText()
        val members = json.decodeFromString<List<com.example.services.GroupMemberEntity>>(jsonString)
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
     * Get checklist items for a specific group
     */
    suspend fun getChecklistItemsByGroup(groupId: String): List<ChecklistItemEntity> {
        return client.from("checklist_items")
            .select {
                filter {
                    eq("group_id", groupId)
                }
            }
            .decodeList<ChecklistItemEntity>()
    }

    /**
     * Get all blog posts for a specific group
     * RLS policies will filter based on membership and published status
     */
    suspend fun getBlogPostsByGroup(groupId: String): List<BlogPostEntity> {
        return client.from("blog_posts")
            .select {
                filter {
                    eq("group_id", groupId)
                }
            }
            .decodeList<BlogPostEntity>()
    }

    /**
     * Get a blog post by ID
     * RLS policies will ensure the user can only access posts in groups they're members of
     */
    suspend fun getBlogPostById(id: String): BlogPostEntity? {
        return client.from("blog_posts")
            .select {
                filter {
                    eq("id", id)
                }
            }
            .decodeSingleOrNull<BlogPostEntity>()
    }

    /**
     * Get a blog post by slug within a specific group
     */
    suspend fun getBlogPostBySlug(groupId: String, slug: String): BlogPostEntity? {
        return client.from("blog_posts")
            .select {
                filter {
                    eq("group_id", groupId)
                    eq("slug", slug)
                }
            }
            .decodeSingleOrNull<BlogPostEntity>()
    }

    /**
     * Get all blog posts created by the authenticated user
     */
    suspend fun getMyBlogPosts(userId: String): List<BlogPostEntity> {
        return client.from("blog_posts")
            .select {
                filter {
                    eq("user_id", userId)
                }
            }
            .decodeList<BlogPostEntity>()
    }

    /**
     * Create a new blog post
     * RLS policies will ensure the user is a member of the group
     */
    suspend fun createBlogPost(
        groupId: String,
        userId: String,
        title: String,
        slug: String,
        content: String,
        published: Boolean = false
    ): BlogPostEntity {
        return client.from("blog_posts")
            .insert(
                CreateBlogPostInput(
                    group_id = groupId,
                    user_id = userId,
                    title = title,
                    slug = slug,
                    content = content,
                    published = published
                )
            ) {
                select()
            }
            .decodeSingle<BlogPostEntity>()
    }

    /**
     * Update a blog post
     * RLS policies will ensure the user owns the post
     */
    suspend fun updateBlogPost(
        id: String,
        title: String? = null,
        slug: String? = null,
        content: String? = null,
        published: Boolean? = null
    ): BlogPostEntity {
        return client.from("blog_posts")
            .update(
                UpdateBlogPostInput(
                    title = title,
                    slug = slug,
                    content = content,
                    published = published
                )
            ) {
                filter {
                    eq("id", id)
                }
                select()
            }
            .decodeSingle<BlogPostEntity>()
    }

    /**
     * Delete a blog post
     * RLS policies will ensure the user owns the post
     */
    suspend fun deleteBlogPost(id: String): Boolean {
        client.from("blog_posts")
            .delete {
                filter {
                    eq("id", id)
                }
            }
        return true
    }
}
