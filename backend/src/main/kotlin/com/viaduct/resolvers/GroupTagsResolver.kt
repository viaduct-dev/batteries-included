package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.GroupResolvers
import com.viaduct.services.TagService
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.api.grts.Tag

/**
 * Field resolver for Group.tags with batch resolution.
 * Uses batchResolve to avoid N+1 queries when fetching tags for multiple groups.
 */
@Resolver(objectValueFragment = "fragment _ on Group { id }")
class GroupTagsResolver(
    private val tagService: TagService
) : GroupResolvers.Tags() {

    /**
     * Batch resolve tags for multiple groups in a single database call.
     * This avoids N+1 queries when querying multiple groups with their tags.
     */
    override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<List<Tag>>> {
        // Collect all group IDs from the contexts
        val groupIds = contexts.map { ctx ->
            ctx.objectValue.getId().internalID
        }

        // Single batch call to fetch tags for all groups
        // Use the authenticated client from the first context (all contexts share the same request)
        val tagsByGroup = tagService.getTagsForGroups(
            contexts.first().authenticatedClient,
            groupIds
        )

        // Map results back to contexts, preserving order
        return contexts.map { ctx ->
            val groupId = ctx.objectValue.getId().internalID
            val tags = tagsByGroup[groupId] ?: emptyList()

            FieldValue.ofValue(
                tags.map { tagEntity ->
                    Tag.Builder(ctx)
                        .id(ctx.globalIDFor(Tag.Reflection, tagEntity.id))
                        .name(tagEntity.name)
                        .color(tagEntity.color)
                        .createdById(tagEntity.created_by_id)
                        .createdAt(tagEntity.created_at)
                        .build()
                }
            )
        }
    }
}
