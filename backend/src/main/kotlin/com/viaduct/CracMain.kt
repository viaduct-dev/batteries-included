package com.viaduct

import com.typesafe.config.ConfigFactory
import com.viaduct.config.DelegatingTenantCodeInjector
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.crac.Core
import viaduct.service.BasicViaductFactory
import viaduct.service.SchemaRegistrationInfo
import viaduct.service.SchemaScopeInfo
import viaduct.service.TenantRegistrationInfo

private val logger = org.slf4j.LoggerFactory.getLogger("CracMain")

/**
 * Application entry point.
 *
 * Startup is split into three phases so that the expensive Viaduct schema
 * compilation can be captured in a CRaC checkpoint image. On a JVM without
 * CRaC (or when `-Dcrac.checkpoint` is not set) the checkpoint call is a
 * no-op and all three phases run sequentially as a normal startup.
 *
 * 1. **Pre-initialization** — Compile the Viaduct GraphQL schema using a
 *    [DelegatingTenantCodeInjector]. No sockets or HTTP connections are opened.
 *
 * 2. **Checkpoint / Restore** — If `-Dcrac.checkpoint` is set the JVM writes
 *    a CRaC image and exits. On restore the process resumes here with the
 *    compiled schema already in memory.
 *
 * 3. **Server start** — Environment variables are read (they may differ from
 *    checkpoint time), Koin is initialized with live credentials, the
 *    delegating injector is wired to the real Koin instance, and the Ktor
 *    Netty server binds its port.
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

    // ── Phase 2: Checkpoint ─────────────────────────────────────────────

    if (System.getProperty("crac.checkpoint") != null) {
        logger.info("CRaC: Taking checkpoint...")
        Core.checkpointRestore()
        logger.info("CRaC: Restored from checkpoint")
    }

    // ── Phase 3: Read live environment and start server ─────────────────

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

    logger.info("Starting Ktor server on port $port")

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        configureApplication(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseKey ?: "NOT_CONFIGURED",
            configurationComplete = configurationComplete,
            viaduct = viaduct,
            cracInjector = cracInjector
        )
    }.start(wait = true)
}
