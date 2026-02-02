package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.GroupMemberResolvers
import com.viaduct.services.UserService
import viaduct.api.Resolver

/**
 * Field resolver for GroupMember.displayName.
 * Looks up the user by userId and returns a display name.
 */
@Resolver(objectValueFragment = "fragment _ on GroupMember { userId }")
class GroupMemberDisplayNameResolver(
    private val userService: UserService
) : GroupMemberResolvers.DisplayName() {
    override suspend fun resolve(ctx: Context): String? {
        // Access parent GroupMember via objectValue, get the userId
        val userId = ctx.objectValue.getUserId()

        // Look up the user to get their display info
        val user = userService.getUserById(ctx.authenticatedClient, userId)

        // Return email as display name (no firstName/lastName available in this schema)
        return user?.email
    }
}
