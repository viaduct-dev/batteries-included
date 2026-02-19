package com.viaduct.config

import com.viaduct.SupabaseService
import com.viaduct.resolvers.*
import com.viaduct.services.AuthService
import com.viaduct.services.GroupService
import com.viaduct.services.UserService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Koin module for dependency injection configuration.
 *
 * This module is used standalone (not as a Ktor plugin) so that singletons
 * can survive CRaC checkpoint/restore independently of the server.
 * Request-scoped context creation is handled directly by the auth plugin.
 */
fun appModule(supabaseUrl: String, supabaseKey: String) = module {
    // HTTP client (singleton) - shared across all requests for connection pooling
    single {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000  // 60 seconds for Supabase requests
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    // Core services (singletons)
    single { SupabaseService(supabaseUrl, supabaseKey, get()) }
    singleOf(::AuthService)
    singleOf(::UserService)
    singleOf(::GroupService)

    // Resolvers - Auth (public, no authentication required)
    singleOf(::SignInResolver)
    singleOf(::SignUpResolver)
    singleOf(::RefreshTokenResolver)
    singleOf(::SupabaseConfigResolver)

    // Resolvers - Admin
    singleOf(::PingQueryResolver)
    singleOf(::SetUserAdminResolver)
    singleOf(::UsersQueryResolver)
    singleOf(::SearchUsersQueryResolver)
    singleOf(::DeleteUserResolver)

    // Resolvers - Group Queries
    singleOf(::GroupsQueryResolver)
    singleOf(::GroupQueryResolver)

    // Resolvers - Group Mutations
    singleOf(::CreateGroupResolver)
    singleOf(::AddGroupMemberResolver)
    singleOf(::RemoveGroupMemberResolver)

    // Resolvers - Group Fields
    singleOf(::GroupMembersResolver)
}
