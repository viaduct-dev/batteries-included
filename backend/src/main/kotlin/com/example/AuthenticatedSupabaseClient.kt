package com.example

import dev.viaduct.persistence.runtime.db.PgGraphqlClient
import dev.viaduct.persistence.runtime.db.PgGraphqlEntity
import dev.viaduct.persistence.runtime.db.PgGraphqlFilter
import dev.viaduct.persistence.runtime.db.PgGraphqlObject
import dev.viaduct.persistence.runtime.db.execute
import dev.viaduct.persistence.runtime.db.executeJson
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Application operations outside node resolvers. Transport, record decoding and mutation
 * construction are provided by pg-persistence; Supabase Auth remains in SupabaseService.
 */
class AuthenticatedSupabaseClient(
    httpClient: HttpClient,
    accessToken: String,
    supabaseUrl: String,
    supabaseKey: String,
) {
    private val client = PgGraphqlClient(httpClient, "$supabaseUrl/graphql/v1")
    private val headers = mapOf("Authorization" to "Bearer $accessToken", "apikey" to supabaseKey)

    suspend fun getGroupById(groupId: String): StoredNodeId? =
        client.selectRecords(
            group, "id: uuidId", StoredNodeId.serializer(),
            filter = PgGraphqlFilter.eq("uuidId", groupId), headers = headers,
        ).singleOrNull()

    suspend fun insertGroup(input: PgGraphqlObject): StoredNodeId =
        client.insertRecords(
            group, listOf(input), "id: uuidId", StoredNodeId.serializer(), headers = headers,
        ).single()

    suspend fun isGroupMember(groupId: String, userId: String): Boolean =
        client.selectRecords(
            member, "id: uuidId", StoredNodeId.serializer(),
            filter = membership(groupId, userId), first = 1, headers = headers,
        ).isNotEmpty()

    suspend fun insertGroupMember(input: PgGraphqlObject): StoredNodeId =
        client.insertRecords(
            member, listOf(input), "id: uuidId", StoredNodeId.serializer(), headers = headers,
        ).single()

    suspend fun removeGroupMember(groupId: String, userId: String): Boolean =
        client.delete(
            member,
            membership(groupId, userId),
            atMost = 1, headers = headers,
        ) == 1

    // These SQL functions are application-specific: they retain the existing Auth-user
    // access checks. Anonymous SQL TABLE return values need a JSON-returning wrapper.
    suspend fun getAllUsers(): List<UserEntity> = client.executeJson(
        "query { getAllUsersJson }", "getAllUsersJson", users, headers = headers,
    )

    suspend fun searchUsers(query: String): List<UserEntity> =
        client.executeJson(
            "query Search(\$query: String!) { searchUsersJson(searchQuery: \$query) }",
            "searchUsersJson", users, PgGraphqlObject.of("query" to query), headers,
        )

    suspend fun callSetUserAdmin(userId: String, isAdmin: Boolean) {
        client.execute(
            "mutation Admin(\$id: UUID!, \$admin: Boolean!) { setUserAdminGraphql(targetUserId: \$id, isAdmin: \$admin) }",
            PgGraphqlObject.of("id" to userId, "admin" to isAdmin),
            "setUserAdminGraphql", headers,
        )
    }

    suspend fun deleteUser(userId: String): Boolean = client.execute(
        "mutation Delete(\$id: UUID!) { deleteUserById(userId: \$id) }",
        PgGraphqlObject.of("id" to userId), "deleteUserById", headers,
    ).jsonPrimitive.boolean

    private companion object {
        val users = ListSerializer(UserEntity.serializer())
        val group = PgGraphqlEntity("Group")
        val member = PgGraphqlEntity("GroupMember")
        fun membership(groupId: String, userId: String) =
            PgGraphqlFilter.allOf(PgGraphqlFilter.eq("groupId", groupId), PgGraphqlFilter.eq("userId", userId))
    }
}

@Serializable
data class StoredNodeId(val id: String)
