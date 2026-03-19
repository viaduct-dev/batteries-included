package com.example

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.example.config.KoinTenantCodeInjector
import com.example.config.RequestContext
import com.example.config.appModule
import com.example.models.GraphQLRequest
import com.example.plugins.GraphQLAuthentication
import com.example.plugins.requestContext
import com.example.policy.GroupMembershipCheckerFactory
import com.example.services.AuthService
import com.example.services.GroupService
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.cors.routing.CORS
import org.slf4j.event.Level
import org.koin.ktor.plugin.Koin
import org.koin.ktor.plugin.scope
import org.koin.ktor.ext.get
import org.koin.ktor.ext.getKoin
import org.koin.logger.slf4jLogger
import viaduct.api.bootstrap.ViaductTenantAPIBootstrapper
import viaduct.service.runtime.SchemaRegistryConfiguration
import viaduct.service.runtime.StandardViaduct
import viaduct.service.api.ExecutionInput as ViaductExecutionInput

/**
 * Configure the Ktor application with GraphQL and authentication
 */
fun Application.module() {
    val supabaseUrl = System.getenv("SUPABASE_URL") ?: "http://127.0.0.1:54321"
    val supabaseKey = System.getenv("SUPABASE_ANON_KEY") ?: error("SUPABASE_ANON_KEY must be set")

    configureApplication(supabaseUrl, supabaseKey)
}

/**
 * Configure the application with the given Supabase credentials
 */
fun Application.configureApplication(supabaseUrl: String, supabaseKey: String) {
    // Create object mapper for JSON serialization
    val objectMapper = jacksonObjectMapper()

    // Install CallLogging for structured request/response logging
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            // Log GraphQL and health endpoints
            call.request.path().startsWith("/graphql") || call.request.path().startsWith("/health")
        }
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val userAgent = call.request.headers["User-Agent"]
            val path = call.request.path()
            "$httpMethod $path - $status - $userAgent"
        }
    }

    // Install ContentNegotiation for automatic JSON serialization
    install(ContentNegotiation) {
        jackson {
            // Use the same ObjectMapper configuration as the manual one
            // This ensures consistent JSON serialization throughout the app
        }
    }

    // Install Koin plugin for dependency injection (Koin 4.x pattern)
    install(Koin) {
        slf4jLogger()
        modules(appModule(supabaseUrl, supabaseKey))
    }

    // Register shutdown hook to close HttpClient properly
    monitor.subscribe(ApplicationStopped) {
        val httpClient = getKoin().getOrNull<HttpClient>()
        httpClient?.close()
    }

    // Install GraphQL authentication plugin
    // This handles extracting and validating auth tokens as a cross-cutting concern
    install(GraphQLAuthentication) {
        this.objectMapper = objectMapper
    }

    // Get the Koin instance from the application context
    val koin = getKoin()

    // Use Koin-based dependency injector for Viaduct resolvers
    val koinInjector = KoinTenantCodeInjector(koin)

    // Get services from Koin for application configuration
    // Note: These are singletons needed at application startup for Viaduct configuration
    val authService = koin.get<AuthService>()
    val groupService = koin.get<GroupService>()
    val supabaseService = koin.get<SupabaseService>()

    // Build Viaduct service using StandardViaduct.Builder
    // Register CheckerExecutorFactory for policy checks
    val viaduct = StandardViaduct.Builder()
        .withTenantAPIBootstrapperBuilder(
            ViaductTenantAPIBootstrapper.Builder()
                .tenantPackagePrefix("com.example")
                .tenantCodeInjector(koinInjector)
        )
        .withSchemaRegistryConfiguration(
            SchemaRegistryConfiguration.fromResources(
                scopes = setOf(
                    SchemaRegistryConfiguration.ScopeConfig("default", setOf("default")),
                    SchemaRegistryConfiguration.ScopeConfig("admin", setOf("default", "admin"))
                ),
                fullSchemaIds = listOf("default")
            )
        )
        .withCheckerExecutorFactoryCreator { schema ->
            GroupMembershipCheckerFactory(schema, groupService)
        }
        .build()

    // Install CORS plugin (with idempotency check for test compatibility)
    if (pluginOrNull(CORS) == null) {
        install(CORS) {
            allowMethod(HttpMethod.Options)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader("X-User-Id")

            // Use environment-based configuration for security
            // In production, set ALLOWED_ORIGINS to comma-separated list of allowed origins
            val allowedOrigins = System.getenv("ALLOWED_ORIGINS")
                ?.split(",")
                ?.map { it.trim() }
                ?: listOf("http://localhost:5173", "http://127.0.0.1:5173")

            allowedOrigins.forEach { origin ->
                // Parse the origin URI to extract host and scheme
                // allowHost() expects just the hostname:port, not the full URL
                val uri = java.net.URI(origin)
                val host = if (uri.port != -1) {
                    "${uri.host}:${uri.port}"
                } else {
                    uri.host
                }
                val scheme = uri.scheme
                allowHost(host, schemes = listOf(scheme))
            }
        }
    }

    routing {
        post("/graphql") {
            // Type-safe deserialization of GraphQL request
            val requestBody = call.receiveText()
            val request = objectMapper.readValue(requestBody, GraphQLRequest::class.java)

            // Get RequestContext - authentication is already handled by the plugin
            val requestContextWrapper = call.requestContext

            // Use AuthService to determine schema ID
            val schemaId = authService.getSchemaId(requestContextWrapper.graphQLContext)

            // Build Viaduct ExecutionInput - pass the RequestContext wrapper
            // Viaduct will pass this to all resolvers and policy executors
            val executionInput = ViaductExecutionInput.create(
                schemaId = schemaId,
                operationText = request.query,
                variables = request.variables,
                requestContext = requestContextWrapper // Pass the typed wrapper!
            )

            // Execute GraphQL query
            val result = viaduct.execute(executionInput)

            // Ktor's ContentNegotiation automatically serializes the response to JSON
            call.respond(HttpStatusCode.OK, result.toSpecification())
        }

        get("/graphiql") {
            val html = this::class.java.classLoader.getResource("graphiql.html")?.readText()
                ?: error("graphiql.html not found in resources")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/health") {
            call.respondText("OK")
        }
    }
}

// Entry point is now EngineMain configured via application.conf
