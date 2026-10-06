package com.example.resolvers

import com.example.resolvers.resolverbases.NodeResolvers
import dev.viaduct.persistence.runtime.db.DbClient
import viaduct.api.grts.Group
import viaduct.api.grts.GroupMember
import viaduct.api.resolver.Resolver

@Resolver
class GroupNodeResolver(private val dbClient: DbClient) : NodeResolvers.Group() {
    override suspend fun resolve(ctx: Context): Group =
        dbClient.fetchByInternalId(
            ctx, "groupCollection", ctx.id.internalID,
        )
}

@Resolver
class GroupMemberNodeResolver(private val dbClient: DbClient) : NodeResolvers.GroupMember() {
    override suspend fun resolve(ctx: Context): GroupMember =
        dbClient.fetchByInternalId(
            ctx, "groupMemberCollection", ctx.id.internalID,
        )
}
