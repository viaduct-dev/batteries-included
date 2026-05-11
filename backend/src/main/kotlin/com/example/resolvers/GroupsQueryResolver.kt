package com.example.resolvers

import com.example.resolvers.resolverbases.QueryResolvers
import com.example.services.GroupService
import viaduct.api.Resolver
import viaduct.api.grts.Group

/**
 * Resolver for the groups query.
 * Returns all groups that the authenticated user is a member of.
 */
@Resolver
class GroupsQueryResolver(
    private val groupService: GroupService
) : QueryResolvers.Groups() {
    override suspend fun resolve(ctx: Context): List<Group> {
        // Use extension property - no need to know about RequestContext!
        val groupEntities = groupService.getUserGroups(ctx.authenticatedClient)

        return groupEntities.map { entity ->
            Group.Builder(ctx)
                .id(ctx.globalIDFor(Group.Reflection, entity.id))
                .name(entity.name)
                .description(entity.description)
                .ownerId(entity.owner_id)
                .createdAt(entity.created_at)
                .updatedAt(entity.updated_at)
                .build()
        }
    }
}
