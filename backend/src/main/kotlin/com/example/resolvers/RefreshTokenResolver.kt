package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import com.example.services.AuthService
import viaduct.api.resolver.Resolver
import viaduct.api.grts.AuthSession
import viaduct.api.grts.AuthUser

/**
 * Resolver for the refreshToken mutation.
 * Refreshes an access token using a refresh token.
 * This is a public endpoint - no authentication required.
 */
@Resolver
class RefreshTokenResolver(
    private val authService: AuthService
) : MutationResolvers.RefreshToken() {
    override suspend fun resolve(ctx: Context): AuthSession {
        val input = ctx.arguments.input
        val response = authService.refreshToken(input.refreshToken)

        val user = AuthUser.Builder(ctx)
            .id(response.user.id)
            .email(response.user.email ?: "")
            .build()

        return AuthSession.Builder(ctx)
            .accessToken(response.accessToken)
            .refreshToken(response.refreshToken)
            .expiresIn(response.expiresIn)
            .user(user)
            .build()
    }
}
