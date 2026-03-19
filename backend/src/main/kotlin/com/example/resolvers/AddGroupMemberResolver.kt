package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import com.example.services.GroupService
import viaduct.api.Resolver
import viaduct.api.grts.GroupMember

/**
 * Resolver for the addGroupMember mutation.
 * Adds a user to a checkbox group.
 * Only the group owner can add members (enforced by RLS).
 */
@Resolver
class AddGroupMemberResolver(
    private val groupService: GroupService
) : MutationResolvers.AddGroupMember() {
    override suspend fun resolve(ctx: Context): GroupMember {
        val input = ctx.arguments.input
        // Use Viaduct's internalID property to get the UUID
        val groupId = input.groupId.internalID

        val memberEntity = groupService.addGroupMember(
            authenticatedClient = ctx.authenticatedClient,
            groupId = groupId,
            userId = input.userId
        )

        return GroupMember.Builder(ctx)
            .id(memberEntity.id)
            .groupId(memberEntity.group_id)
            .userId(memberEntity.user_id)
            .joinedAt(memberEntity.joined_at)
            .build()
    }
}
