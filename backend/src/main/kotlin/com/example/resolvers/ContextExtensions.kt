package com.example.resolvers

import com.example.AuthenticatedSupabaseClient
import com.example.GraphQLRequestContext
import com.example.config.RequestContext
import viaduct.api.context.FieldExecutionContext

/**
 * Internal extension to get RequestContext from Viaduct's FieldExecutionContext.
 * Resolvers should use the specific extension properties/functions instead.
 */
@PublishedApi
internal val FieldExecutionContext<*, *, *, *>.requestContextInternal: RequestContext
    get() = requestContext as RequestContext

/**
 * Get a request-scoped dependency from Koin with type safety.
 * Usage in resolvers: ctx.get<SomeDependency>()
 *
 * This allows resolvers to access request-scoped dependencies without
 * needing to know about RequestContext at all.
 */
inline fun <reified T : Any> FieldExecutionContext<*, *, *, *>.get(): T =
    requestContextInternal.get()

/**
 * Extension property to access the authenticated Supabase client directly.
 * Usage in resolvers: ctx.authenticatedClient
 */
val FieldExecutionContext<*, *, *, *>.authenticatedClient: AuthenticatedSupabaseClient
    get() = requestContextInternal.authenticatedClient

/**
 * Extension property to access the GraphQL request context (user ID, admin status, etc.)
 * Usage in resolvers: ctx.graphQLContext
 */
val FieldExecutionContext<*, *, *, *>.graphQLContext: GraphQLRequestContext
    get() = requestContextInternal.graphQLContext

/**
 * Extension property to get the current user ID.
 * Usage in resolvers: ctx.userId
 */
val FieldExecutionContext<*, *, *, *>.userId: String
    get() = requestContextInternal.graphQLContext.userId

/**
 * Extension property to check if the current user is an admin.
 * Usage in resolvers: if (ctx.isAdmin) { ... }
 */
val FieldExecutionContext<*, *, *, *>.isAdmin: Boolean
    get() = requestContextInternal.graphQLContext.isAdmin
