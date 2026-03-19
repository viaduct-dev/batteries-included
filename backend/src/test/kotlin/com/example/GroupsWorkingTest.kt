package com.example

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Simple test to verify groups functionality works end-to-end
 */
class GroupsWorkingTest : FunSpec({
    val supabaseUrl = System.getenv("SUPABASE_URL") ?: "http://127.0.0.1:54321"
    val supabaseAnonKey = System.getenv("SUPABASE_ANON_KEY")
        ?: "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH"
    val supabaseServiceRoleKey = System.getenv("SUPABASE_SERVICE_ROLE_KEY")

    test("Groups should work: create, query, and list").config(timeout = 30.seconds) {
        // 1. Sign in as a user
        val client = createSupabaseClient(supabaseUrl, supabaseAnonKey) {
            install(Auth)
        }

        val testEmail = "groups-test-${System.currentTimeMillis()}@example.com"

        client.auth.signUpWith(Email) {
            email = testEmail
            password = "TestPassword123!"
        }

        client.auth.signInWith(Email) {
            email = testEmail
            password = "TestPassword123!"
        }

        val token = client.auth.currentAccessTokenOrNull()
        token shouldNotBe null
        println("✓ User signed in, got token")

        // 2. Create a group via GraphQL
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000
            }
        }
        val supabaseService = SupabaseService(supabaseUrl, supabaseAnonKey, httpClient)
        val authClient = supabaseService.createAuthenticatedClient(token!!, httpClient)

        val groupName = "Test Group ${System.currentTimeMillis()}"
        val userId = client.auth.currentUserOrNull()?.id
        userId shouldNotBe null

        val createdGroup = authClient.createCheckboxGroup(
            name = groupName,
            description = "Test description",
            ownerId = userId!!
        )

        println("✓ Group created: ${createdGroup.name}")
        createdGroup.name shouldBe groupName

        // 3. Query groups and verify the new group appears
        val groups = authClient.getCheckboxGroups()
        groups.shouldNotBeEmpty()
        println("✓ Found ${groups.size} group(s)")

        val foundGroup = groups.find { it.name == groupName }
        foundGroup shouldNotBe null
        foundGroup!!.owner_id shouldBe userId
        println("✓ Group appears in query results")

        // 4. Query the specific group by ID
        val queriedGroup = authClient.getCheckboxGroupById(createdGroup.id)
        queriedGroup shouldNotBe null
        queriedGroup!!.name shouldBe groupName
        println("✓ Can query group by ID")

        println("\n✅ All groups tests passed!")
    }
})
