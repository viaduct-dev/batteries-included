package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import viaduct.api.resolver.Resolver

@Resolver
class RemoveGroupMemberResolver : MutationResolvers.RemoveGroupMember() {
    override suspend fun resolve(ctx: Context): Boolean =
        ctx.authenticatedClient.removeGroupMember(ctx.arguments.input.groupId.internalID, ctx.arguments.input.userId)
}
