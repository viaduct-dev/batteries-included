package com.example.config

import com.example.AuthenticatedSupabaseClient
import com.example.GraphQLRequestContext

/**
 * Request-scoped context containing authentication and client information.
 *
 * This data class provides type-safe access to request-specific data.
 * Each GraphQL request gets its own RequestContext instance created
 * directly by the authentication plugin.
 */
data class RequestContext(
    /**
     * The GraphQL request context containing user authentication info.
     */
    val graphQLContext: GraphQLRequestContext,

    /**
     * The authenticated Supabase client configured for the current user.
     */
    val authenticatedClient: AuthenticatedSupabaseClient
)
