package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import com.example.services.GroupService
import viaduct.api.Resolver

/**
 * Resolver for the removeGroupMember mutation.
 * Removes a user from a checkbox group.
 * The group owner or the member themselves can remove the membership (enforced by RLS).
 */
@Resolver
class RemoveGroupMemberResolver(
    private val groupService: GroupService
) : MutationResolvers.RemoveGroupMember() {
    override suspend fun resolve(ctx: Context): Boolean {
        val input = ctx.arguments.input
        // Use Viaduct's internalID property to get the UUID
        val groupId = input.groupId.internalID

        return groupService.removeGroupMember(
            authenticatedClient = ctx.authenticatedClient,
            groupId = groupId,
            userId = input.userId
        )
    }
}
