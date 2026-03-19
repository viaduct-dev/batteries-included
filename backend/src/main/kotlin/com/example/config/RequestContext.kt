package com.example.config

import com.example.AuthenticatedSupabaseClient
import com.example.GraphQLRequestContext
import org.koin.core.scope.Scope

/**
 * Request-scoped context containing authentication and client information.
 *
 * This data class provides type-safe access to request-specific data
 * instead of forcing callers to use scope.get<T>() everywhere.
 *
 * Each GraphQL request gets its own RequestContext instance that is
 * automatically created by Koin and cleaned up by Ktor's request lifecycle.
 */
data class RequestContext(
    /**
     * The GraphQL request context containing user authentication info.
     */
    val graphQLContext: GraphQLRequestContext,

    /**
     * The authenticated Supabase client configured for the current user.
     */
    val authenticatedClient: AuthenticatedSupabaseClient,

    /**
     * The Koin request scope for this GraphQL request.
     * This allows resolvers to access request-scoped dependencies in a type-safe manner.
     */
    val koinScope: Scope
) {
    /**
     * Get a request-scoped dependency from Koin with type safety.
     * Usage in resolvers: requestContext.get<SomeDependency>()
     */
    inline fun <reified T : Any> get(): T = koinScope.get()
}
