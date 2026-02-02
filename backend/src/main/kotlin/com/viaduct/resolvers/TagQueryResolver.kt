package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.QueryResolvers
import com.viaduct.services.TagService
import viaduct.api.Resolver
import viaduct.api.grts.Tag

/**
 * Resolver for the tag query.
 * Returns a specific tag by ID.
 */
@Resolver
class TagQueryResolver(
    private val tagService: TagService
) : QueryResolvers.Tag() {
    override suspend fun resolve(ctx: Context): Tag? {
        // Use Viaduct's internalID property to get the UUID (thanks to @idOf directive)
        val tagId = ctx.arguments.id.internalID

        val tagEntity = tagService.getTagById(ctx.authenticatedClient, tagId) ?: return null

        return Tag.Builder(ctx)
            .id(ctx.arguments.id)  // Reuse the GlobalID from arguments
            .name(tagEntity.name)
            .color(tagEntity.color)
            .createdById(tagEntity.created_by_id)
            .createdAt(tagEntity.created_at)
            .build()
    }
}
