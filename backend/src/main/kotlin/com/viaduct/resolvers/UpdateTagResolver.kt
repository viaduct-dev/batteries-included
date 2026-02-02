package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.MutationResolvers
import com.viaduct.services.TagService
import viaduct.api.Resolver
import viaduct.api.grts.Tag

/**
 * Resolver for the updateTag mutation.
 * Updates an existing tag and returns it.
 */
@Resolver
class UpdateTagResolver(
    private val tagService: TagService
) : MutationResolvers.UpdateTag() {
    override suspend fun resolve(ctx: Context): Tag {
        val input = ctx.arguments.input

        // input.id is GlobalID<Tag> thanks to @idOf directive
        val tagId = input.id.internalID

        val tagEntity = tagService.updateTag(
            authenticatedClient = ctx.authenticatedClient,
            tagId = tagId,
            name = input.name,
            color = input.color
        )

        return Tag.Builder(ctx)
            .id(ctx.globalIDFor(Tag.Reflection, tagEntity.id))
            .name(tagEntity.name)
            .color(tagEntity.color)
            .createdById(tagEntity.created_by_id)
            .createdAt(tagEntity.created_at)
            .build()
    }
}
