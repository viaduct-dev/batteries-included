package com.viaduct.resolvers

import viaduct.api.grts.User
import com.viaduct.resolvers.resolverbases.QueryResolvers
import com.viaduct.services.UserService
import viaduct.api.Resolver
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Resolver
class UsersQueryResolver(
    private val userService: UserService
) : QueryResolvers.Users() {
    override suspend fun resolve(ctx: Context): List<User> {
        val userEntities = userService.getAllUsers(ctx.authenticatedClient)

        return userEntities.map { entity ->
            // Extract is_admin from raw_app_meta_data
            val isAdminValue = entity.raw_app_meta_data?.get("is_admin")
            val isAdmin = when (isAdminValue) {
                is JsonPrimitive -> isAdminValue.booleanOrNull ?: false
                else -> false
            }

            User.Builder(ctx)
                .id(ctx.globalIDFor(User.Reflection, entity.id))
                .email(entity.email ?: "")
                .isAdmin(isAdmin)
                .createdAt(entity.created_at)
                .build()
        }
    }
}
