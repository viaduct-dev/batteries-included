package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import com.example.services.AuthService
import viaduct.api.Resolver
import viaduct.api.grts.AuthSession
import viaduct.api.grts.AuthUser

/**
 * Resolver for the signIn mutation.
 * Authenticates a user with email and password.
 * This is a public endpoint - no authentication required.
 */
@Resolver
class SignInResolver(
    private val authService: AuthService
) : MutationResolvers.SignIn() {
    override suspend fun resolve(ctx: Context): AuthSession {
        val input = ctx.arguments.input
        val response = authService.signIn(input.email, input.password)

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
