package com.viaduct

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import org.crac.Context
import org.crac.Core
import org.crac.Resource

private val logger = org.slf4j.LoggerFactory.getLogger("CracServer")

/**
 * CRaC [Resource] that manages the CIO engine lifecycle across checkpoint/restore.
 *
 * **Before checkpoint**: stops the CIO engine (unbinds port, cancels accept loop)
 * while keeping the fully-configured [io.ktor.server.application.Application] alive
 * in the heap — all Ktor plugins and routes remain installed.
 *
 * **After restore**: creates a fresh [CIOApplicationEngine] via [CIO.create] that
 * reuses the existing Application. Only port binding happens;
 * [configureApplication] does NOT run again.
 *
 * @param server The [EmbeddedServer] created and started before checkpoint.
 * @param port   The port to bind on after restore.
 * @param host   The host to bind on after restore.
 */
class CracServer(
    private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val port: Int,
    private val host: String = "0.0.0.0"
) : Resource {

    @Volatile
    var restoredEngine: CIOApplicationEngine? = null
        private set

    init {
        Core.getGlobalContext().register(this)
    }

    override fun beforeCheckpoint(context: Context<out Resource>) {
        logger.info("CRaC: Stopping CIO engine before checkpoint...")

        // Stop ONLY the engine (port unbind + accept loop shutdown).
        // Do NOT call server.stop() — that would invoke destroyApplication()
        // which disposes the Application (cancels coroutine scope, uninstalls plugins).
        server.engine.stop(1000, 2000)

        logger.info("CRaC: Engine stopped. Application with all plugins preserved in heap.")
    }

    override fun afterRestore(context: Context<out Resource>) {
        // Re-read PORT from the environment — at checkpoint time it may not have
        // been set, but the hosting platform (e.g. Render) injects it at runtime.
        val runtimePort = System.getenv("PORT")?.toIntOrNull() ?: port
        logger.info("CRaC: Restoring — creating fresh CIO engine on $host:$runtimePort")

        val config = CIOApplicationEngine.Configuration().apply {
            connector {
                this.port = runtimePort
                this.host = this@CracServer.host
            }
        }

        // Create a new CIO engine reusing the existing Application.
        // The Application survived checkpoint with its full pipeline:
        // CORS, ContentNegotiation, CallLogging, GraphQLAuthentication, routing tree.
        // No modules are re-applied — only port binding happens.
        restoredEngine = CIO.create(
            environment = server.application.environment,
            monitor = server.application.monitor,
            developmentMode = false,
            configuration = config,
            applicationProvider = { server.application }
        )
        restoredEngine!!.start(wait = false)

        logger.info("CRaC: Engine restored and accepting connections on port $runtimePort")
    }

    /**
     * Block the calling thread until the server shuts down.
     *
     * Calling [CIOApplicationEngine.start] with `wait=true` on an already-started
     * engine is idempotent — the lazy serverJob is already running, the completed
     * startupJob returns immediately, and `serverJob.join()` blocks until shutdown.
     */
    fun blockUntilShutdown() {
        val engine = restoredEngine
            ?: error("blockUntilShutdown called but no restored engine exists")
        engine.start(wait = true)
    }
}
