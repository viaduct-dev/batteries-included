package com.viaduct

import com.typesafe.config.ConfigFactory
import com.viaduct.config.DelegatingTenantCodeInjector
import com.viaduct.config.KoinTenantCodeInjector
import com.viaduct.config.appModule
import com.viaduct.services.AuthService
import com.viaduct.services.GroupService
import com.viaduct.services.UserService
import io.ktor.client.HttpClient
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.crac.Core
import org.koin.dsl.koinApplication
import org.koin.logger.slf4jLogger
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.TenantRegistrationInfo

private val logger = org.slf4j.LoggerFactory.getLogger("CracMain")

/**
 * Application entry point.
 *
 * Startup is split into three phases so that as much initialization as
 * possible is captured in a CRaC checkpoint image. On a JVM without CRaC
 * (or when `-Dcrac.checkpoint` is not set) the checkpoint call is a no-op
 * and all three phases run sequentially as a normal startup.
 *
 * 1. **Pre-initialization** — Compile the Viaduct GraphQL schema, start a
 *    standalone Koin container with all singletons (services, resolvers,
 *    HTTP clients), and wire the [DelegatingTenantCodeInjector]. No Netty
 *    sockets are opened.
 *
 * 2. **Checkpoint / Restore** — If `-Dcrac.checkpoint` is set the JVM
 *    writes a CRaC image and exits. On restore the process resumes here
 *    with the compiled schema, all loaded classes, and all Koin singletons
 *    already in memory.
 *
 * 3. **Server start** — The Ktor Netty server is created and binds its
 *    port. Because Koin is already initialized, only the lightweight Ktor
 *    plugin installation and Netty port bind happen here.
 */
fun main() {
    // ── Phase 1: Pre-initialization (captured in checkpoint) ────────────

    logger.info("Pre-initializing Viaduct schema...")

    val cracInjector = DelegatingTenantCodeInjector()

    val viaduct = BasicViaductFactory.create(
        schemaRegistrationInfo = SchemaRegistrationInfo(
            scopes = listOf(
                SchemaScopeInfo("public", setOf("public")),
                SchemaScopeInfo("default", setOf("default", "public")),
                SchemaScopeInfo("admin", setOf("default", "admin", "public"))
            )
        ),
        tenantRegistrationInfo = TenantRegistrationInfo(
            tenantPackagePrefix = "com.viaduct",
            tenantCodeInjector = cracInjector
        )
    )

    logger.info("Viaduct schema compiled")

    // Read environment variables (baked into checkpoint when built with Docker ARGs)
    val config = ConfigFactory.load()
    val port = config.getInt("ktor.deployment.port")
    val supabaseKey = System.getenv("SUPABASE_ANON_KEY")
    val supabaseProjectId = System.getenv("SUPABASE_PROJECT_ID")
    val supabaseUrl = deriveSupabaseUrl(
        System.getenv("SUPABASE_URL"), supabaseProjectId, supabaseKey
    )
    val configurationComplete = supabaseKey != null

    if (!configurationComplete) {
        logger.warn("SUPABASE_ANON_KEY is not set — GraphQL queries will fail")
    }

    // Start Koin standalone (not tied to Ktor lifecycle) so singletons
    // survive checkpoint/restore independently of the Netty server.
    logger.info("Initializing Koin container...")
    val koin = koinApplication {
        slf4jLogger()
        modules(appModule(supabaseUrl, supabaseKey ?: "NOT_CONFIGURED"))
    }.koin

    // Eagerly resolve all singletons to force class loading and object creation
    koin.get<HttpClient>()
    koin.get<SupabaseService>()
    koin.get<AuthService>()
    koin.get<UserService>()
    koin.get<GroupService>()

    // Wire the delegating injector to the live Koin instance
    cracInjector.delegate = KoinTenantCodeInjector(koin)

    logger.info("Koin container initialized")

    // Start and stop a throwaway Netty server to force all Netty class
    // loading (PlatformDependent, NioIoHandler, epoll probing, native
    // library loading, buffer allocators, etc.) into the checkpoint.
    logger.info("Warming up Netty class loading...")
    val warmup = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        routing { get("/") { call.respondText("warmup", ContentType.Text.Plain) } }
    }.start(wait = false)
    warmup.stop(0, 0)
    logger.info("Netty warm-up complete")

    // ── Phase 2: Checkpoint ─────────────────────────────────────────────

    if (System.getProperty("crac.checkpoint") != null) {
        logger.info("CRaC: Taking checkpoint...")
        Core.checkpointRestore()
        logger.info("CRaC: Restored from checkpoint")
    }

    // ── Phase 3: Start Ktor/Netty server ────────────────────────────────
    // Koin singletons survive in the heap from pre-checkpoint init.
    // Only Ktor plugin installation and Netty port binding happen here.

    logger.info("Starting Ktor server on port $port")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureApplication(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey ?: "NOT_CONFIGURED",
            configurationComplete = configurationComplete,
            viaduct = viaduct,
            cracInjector = cracInjector,
            koin = koin
        )
    }.start(wait = true)
}
