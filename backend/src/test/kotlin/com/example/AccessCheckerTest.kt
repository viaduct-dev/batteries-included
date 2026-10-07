package com.example

import com.example.config.KoinTenantCodeInjector
import com.example.config.RequestContext
import com.example.config.appModule
import com.example.services.GroupService
import com.viaduct.checkers.AccessCheckerExecutorFactory
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.dsl.koinApplication
import viaduct.service.SchemaScopeInfo
import viaduct.engine.api.EngineSchema
import viaduct.service.ViaductBuilder
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import java.util.concurrent.atomic.AtomicInteger

/** Select the admin schema deliberately: runtime checks must not depend on schema visibility. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessCheckerTest {
    private val application = koinApplication { modules(appModule("http://supabase.test", "unused")) }
    private val viaduct = ViaductBuilder()
        .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(KoinTenantCodeInjector(application.koin)))
        .withScopedSchemas(listOf(SchemaScopeInfo.Scoped("admin", setOf("admin", "default", "public"))))
        .withCheckerExecutorFactoryCreator { AccessCheckerExecutorFactory(application.koin.get<GroupService>()) }
        .build()

    @AfterAll
    fun close() {
        application.koin.getOrNull<HttpClient>()?.close()
        application.close()
    }

    @Test
    fun `directives protect arbitrary field and object names without affecting unmarked fields`() {
        val schema = EngineSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse("""
            directive @requiresAdmin on FIELD_DEFINITION | OBJECT
            type Query { renamed: String @requiresAdmin users: String }
            type Protected @requiresAdmin { value: String }
            type Ordinary { value: String }
        """), RuntimeWiring.newRuntimeWiring().build()))
        val factory = AccessCheckerExecutorFactory(application.koin.get<GroupService>())
        listOf(
            factory.checkerExecutorForField(schema, "Query", "renamed") != null,
            factory.checkerExecutorForField(schema, "Query", "users") != null,
            factory.checkerExecutorForType(schema, "Protected") != null,
            factory.checkerExecutorForType(schema, "Ordinary") != null,
        ) shouldBe listOf(true, false, true, false)
    }

    @Test
    fun `non-admin and missing identities cannot execute admin mutations`() = withBackend { client, writes ->
        val results = mutableListOf<Pair<Any?, List<Any?>>>()
        for (context in listOf(requestContext(client, false), null)) {
            for (mutation in listOf(
                "setUserAdmin(input: {userId: \"target\", isAdmin: true})",
                "deleteUser(input: {userId: \"target\"})",
            )) {
                val result = execute("mutation { $mutation }", context)
                results.add(result["data"] to messages(result))
            }
        }
        (results to writes.get()) shouldBe
            (List(4) { null to listOf("java.lang.IllegalAccessException: Administrator access required") } to 0)
    }

    @Test
    fun `admin mutations reach their resolvers`() = withBackend { client, writes ->
        val result = execute("""mutation {
            setUserAdmin(input: {userId: "target", isAdmin: true})
            deleteUser(input: {userId: "target"})
        }""", requestContext(client, true))
        Triple(result["data"], result["errors"], writes.get()) shouldBe
            Triple(mapOf("setUserAdmin" to true, "deleteUser" to true), null, 2)
    }

    @Test
    fun `admin query values are withheld from non-admins`() = withBackend { client, _ ->
        val result = execute("{ users { id } }", requestContext(client, false))
        (result["data"] to messages(result)) shouldBe
            (null to listOf("java.lang.IllegalAccessException: Administrator access required"))
    }

    @Test
    fun `admins can list users`() = withBackend { client, _ ->
        val result = execute("{ users { id } }", requestContext(client, true))
        (result["data"] to result["errors"]) shouldBe (mapOf("users" to emptyList<Any>()) to null)
    }

    private fun withBackend(block: suspend (HttpClient, AtomicInteger) -> Unit) = testApplication {
        val writes = AtomicInteger()
        externalServices {
            hosts("http://supabase.test") {
                routing {
                    post("/graphql/v1") {
                        if ("mutation" in call.receiveText()) writes.incrementAndGet()
                        call.respondText(
                            """{"data":{"getAllUsersJson":"[]","setUserAdminGraphql":true,"deleteUserById":true}}""",
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }
        block(client, writes)
    }

    private fun requestContext(client: HttpClient, admin: Boolean) = RequestContext(
        GraphQLRequestContext("caller", "unused", admin),
        AuthenticatedSupabaseClient(client, "unused", "http://supabase.test", "unused"),
    )

    private suspend fun execute(query: String, context: Any?): Map<String, Any?> =
        viaduct.execute(
            ExecutionInput.create(operationText = query, requestContext = context),
            SchemaId.Scoped("admin", setOf("admin", "default", "public")),
        ).toSpecification()

    private fun messages(result: Map<String, Any?>): List<Any?> =
        (result["errors"] as? List<*>)?.map { (it as Map<*, *>)["message"] }.orEmpty()
}
