package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.TagResolvers
import viaduct.api.Resolver
import viaduct.api.grts.User

/**
 * Field resolver for Tag.createdBy.
 * Uses node reference pattern - Viaduct handles the actual User resolution.
 */
@Resolver(objectValueFragment = "fragment _ on Tag { createdById }")
class TagCreatedByResolver : TagResolvers.CreatedBy() {
    override suspend fun resolve(ctx: Context): User? {
        // Get the createdById from the parent Tag object
        val createdById = ctx.objectValue.getCreatedById()

        // Return a node reference - Viaduct will call the User Node Resolver
        // This avoids N+1 queries and reuses User resolution logic
        return ctx.nodeFor(ctx.globalIDFor(User.Reflection, createdById))
    }
}
