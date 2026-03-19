package com.example.resolvers

import com.example.resolvers.resolverbases.QueryResolvers
import viaduct.api.Resolver
import java.time.Instant

/**
 * Resolver for the ping query.
 * Returns the current server timestamp to verify API connectivity.
 */
@Resolver
class PingQueryResolver : QueryResolvers.Ping() {
    override suspend fun resolve(ctx: Context): String {
        return "pong: ${Instant.now()}"
    }
}
