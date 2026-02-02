package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.GroupResolvers
import com.viaduct.services.GroupService
import viaduct.api.Resolver

/**
 * Field resolver for Group.memberCount.
 * Returns the count of members in the group.
 */
@Resolver(objectValueFragment = "fragment _ on Group { id }")
class GroupMemberCountResolver(
    private val groupService: GroupService
) : GroupResolvers.MemberCount() {
    override suspend fun resolve(ctx: Context): Int {
        // Access parent Group via objectValue, get the ID
        val groupId = ctx.objectValue.getId().internalID

        return groupService.getGroupMemberCount(ctx.authenticatedClient, groupId)
    }
}
