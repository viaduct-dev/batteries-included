@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)
package com.example.resolvers

import com.example.resolvers.resolverbases.QueryResolvers
import dev.viaduct.persistence.runtime.db.DbClient
import viaduct.api.grts.Group
import viaduct.api.resolver.Resolver

@Resolver
class GroupsQueryResolver(private val dbClient: DbClient) : QueryResolvers.Groups() {
    override suspend fun resolve(ctx: Context): List<Group> {
        val groups = mutableListOf<Group>()
        var cursor: String? = null
        do {
            val page = dbClient.fetchUuidConnection(ctx, "groupCollection", first = 100, after = cursor)
            groups.addAll(page.edges.map { ctx.ref(ctx.globalIDFor(Group.Reflection, it.uuidId)) })
            cursor = if (page.pageInfo.hasNextPage) requireNotNull(page.pageInfo.endCursor) else null
        } while (cursor != null)
        return groups
    }
}
