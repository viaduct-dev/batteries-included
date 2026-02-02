package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.TagResolvers
import com.viaduct.services.TagService
import viaduct.api.Resolver

/**
 * Field resolver for Tag.usageCount (admin scope only).
 * Returns the number of groups using this tag.
 */
@Resolver(objectValueFragment = "fragment _ on Tag { id }")
class TagUsageCountResolver(
    private val tagService: TagService
) : TagResolvers.UsageCount() {
    override suspend fun resolve(ctx: Context): Int? {
        // Get the tag ID from the parent object
        val tagId = ctx.objectValue.getId().internalID

        // Return the usage count
        return tagService.getTagUsageCount(ctx.authenticatedClient, tagId)
    }
}
