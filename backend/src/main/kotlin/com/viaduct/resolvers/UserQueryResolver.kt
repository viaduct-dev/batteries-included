package com.viaduct.resolvers

import com.viaduct.resolvers.resolverbases.QueryResolvers
import com.viaduct.services.UserService
import viaduct.api.Resolver
import viaduct.api.grts.User

/**
 * Resolver for the user query.
 * Returns a specific user by ID - enables Node resolution for User type.
 */
@Resolver
class UserQueryResolver(
    private val userService: UserService
) : QueryResolvers.User() {
    override suspend fun resolve(ctx: Context): User? {
        // Use Viaduct's internalID property to get the UUID (thanks to @idOf directive)
        val userId = ctx.arguments.id.internalID

        val userEntity = userService.getUserById(ctx.authenticatedClient, userId) ?: return null

        // Extract is_admin from raw_app_meta_data
        val isAdmin = userEntity.raw_app_meta_data
            ?.get("is_admin")
            ?.toString()
            ?.toBooleanStrictOrNull() ?: false

        return User.Builder(ctx)
            .id(ctx.arguments.id)  // Reuse the GlobalID from arguments
            .email(userEntity.email)
            .isAdmin(isAdmin)
            .createdAt(userEntity.created_at)
            .build()
    }
}
