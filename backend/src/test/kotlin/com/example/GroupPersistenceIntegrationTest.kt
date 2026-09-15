package com.example

import com.example.config.DelegatingTenantCodeInjector
import com.example.config.KoinTenantCodeInjector
import com.example.config.appModule
import com.example.services.GroupService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.viaduct.checkers.GroupMembershipCheckerExecutorFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.*
import org.koin.dsl.koinApplication
import viaduct.service.SchemaScopeInfo
import viaduct.service.ViaductBuilder
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory
import java.util.UUID

/** Real Viaduct requests, real checker executors, real pg_graphql and Supabase Auth. */
class GroupPersistenceIntegrationTest : FunSpec({
    val fixture = PersistenceFixture()
    lateinit var owner: AuthSessionResponse
    lateinit var member: AuthSessionResponse
    lateinit var outsider: AuthSessionResponse
    lateinit var admin: AuthSessionResponse

    beforeSpec {
        owner = fixture.user()
        member = fixture.user()
        outsider = fixture.user()
        admin = fixture.admin()
    }
    afterSpec { fixture.close() }

    test("inserting a GRT input returns requested fields and automatically adds the owner") {
        fixture.withApp {
            val result = fixture.createGroup(client, owner)
            result["name"].asText() shouldBe "Guest community"
            result["description"].asText() shouldBe "A community"
            result["ownerId"].asText() shouldBe owner.user.id
            result["members"].map { it["userId"].asText() } shouldBe listOf(owner.user.id)
        }
    }

    test("omitted nullable insert fields remain null") {
        fixture.withApp {
            val data = fixture.query(client, owner,
                """mutation { createGroup(input: {name: "No description"}) { description } }""")
            data["createGroup"]["description"].isNull shouldBe true
        }
    }

    test("group and node queries resolve the same persisted record") {
        fixture.withApp {
            val group = fixture.createGroup(client, owner)
            val data = fixture.query(client, owner,
                """query(${'$'}id: ID!) {
                  group(id: ${'$'}id) { id name }
                  node(id: ${'$'}id) { ... on Group { id name } }
                }""", mapOf("id" to group["id"].asText()))
            data["group"] shouldBe data["node"]
        }
    }

    test("members use typed IDs and resolve through a persistent relationship") {
        fixture.withApp {
            val groupId = fixture.createGroup(client, owner)["id"].asText()
            val added = fixture.addMember(client, owner, groupId, member.user.id)
            added["groupId"].asText() shouldBe groupId
            val data = fixture.query(client, member,
                """query(${'$'}id: ID!) { group(id: ${'$'}id) { members { id userId } } }""",
                mapOf("id" to groupId))
            data["group"]["members"].map { it["id"].asText() } shouldContain added["id"].asText()
        }
    }

    test("outsiders cannot read or list another user's group") {
        fixture.withApp {
            val groupId = fixture.createGroup(client, owner)["id"].asText()
            val data = fixture.query(client, outsider,
                """query(${'$'}id: ID!) { group(id: ${'$'}id) { name } groups { id } }""",
                mapOf("id" to groupId))
            data["group"].isNull shouldBe true
            data["groups"].toList() shouldHaveSize 0
        }
    }

    test("non-owners cannot add members even when they belong to the group") {
        fixture.withApp {
            val groupId = fixture.createGroup(client, owner)["id"].asText()
            fixture.addMember(client, owner, groupId, member.user.id)
            val response = fixture.response(client, member, PersistenceFixture.addMember,
                mapOf("groupId" to groupId, "userId" to outsider.user.id))
            response["errors"].isArray shouldBe true
            response["data"].isNull shouldBe true
        }
    }

    test("duplicate membership fails without creating another row") {
        fixture.withApp {
            val groupId = fixture.createGroup(client, owner)["id"].asText()
            fixture.addMember(client, owner, groupId, member.user.id)
            val response = fixture.response(client, owner, PersistenceFixture.addMember,
                mapOf("groupId" to groupId, "userId" to member.user.id))
            response["errors"].isArray shouldBe true
            val data = fixture.query(client, owner,
                """query(${'$'}id: ID!) { group(id: ${'$'}id) { members { userId } } }""",
                mapOf("id" to groupId))
            data["group"]["members"].toList() shouldHaveSize 2
        }
    }

    test("a member can remove themselves and loses access") {
        fixture.withApp {
            val groupId = fixture.createGroup(client, owner)["id"].asText()
            fixture.addMember(client, owner, groupId, member.user.id)
            fixture.removeMember(client, member, groupId, member.user.id) shouldBe true
            val data = fixture.query(client, member,
                """query(${'$'}id: ID!) { group(id: ${'$'}id) { name } }""", mapOf("id" to groupId))
            data["group"].isNull shouldBe true
        }
    }

    test("unauthorized removal does not report success or delete the membership") {
        fixture.withApp {
            val groupId = fixture.createGroup(client, owner)["id"].asText()
            fixture.addMember(client, owner, groupId, member.user.id)
            fixture.removeMember(client, outsider, groupId, member.user.id) shouldBe false
            fixture.removeMember(client, owner, groupId, member.user.id) shouldBe true
            fixture.removeMember(client, owner, groupId, member.user.id) shouldBe false
        }
    }

    test("user search is served through the GraphQL SQL-function wrapper") {
        fixture.withApp {
            val data = fixture.query(client, member,
                """query(${'$'}query: String!) { searchUsers(query: ${'$'}query) { id email } }""",
                mapOf("query" to outsider.user.email))
            data["searchUsers"].map { it["id"].asText() } shouldBe listOf(outsider.user.id)
        }
    }

    test("groups includes records beyond pg_graphql's default page size") {
        fixture.withApp {
            val batch = (1..35).joinToString(" ") {
                "g$it: createGroup(input: {name: \"Page $it\"}) { id }"
            }
            val created = fixture.query(client, owner, "mutation { $batch }").map { it["id"].asText() }
            val listed = fixture.query(client, owner, "{ groups { id } }")["groups"].map { it["id"].asText() }
            listed.toSet().containsAll(created) shouldBe true
        }
    }

    test("admin user listing uses the JSON function and rejects non-admins") {
        fixture.withApp {
            val users = fixture.query(client, admin, "{ users { id email } }")["users"]
            users.map { it["id"].asText() } shouldContain member.user.id
            fixture.response(client, member, "{ users { id } }")["errors"].isArray shouldBe true
        }
    }

    test("members includes records beyond pg_graphql's default page size") {
        fixture.withApp {
            val groupId = fixture.createGroup(client, owner)["id"].asText()
            val invitees = (1..31).map { fixture.user() }
            invitees.forEach { fixture.addMember(client, owner, groupId, it.user.id) }
            val data = fixture.query(client, owner,
                """query { group(id: "$groupId") { members { userId } } }""")
            data["group"]["members"].map { it["userId"].asText() }.toSet() shouldBe
                (invitees.map { it.user.id } + owner.user.id).toSet()
        }
    }

    test("admin status changes are written through pg_graphql") {
        fixture.withApp {
            val target = fixture.user()
            fixture.query(client, admin,
                """mutation { setUserAdmin(input: {userId: "${target.user.id}", isAdmin: true}) }"""
            )["setUserAdmin"].asBoolean() shouldBe true
            val users = fixture.query(client, admin, "{ users { id isAdmin } }")["users"]
            users.single { it["id"].asText() == target.user.id }["isAdmin"].asBoolean() shouldBe true
        }
    }

    test("admin user deletion is written through pg_graphql") {
        fixture.withApp {
            val target = fixture.user()
            fixture.query(client, admin,
                """mutation { deleteUser(input: {userId: "${target.user.id}"}) }"""
            )["deleteUser"].asBoolean() shouldBe true
            val users = fixture.query(client, admin, "{ users { id } }")["users"]
            users.none { it["id"].asText() == target.user.id } shouldBe true
        }
    }
})

