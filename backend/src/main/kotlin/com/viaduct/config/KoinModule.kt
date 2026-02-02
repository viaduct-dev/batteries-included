package com.viaduct.config

import com.viaduct.AuthenticatedSupabaseClient
import com.viaduct.GraphQLRequestContext
import com.viaduct.SupabaseService
import com.viaduct.resolvers.*
import com.viaduct.services.AuthService
import com.viaduct.services.GroupService
import com.viaduct.services.TagService
import com.viaduct.services.UserService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.ApplicationCall
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.module.requestScope

/**
 * Koin module for dependency injection configuration
 */
fun appModule(supabaseUrl: String, supabaseKey: String) = module {
    // HTTP client (singleton) - shared across all requests for connection pooling
    // Note: Koin 4.x doesn't have built-in onClose callbacks for singletons.
    // The HttpClient will be closed when the application shuts down via a shutdown hook
    // or when the Koin context is explicitly stopped.
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
    // Inject the shared HttpClient into SupabaseService for connection pooling
    single { SupabaseService(supabaseUrl, supabaseKey, get()) }
    singleOf(::AuthService)
    singleOf(::UserService)
    singleOf(::GroupService)
    singleOf(::TagService)

    // Request scope - automatically tied to Ktor request lifecycle
    // ApplicationCall is automatically available for injection in this scope
    requestScope {
        // Factory for GraphQLRequestContext - ApplicationCall injected automatically
        scoped {
            GraphQLRequestContextFactory(
                call = get(),
                authService = get()
            ).create()
        }

        // Factory for AuthenticatedSupabaseClient
        scoped<AuthenticatedSupabaseClient> {
            val requestContext = get<GraphQLRequestContext>()
            val supabaseService = get<SupabaseService>()
            val httpClient = get<HttpClient>()
            supabaseService.createAuthenticatedClient(requestContext.accessToken, httpClient)
        }

        // Factory for RequestContext wrapper
        scoped {
            RequestContext(
                graphQLContext = get(),
                authenticatedClient = get(),
                koinScope = this  // Pass the current request scope
            )
        }
    }

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
    singleOf(::GroupMemberCountResolver)
    singleOf(::GroupMemberDisplayNameResolver)
    singleOf(::GroupTagsResolver)

    // Resolvers - User Queries
    singleOf(::UserQueryResolver)

    // Resolvers - Tag Queries
    singleOf(::TagQueryResolver)

    // Resolvers - Tag Fields
    singleOf(::TagCreatedByResolver)
    singleOf(::TagUsageCountResolver)

    // Resolvers - Tag Mutations
    singleOf(::CreateTagResolver)
    singleOf(::UpdateTagResolver)
    singleOf(::DeleteTagResolver)
    singleOf(::DeleteAllTagsResolver)

    // Note: ChecklistItem resolvers moved to examples
    // See: backend/src/main/kotlin/com/viaduct/examples/checklist/resolvers/
}

/**
 * Factory for creating GraphQLRequestContext from ApplicationCall
 * ApplicationCall is automatically injected by Koin's requestScope
 */
class GraphQLRequestContextFactory(
    private val call: ApplicationCall,
    private val authService: AuthService
) {
    fun create(): GraphQLRequestContext {
        val authHeader = call.request.headers["Authorization"]
        val accessToken = authHeader?.removePrefix("Bearer ")?.trim()
            ?: throw IllegalArgumentException("Authorization header required")

        return authService.createRequestContext(accessToken)
    }
}
