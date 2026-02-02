package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.MutationResolvers
import com.viaduct.services.TagService
import viaduct.api.Resolver

/**
 * Resolver for the deleteAllTags mutation (admin scope only).
 * Deletes all tags and returns the count deleted.
 */
@Resolver
class DeleteAllTagsResolver(
    private val tagService: TagService
) : MutationResolvers.DeleteAllTags() {
    override suspend fun resolve(ctx: Context): Int {
        return tagService.deleteAllTags(ctx.authenticatedClient)
    }
}
