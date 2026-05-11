package com.example.resolvers

import com.example.resolvers.resolverbases.GroupResolvers
import com.example.services.GroupService
import viaduct.api.Resolver
import viaduct.api.grts.GroupMember

/**
 * Field resolver for Group.members.
 * Returns all members of the group.
 */
@Resolver(objectValueFragment = "fragment _ on Group { id }")
class GroupMembersResolver(
    private val groupService: GroupService
) : GroupResolvers.Members() {
    override suspend fun resolve(ctx: Context): List<GroupMember> {
        // Access parent Group via objectValue
        val groupId = ctx.objectValue.getId().internalID

        val memberEntities = groupService.getGroupMembers(ctx.authenticatedClient, groupId)

        return memberEntities.map { entity ->
            GroupMember.Builder(ctx)
                .id(entity.id)
                .groupId(entity.group_id)
                .userId(entity.user_id)
                .joinedAt(entity.joined_at)
                .build()
        }
    }
}
