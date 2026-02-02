package com.viaduct.services

import com.viaduct.AuthenticatedSupabaseClient
import com.viaduct.SupabaseService
import com.viaduct.config.RequestContext
import kotlinx.serialization.Serializable

@Serializable
data class CheckboxGroupEntity(
    val id: String,
    val name: String,
    val description: String? = null,
    val owner_id: String,
    val created_at: String,
    val updated_at: String
)

@Serializable
data class GroupMemberEntity(
    val id: String,
    val group_id: String,
    val user_id: String,
    val joined_at: String
)

@Serializable
data class CreateGroupInput(
    val name: String,
    val description: String? = null,
    val owner_id: String
)

@Serializable
data class AddMemberInput(
    val group_id: String,
    val user_id: String
)

/**
 * Service for managing checkbox groups and group memberships.
 * Handles group creation, membership checks, and group-related queries.
 */
open class GroupService(
    internal val supabaseService: SupabaseService
) {
    /**
     * Check if a user is a member of a specific group.
     * This is called by the policy executor to enforce per-row access control.
     * Uses an authenticated client from the request context to respect RLS policies.
     */
    open suspend fun isUserMemberOfGroup(userId: String, groupId: String, requestContext: RequestContext): Boolean {
        val members = requestContext.authenticatedClient.getGroupMembers(groupId)
        return members.any { it.user_id == userId }
    }

    /**
     * Get all groups that a user is a member of.
     */
    suspend fun getUserGroups(authenticatedClient: AuthenticatedSupabaseClient): List<CheckboxGroupEntity> {
        return authenticatedClient.getGroups()
    }

    /**
     * Get a specific group by ID.
     */
    suspend fun getGroupById(authenticatedClient: AuthenticatedSupabaseClient, groupId: String): CheckboxGroupEntity? {
        return authenticatedClient.getGroupById(groupId)
    }

    /**
     * Create a new checkbox group.
     * The creator is automatically added as a member via database trigger.
     */
    suspend fun createGroup(
        authenticatedClient: AuthenticatedSupabaseClient,
        name: String,
        description: String?,
        ownerId: String
    ): CheckboxGroupEntity {
        return authenticatedClient.createGroup(name, description, ownerId)
    }

    /**
     * Get all members of a group.
     */
    suspend fun getGroupMembers(authenticatedClient: AuthenticatedSupabaseClient, groupId: String): List<GroupMemberEntity> {
        return authenticatedClient.getGroupMembers(groupId)
    }

    /**
     * Add a member to a group.
     */
    suspend fun addGroupMember(
        authenticatedClient: AuthenticatedSupabaseClient,
        groupId: String,
        userId: String
    ): GroupMemberEntity {
        return authenticatedClient.addGroupMember(groupId, userId)
    }

    /**
     * Remove a member from a group.
     */
    suspend fun removeGroupMember(
        authenticatedClient: AuthenticatedSupabaseClient,
        groupId: String,
        userId: String
    ): Boolean {
        return authenticatedClient.removeGroupMember(groupId, userId)
    }

    /**
     * Count the number of members in a group.
     */
    suspend fun getGroupMemberCount(authenticatedClient: AuthenticatedSupabaseClient, groupId: String): Int {
        return authenticatedClient.getGroupMembers(groupId).size
    }
}
