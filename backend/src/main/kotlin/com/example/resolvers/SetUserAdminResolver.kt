package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import com.example.services.UserService
import viaduct.api.Resolver

@Resolver
class SetUserAdminResolver(
    private val userService: UserService
) : MutationResolvers.SetUserAdmin() {
    override suspend fun resolve(ctx: Context): Boolean {
        val input = ctx.arguments.input
        userService.setUserAdmin(ctx.authenticatedClient, input.userId, input.isAdmin)
        return true
    }
}
