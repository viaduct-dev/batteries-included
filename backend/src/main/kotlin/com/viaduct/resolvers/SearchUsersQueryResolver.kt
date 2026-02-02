package com.viaduct.resolvers

import viaduct.api.grts.User
import com.viaduct.resolvers.resolverbases.QueryResolvers
import com.viaduct.services.UserService
import viaduct.api.Resolver
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Resolver
class SearchUsersQueryResolver(
    private val userService: UserService
) : QueryResolvers.SearchUsers() {
    override suspend fun resolve(ctx: Context): List<User> {
        val query = ctx.arguments.query
        val userEntities = userService.searchUsers(ctx.authenticatedClient, query)

        return userEntities.map { entity ->
            // Extract is_admin from raw_app_meta_data
            val isAdminValue = entity.raw_app_meta_data?.get("is_admin")
            val isAdmin = when (isAdminValue) {
                is JsonPrimitive -> isAdminValue.booleanOrNull ?: false
                else -> false
            }

            User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, entity.id))
                .email(entity.email)
                .isAdmin(isAdmin)
                .createdAt(entity.created_at)
                .build()
        }
    }
}
