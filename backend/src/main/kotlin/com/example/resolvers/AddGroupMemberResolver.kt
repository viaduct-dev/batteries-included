@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)
package com.example.resolvers

import com.example.resolvers.resolverbases.MutationResolvers
import dev.viaduct.persistence.runtime.db.toPgGraphqlInsert
import viaduct.api.grts.GroupMember
import viaduct.api.resolver.Resolver

@Resolver
class AddGroupMemberResolver : MutationResolvers.AddGroupMember() {
    override suspend fun resolve(ctx: Context): GroupMember {
        val member = ctx.authenticatedClient.insertGroupMember(ctx.arguments.input.toPgGraphqlInsert())
        return ctx.ref(ctx.globalIDFor(GroupMember.Reflection, member.id))
    }
}
