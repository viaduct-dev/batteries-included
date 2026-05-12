package com.example.resolvers

import com.example.resolvers.resolverbases.QueryResolvers
import com.example.services.AuthService
import viaduct.api.resolver.Resolver
import viaduct.api.grts.SupabaseConfig

/**
 * Resolver for the supabaseConfig query.
 * Returns the Supabase URL and anon key for client initialization.
 * This is a public endpoint - no authentication required.
 * Only returns public/safe credentials (URL and anon key).
 */
@Resolver
class SupabaseConfigResolver(
    private val authService: AuthService
) : QueryResolvers.SupabaseConfig() {
    override suspend fun resolve(ctx: Context): SupabaseConfig {
        return SupabaseConfig.Builder(ctx)
            .url(authService.getSupabaseUrl())
            .anonKey(authService.getSupabaseAnonKey())
            .build()
    }
}
