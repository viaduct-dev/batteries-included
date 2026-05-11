package com.example.resolvers

import com.example.AuthenticatedSupabaseClient
import com.example.GraphQLRequestContext
import com.example.config.RequestContext
import viaduct.api.context.ExecutionContext

/**
 * Internal extension to get RequestContext from Viaduct's ExecutionContext.
 * Resolvers should use the specific extension properties/functions instead.
 */
@PublishedApi
internal val ExecutionContext.requestContextInternal: RequestContext
    get() = requestContext as RequestContext

/**
 * Extension property to access the authenticated Supabase client directly.
 * Usage in resolvers: ctx.authenticatedClient
 */
val ExecutionContext.authenticatedClient: AuthenticatedSupabaseClient
    get() = requestContextInternal.authenticatedClient

/**
 * Extension property to access the GraphQL request context (user ID, admin status, etc.)
 * Usage in resolvers: ctx.graphQLContext
 */
val ExecutionContext.graphQLContext: GraphQLRequestContext
    get() = requestContextInternal.graphQLContext

/**
 * Extension property to get the current user ID.
 * Usage in resolvers: ctx.userId
 */
val ExecutionContext.userId: String
    get() = requestContextInternal.graphQLContext.userId

/**
 * Extension property to check if the current user is an admin.
 * Usage in resolvers: if (ctx.isAdmin) { ... }
 */
val ExecutionContext.isAdmin: Boolean
    get() = requestContextInternal.graphQLContext.isAdmin
