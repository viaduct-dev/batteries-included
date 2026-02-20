package com.viaduct

import com.typesafe.config.ConfigFactory
import com.viaduct.config.DelegatingTenantCodeInjector
import com.viaduct.config.KoinTenantCodeInjector
import com.viaduct.config.appModule
import com.viaduct.services.AuthService
import com.viaduct.services.GroupService
import com.viaduct.services.UserService
import io.ktor.client.HttpClient
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import org.crac.Core
import org.koin.dsl.koinApplication
import org.koin.logger.slf4jLogger
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.TenantRegistrationInfo

private val logger = org.slf4j.LoggerFactory.getLogger("CracMain")

/**
 * Application entry point with CRaC-optimized startup.
 *
 * Startup is split into phases to maximize what the CRaC checkpoint captures:
 *
 * 1. **Pre-initialization** — Compile the Viaduct GraphQL schema, start a
 *    standalone Koin container, and eagerly resolve all singletons.
 *
 * 2. **Server start** — Create and start the Ktor CIO server. This runs
 *    [configureApplication] which installs all plugins (CORS, auth, content
 *    negotiation, routing). The fully-configured Application and all CIO
 *    classes are now in the heap.
 *
 * 3. **Checkpoint / Restore** — [CracServer.beforeCheckpoint] stops the CIO
 *    engine (unbinds port) while preserving the Application. The checkpoint
 *    captures everything: compiled schema, Koin singletons, Ktor Application
 *    with all plugins and routes, and loaded CIO classes.
 *
 *    On restore, [CracServer.afterRestore] creates a fresh CIO engine that
 *    reuses the existing Application — only port binding happens.
 *    [configureApplication] does NOT run again.
 *
 * On a JVM without CRaC, all phases run sequentially as a normal startup.
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
    // survive checkpoint/restore independently of the server.
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

    // ── Phase 2: Start server (plugins + routes captured in checkpoint) ──

    val isCracCheckpoint = System.getProperty("crac.checkpoint") != null

    if (isCracCheckpoint) {
        // CRaC path: start server, checkpoint, then block after restore.
        //
        // Starting the real server before checkpoint serves two purposes:
        // 1. configureApplication() installs all plugins and routes into the
        //    Application — captured in the checkpoint, never re-run on restore
        // 2. CIO class loading is warmed up (no separate warmup server needed)

        logger.info("Starting Ktor server on port $port (pre-checkpoint)...")

        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            configureApplication(
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseKey ?: "NOT_CONFIGURED",
                configurationComplete = configurationComplete,
                viaduct = viaduct,
                cracInjector = cracInjector,
                koin = koin
            )
        }.start(wait = false)

        logger.info("Server started, Application fully configured")

        // Register CRaC resource. beforeCheckpoint() will stop only the engine;
        // afterRestore() will create a new engine reusing the existing Application.
        val cracServer = CracServer(server, port)

        // ── Phase 3: Checkpoint ─────────────────────────────────────────
        logger.info("CRaC: Taking checkpoint...")
        Core.checkpointRestore()
        // Execution resumes here after restore.
        // CracServer.afterRestore() has already started a new engine.
        logger.info("CRaC: Restored from checkpoint")

        // Block main thread on the restored engine
        cracServer.blockUntilShutdown()

    } else {
        // Non-CRaC path: start normally with blocking.
        // Used during local development (./gradlew run).
        logger.info("Starting Ktor server on port $port")

        embeddedServer(CIO, port = port, host = "0.0.0.0") {
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
}
