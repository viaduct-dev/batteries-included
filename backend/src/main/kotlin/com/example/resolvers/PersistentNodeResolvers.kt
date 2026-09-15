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
            ctx.ownedSelections().selectionSetFor(Group.Reflection),
            ctx.selections().selectionSetFor(Group.Reflection),
        )
}

@Resolver
class GroupMemberNodeResolver(private val dbClient: DbClient) : NodeResolvers.GroupMember() {
    override suspend fun resolve(ctx: Context): GroupMember =
        dbClient.fetchByInternalId(
            ctx, "groupMemberCollection", ctx.id.internalID,
            ctx.ownedSelections().selectionSetFor(GroupMember.Reflection),
            ctx.selections().selectionSetFor(GroupMember.Reflection),
        )
}
