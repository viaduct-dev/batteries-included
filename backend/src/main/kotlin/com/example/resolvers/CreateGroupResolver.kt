package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import com.example.services.GroupService
import viaduct.api.Resolver
import viaduct.api.grts.Group

/**
 * Resolver for the createGroup mutation.
 * Creates a new group with the authenticated user as the owner.
 * The owner is automatically added as a member via database trigger.
 */
@Resolver
class CreateGroupResolver(
    private val groupService: GroupService
) : MutationResolvers.CreateGroup() {
    override suspend fun resolve(ctx: Context): Group {
        val input = ctx.arguments.input
        val userId = ctx.userId

        val groupEntity = groupService.createGroup(
            authenticatedClient = ctx.authenticatedClient,
            name = input.name,
            description = input.description,
            ownerId = userId
        )

        return Group.Builder(ctx)
            .id(ctx.globalIDFor(Group.Reflection, groupEntity.id))
            .name(groupEntity.name)
            .description(groupEntity.description)
            .ownerId(groupEntity.owner_id)
            .createdAt(groupEntity.created_at)
            .updatedAt(groupEntity.updated_at)
            .build()
    }
}
