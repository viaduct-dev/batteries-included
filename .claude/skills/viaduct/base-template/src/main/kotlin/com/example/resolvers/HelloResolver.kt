package com.example.resolvers

import com.example.resolvers.resolverbases.QueryResolvers
import viaduct.api.Resolver

@Resolver
class HelloResolver : QueryResolvers.Hello() {
    override suspend fun resolve(ctx: Context): String {
        return "Hello from Viaduct!"
    }
}
