package com.example.policy

import com.example.AuthenticatedSupabaseClient
import com.example.GraphQLRequestContext
import com.example.SupabaseService
import com.example.config.appModule
import com.example.services.GroupService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import viaduct.api.globalid.GlobalID
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData

/**
 * Fake SupabaseService implementation for testing.
 * Works around Java 21 / Byte Buddy compatibility issues with MockK.
 * Even though SupabaseService is 'open', MockK's inline instrumentation
 * fails with Java 21, so we use a fake implementation instead.
 */
class FakeSupabaseService(httpClient: HttpClient = HttpClient(CIO))
    : SupabaseService("fake-url", "fake-key", httpClient) {
    override fun getAuthenticatedClient(requestContext: Any?): AuthenticatedSupabaseClient {
        // Return a relaxed mock client for testing
        return mockk<AuthenticatedSupabaseClient>(relaxed = true)
    }
}

/**
 * Fake GroupService implementation for testing.
 * Avoids Java 21 / Byte Buddy compatibility issues with MockK.
 */
class FakeGroupService(supabaseService: SupabaseService) : GroupService(supabaseService) {
    private val memberships = mutableMapOf<Pair<String, String>, Boolean>()

    fun setMembership(userId: String, groupId: String, isMember: Boolean) {
        memberships[Pair(userId, groupId)] = isMember
    }

    override suspend fun isUserMemberOfGroup(userId: String, groupId: String, requestContext: com.example.config.RequestContext): Boolean {
        return memberships[Pair(userId, groupId)] ?: false
    }
}

/**
 * Integration test for GroupMembershipPolicyExecutor.
 *
 * This test demonstrates Viaduct's per-row policy check feature by testing:
 * 1. Access granted when user is a member of the group
 * 2. Access denied when user is not a member of the group
 * 3. Access granted when group ID is null (backward compatibility)
 * 4. Proper error messages on access denial
 */
