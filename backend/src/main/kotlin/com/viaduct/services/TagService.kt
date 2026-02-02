package com.viaduct.services

import com.viaduct.AuthenticatedSupabaseClient
import kotlinx.serialization.Serializable

@Serializable
data class TagEntity(
    val id: String,
    val name: String,
    val color: String? = null,
    val created_by_id: String,
    val internal_notes: String? = null,
    val created_at: String
)

@Serializable
data class CreateTagInput(
    val name: String,
    val color: String? = null,
    val created_by_id: String
)

@Serializable
data class GroupTagEntity(
    val group_id: String,
    val tag_id: String,
    val created_at: String? = null
)

/**
 * Service for managing tags.
 */
class TagService {
    /**
     * Get a specific tag by ID.
     */
    suspend fun getTagById(authenticatedClient: AuthenticatedSupabaseClient, tagId: String): TagEntity? {
        return authenticatedClient.getTagById(tagId)
    }

    /**
     * Create a new tag.
     */
    suspend fun createTag(authenticatedClient: AuthenticatedSupabaseClient, name: String, color: String?, createdById: String): TagEntity {
        return authenticatedClient.createTag(name, color, createdById)
    }

    /**
     * Update an existing tag.
     */
    suspend fun updateTag(authenticatedClient: AuthenticatedSupabaseClient, tagId: String, name: String?, color: String?): TagEntity {
        return authenticatedClient.updateTag(tagId, name, color)
    }

    /**
     * Delete a tag by ID.
     */
    suspend fun deleteTag(authenticatedClient: AuthenticatedSupabaseClient, tagId: String): Boolean {
        return authenticatedClient.deleteTag(tagId)
    }

    /**
     * Get tags for a specific group.
     */
    suspend fun getTagsForGroup(authenticatedClient: AuthenticatedSupabaseClient, groupId: String): List<TagEntity> {
        return authenticatedClient.getTagsForGroup(groupId)
    }

    /**
     * Batch fetch tags for multiple groups.
     * Returns a map of groupId -> list of tags.
     */
    suspend fun getTagsForGroups(authenticatedClient: AuthenticatedSupabaseClient, groupIds: List<String>): Map<String, List<TagEntity>> {
        return authenticatedClient.getTagsForGroups(groupIds)
    }

    /**
     * Get usage count for a tag (how many groups use it).
     * Admin-only operation.
     */
    suspend fun getTagUsageCount(authenticatedClient: AuthenticatedSupabaseClient, tagId: String): Int {
        return authenticatedClient.getTagUsageCount(tagId)
    }

    /**
     * Delete all tags.
     * Admin-only operation.
     */
    suspend fun deleteAllTags(authenticatedClient: AuthenticatedSupabaseClient): Int {
        return authenticatedClient.deleteAllTags()
    }
}