internal class PersistenceFixture(extraModule: org.koin.core.module.Module? = null) {
    private val url = System.getenv("SUPABASE_URL") ?: "http://127.0.0.1:54321"
    private val key = requireNotNull(System.getenv("SUPABASE_ANON_KEY"))
    private val http = HttpClient(CIO)
    private val auth = SupabaseService(url, key, http)
    private val mapper = jacksonObjectMapper()
    private val createdUsers = mutableListOf<String>()
    private val injector = DelegatingTenantCodeInjector()
    private val application = koinApplication {
        modules(appModule(url, key))
        extraModule?.let { modules(it) }
    }
    private val koin = application.koin.also { injector.delegate = KoinTenantCodeInjector(it) }
    private val viaduct = ViaductBuilder()
        .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(injector))
        .withScopedSchemas(listOf(
            SchemaScopeInfo.Scoped("public", setOf("public")),
            SchemaScopeInfo.Scoped("default", setOf("default", "public")),
            SchemaScopeInfo.Scoped("admin", setOf("admin", "default", "public")),
        ))
        .withCheckerExecutorFactoryCreator { GroupMembershipCheckerExecutorFactory(koin.get<GroupService>()) }
        .build()

    suspend fun user(): AuthSessionResponse =
        auth.signUp("persistence-${UUID.randomUUID()}@example.com", "TestPassword123!")
            .also { createdUsers.add(it.user.id) }

    suspend fun admin(): AuthSessionResponse {
        val user = user()
        val serviceKey = requireNotNull(System.getenv("SUPABASE_SERVICE_ROLE_KEY"))
        val response = http.put("$url/auth/v1/admin/users/${user.user.id}") {
            header("apikey", serviceKey)
            bearerAuth(serviceKey)
            contentType(ContentType.Application.Json)
            setBody("""{"app_metadata":{"is_admin":true}}""")
        }
        check(response.status == HttpStatusCode.OK) { "Could not prepare admin fixture: ${response.status}" }
        return auth.signIn(requireNotNull(user.user.email), "TestPassword123!")
    }

    fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { configureApplication(url, key, true, viaduct, injector, koin) }
        block()
    }

    suspend fun response(client: HttpClient, user: AuthSessionResponse, query: String, variables: Map<String, Any?> = emptyMap()): JsonNode {
        val response = client.post("/graphql") {
            contentType(ContentType.Application.Json)
            bearerAuth(user.accessToken)
            setBody(mapper.writeValueAsString(mapOf("query" to query, "variables" to variables)))
        }
        response.status shouldBe HttpStatusCode.OK
        return mapper.readTree(response.bodyAsText())
    }

    suspend fun query(client: HttpClient, user: AuthSessionResponse, query: String, variables: Map<String, Any?> = emptyMap()): JsonNode {
        val result = response(client, user, query, variables)
        check(!result.has("errors")) { result.toString() }
        return result["data"]
    }

    suspend fun createGroup(client: HttpClient, user: AuthSessionResponse): JsonNode = query(client, user,
        """mutation {
          createGroup(input: {name: "Guest community", description: "A community"}) {
            id name description ownerId createdAt updatedAt members { id groupId userId joinedAt }
          }
        }""")["createGroup"]

    suspend fun addMember(client: HttpClient, user: AuthSessionResponse, groupId: String, userId: String): JsonNode =
        query(client, user, addMember, mapOf("groupId" to groupId, "userId" to userId))["addGroupMember"]

    suspend fun removeMember(client: HttpClient, user: AuthSessionResponse, groupId: String, userId: String): Boolean =
        query(client, user,
            """mutation(${'$'}groupId: ID!, ${'$'}userId: String!) {
              removeGroupMember(input: {groupId: ${'$'}groupId, userId: ${'$'}userId})
            }""", mapOf("groupId" to groupId, "userId" to userId))["removeGroupMember"].asBoolean()

    suspend fun close() {
        val serviceKey = requireNotNull(System.getenv("SUPABASE_SERVICE_ROLE_KEY"))
        for (id in createdUsers) {
            val response = http.delete("$url/auth/v1/admin/users/$id") {
                header("apikey", serviceKey)
                bearerAuth(serviceKey)
            }
            check(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NotFound) {
                "Could not remove test user: ${response.status}"
            }
        }
        koin.get<HttpClient>().close()
        application.close()
        http.close()
    }

    companion object {
        val addMember = """mutation(${'$'}groupId: ID!, ${'$'}userId: String!) {
          addGroupMember(input: {groupId: ${'$'}groupId, userId: ${'$'}userId}) { id groupId userId }
        }"""
    }
}