class GroupMembershipPolicyCheckTest : FunSpec({

    val testUserId = "user-123"
    val testGroupId = "group-456"
    val anotherGroupId = "group-789"
    val supabaseUrl = "http://localhost:54321"
    val supabaseKey = "test-key"

    lateinit var groupService: FakeGroupService
    lateinit var policyExecutor: GroupMembershipPolicyExecutor
    lateinit var mockContext: EngineExecutionContext
    lateinit var requestContextWrapper: com.example.config.RequestContext

    beforeEach {
        // Use FakeSupabaseService instead of MockK due to Byte Buddy / Java 21 issues
        val fakeSupabaseService = FakeSupabaseService()
        groupService = FakeGroupService(fakeSupabaseService)

        // User is a member of testGroupId but not anotherGroupId
        groupService.setMembership(testUserId, testGroupId, true)
        groupService.setMembership(testUserId, anotherGroupId, false)

        // Create policy executor
        policyExecutor = GroupMembershipPolicyExecutor(
            groupIdFieldName = "groupId",
            groupService = groupService
        )

        // Setup Koin if not already started
        try {
            startKoin {
                modules(appModule(supabaseUrl, supabaseKey))
            }
        } catch (e: Exception) {
            // Already started
        }

        // Create the GraphQLRequestContext
        val graphQLContext = GraphQLRequestContext(
            userId = testUserId,
            accessToken = "test-token",
            isAdmin = false
        )

        // Create a mock authenticated client
        val mockClient = fakeSupabaseService.getAuthenticatedClient(null)

        // Create RequestContext with both dependencies
        val mockScope = mockk<org.koin.core.scope.Scope>(relaxed = true)
        requestContextWrapper = com.example.config.RequestContext(
            graphQLContext = graphQLContext,
            authenticatedClient = mockClient,
            koinScope = mockScope
        )

        // Mock execution context with the wrapped context
        mockContext = mockk<EngineExecutionContext>(relaxed = true)
        every { mockContext.requestContext } returns requestContextWrapper
    }

    afterEach {
        // No cleanup needed - RequestContext is now a simple data class
        // Koin automatically handles scope lifecycle
    }

    afterSpec {
        stopKoin()
    }

    test("should grant access when user is a member of the group") {
        // Arrange: Create object data with group ID that user is a member of
        val objectData = mockk<EngineObjectData>()
        coEvery { objectData.fetch("groupId") } coAnswers { testGroupId }

        val objectDataMap = mapOf("" to objectData)
        val arguments = emptyMap<String, Any?>()

        // Act: Execute policy check
        val result = policyExecutor.execute(
            arguments = arguments,
            objectDataMap = objectDataMap,
            context = mockContext
        )

        // Debug: Print error if not success
        if (result !is CheckerResult.Success) {
            println("TEST FAILED: Expected Success but got ${result::class.simpleName}")
            if (result is GroupMembershipErrorResult) {
                println("Error message: ${result.error.message}")
                result.error.printStackTrace()
            }
        }

        // Assert: Access should be granted
        result shouldBe CheckerResult.Success
    }

    test("should deny access when user is not a member of the group") {
        // Arrange: Create object data with group ID that user is NOT a member of
        val objectData = mockk<EngineObjectData>()
        coEvery { objectData.fetch("groupId") } coAnswers { anotherGroupId }

        val objectDataMap = mapOf("" to objectData)
        val arguments = emptyMap<String, Any?>()

        // Act: Execute policy check
        val result = policyExecutor.execute(
            arguments = arguments,
            objectDataMap = objectDataMap,
            context = mockContext
        )

        // Assert: Access should be denied with error
        result shouldNotBe CheckerResult.Success
        result as GroupMembershipErrorResult
        result.error.message shouldContain "not a member of this group"
    }

    test("should grant access when group ID is null (backward compatibility)") {
        // Arrange: Create object data with null group ID (legacy personal items)
        val objectData = mockk<EngineObjectData>()
        coEvery { objectData.fetch("groupId") } coAnswers { null }

        val objectDataMap = mapOf("" to objectData)
        val arguments = emptyMap<String, Any?>()

        // Act: Execute policy check
        val result = policyExecutor.execute(
            arguments = arguments,
            objectDataMap = objectDataMap,
            context = mockContext
        )

        // Assert: Access should be granted for null group ID
        result shouldBe CheckerResult.Success
    }

    test("should check membership from arguments when object data is null") {
        // Arrange: No object data, but group ID provided as GlobalID argument
        val objectDataMap = emptyMap<String, EngineObjectData>()
        val mockGlobalId = mockk<GlobalID<*>>()
        every { mockGlobalId.internalID } returns testGroupId
        val arguments = mapOf("groupId" to mockGlobalId)

        // Act: Execute policy check
        val result = policyExecutor.execute(
            arguments = arguments,
            objectDataMap = objectDataMap,
            context = mockContext
        )

        // Assert: Access should be granted (user is member of testGroupId)
        result shouldBe CheckerResult.Success
    }

    test("should deny access from arguments when user is not a member") {
        // Arrange: No object data, but group ID provided as GlobalID argument for non-member group
        val objectDataMap = emptyMap<String, EngineObjectData>()
        val mockGlobalId = mockk<GlobalID<*>>()
        every { mockGlobalId.internalID } returns anotherGroupId
        val arguments = mapOf("groupId" to mockGlobalId)

        // Act: Execute policy check
        val result = policyExecutor.execute(
            arguments = arguments,
            objectDataMap = objectDataMap,
            context = mockContext
        )

        // Assert: Access should be denied
        result shouldNotBe CheckerResult.Success
        result as GroupMembershipErrorResult
        result.error.message shouldContain "not a member of this group"
    }

    test("should grant access when no group ID is provided anywhere") {
        // Arrange: No object data and no group ID argument
        val objectDataMap = emptyMap<String, EngineObjectData>()
        val arguments = emptyMap<String, Any?>()

        // Act: Execute policy check
        val result = policyExecutor.execute(
            arguments = arguments,
            objectDataMap = objectDataMap,
            context = mockContext
        )

        // Assert: Access should be granted (no group to check)
        result shouldBe CheckerResult.Success
    }

    test("error result should indicate it's for the resolver") {
        // Arrange: Create an error result
        val errorResult = GroupMembershipErrorResult(
            RuntimeException("Test error")
        )

        val mockResultContext = mockk<viaduct.engine.api.CheckerResultContext>()

        // Assert: Error should be returned to resolver
        errorResult.isErrorForResolver(mockResultContext) shouldBe true
    }

    test("error result combine should prefer field error") {
        // Arrange: Create two error results
        val typeError = GroupMembershipErrorResult(
            RuntimeException("Type error")
        )
        val fieldError = GroupMembershipErrorResult(
            RuntimeException("Field error")
        )

        // Act: Combine errors
        val combinedResult = typeError.combine(fieldError)

        // Assert: Field error should take precedence
        combinedResult shouldBe fieldError
        (combinedResult as GroupMembershipErrorResult).error.message shouldBe "Field error"
    }
})
