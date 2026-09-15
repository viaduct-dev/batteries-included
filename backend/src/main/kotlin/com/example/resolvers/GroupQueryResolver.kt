@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)
package com.example.resolvers

import com.example.resolvers.resolverbases.QueryResolvers
import viaduct.api.grts.Group
import viaduct.api.resolver.Resolver

@Resolver
class GroupQueryResolver : QueryResolvers.Group() {
    override suspend fun resolve(ctx: Context): Group? {
        ctx.authenticatedClient.getGroupById(ctx.arguments.id.internalID) ?: return null
        return ctx.ref(ctx.arguments.id)
    }
}
