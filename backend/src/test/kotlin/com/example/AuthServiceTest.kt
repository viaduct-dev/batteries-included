package com.example

import com.example.services.AuthService
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

class AuthServiceTest {
    @Test
    fun `identity comes from Supabase rather than unverified token claims`() = withAuth(
        """{"id":"verified-user","aud":"authenticated","app_metadata":{"is_admin":true}}""",
    ) { auth ->
        auth.createRequestContext("untrusted-token") shouldBe
            GraphQLRequestContext("verified-user", "untrusted-token", true)
    }

    @Test
    fun `user-controlled metadata cannot grant administrator access`() = withAuth(
        """{"id":"user","aud":"authenticated","user_metadata":{"is_admin":true}}""",
    ) { auth ->
        auth.createRequestContext("token").isAdmin shouldBe false
    }

    @Test
    fun `missing or invalid administrator flags fail closed`() {
        for (metadata in listOf(
            "",
            ""","app_metadata":{"is_admin":false}""",
            ""","app_metadata":{"is_admin":"true"}""",
            ""","app_metadata":{"is_admin":"invalid"}""",
        )) {
            withAuth("""{"id":"user","aud":"authenticated"$metadata}""") { auth ->
                auth.createRequestContext("token").isAdmin shouldBe false
            }
        }
    }

    @Test
    fun `failed verification never creates a request context`() = withAuth(
        """{"msg":"Invalid token"}""", HttpStatusCode.Unauthorized,
    ) { auth ->
        assertThrows(Exception::class.java) {
            runBlocking { auth.createRequestContext("forged-token") }
        }
    }

    private fun withAuth(
        response: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (AuthService) -> Unit,
    ) = testApplication {
        externalServices {
            hosts("http://supabase.test") {
                routing {
                    get("/auth/v1/user") { call.respondText(response, ContentType.Application.Json, status) }
                }
            }
        }
        block(AuthService(SupabaseService("http://supabase.test", "unused", client)))
    }
}
