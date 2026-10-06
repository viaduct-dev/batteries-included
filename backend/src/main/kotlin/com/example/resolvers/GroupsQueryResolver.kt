package com.example.resolvers

import com.example.resolvers.resolverbases.QueryResolvers
import viaduct.api.grts.Group
import viaduct.api.resolver.Resolver

@Resolver
class GroupsQueryResolver : QueryResolvers.Groups() {
    override suspend fun resolve(ctx: Context): List<Group> =
        ctx.authenticatedClient.selectNodeIds("Group").map { ctx.ref(ctx.globalIDFor(Group.Reflection, it)) }
}
