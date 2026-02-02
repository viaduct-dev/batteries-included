package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.MutationResolvers
import com.viaduct.services.TagService
import viaduct.api.Resolver
import viaduct.api.grts.Tag

/**
 * Resolver for the createTag mutation.
 * Creates a new tag and returns it.
 */
@Resolver
class CreateTagResolver(
    private val tagService: TagService
) : MutationResolvers.CreateTag() {
    override suspend fun resolve(ctx: Context): Tag {
        val input = ctx.arguments.input

        val tagEntity = tagService.createTag(
            authenticatedClient = ctx.authenticatedClient,
            name = input.name,
            color = input.color,
            createdById = ctx.userId
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
