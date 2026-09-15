@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)
package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import viaduct.api.grts.Group
import viaduct.api.resolver.Resolver

@Resolver
class CreateGroupResolver : MutationResolvers.CreateGroup() {
    override suspend fun resolve(ctx: Context): Group {
        // owner_id defaults to auth.uid(); the existing trigger adds the owner as a member.
        val group = ctx.authenticatedClient.insertGroup(ctx.arguments.input.toPgGraphqlInsert())
        return ctx.ref(ctx.globalIDFor(Group.Reflection, group.id))
    }
}
