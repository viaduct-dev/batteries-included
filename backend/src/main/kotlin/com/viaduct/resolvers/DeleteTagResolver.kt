package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.MutationResolvers
import com.viaduct.services.TagService
import viaduct.api.Resolver

/**
 * Resolver for the deleteTag mutation.
 * Deletes a tag by ID and returns true on success.
 */
@Resolver
class DeleteTagResolver(
    private val tagService: TagService
) : MutationResolvers.DeleteTag() {
    override suspend fun resolve(ctx: Context): Boolean {
        // ctx.arguments.id is GlobalID<Tag> thanks to @idOf directive
        val tagId = ctx.arguments.id.internalID

        return tagService.deleteTag(
            authenticatedClient = ctx.authenticatedClient,
            tagId = tagId
        )
    }
}
