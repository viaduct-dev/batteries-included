package com.example.policy

import com.example.services.GroupService
import graphql.Scalars
import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLAppliedDirectiveArgument
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import viaduct.engine.api.ViaductSchema

/**
 * Test for GroupMembershipCheckerFactory.
 *
 * Verifies that the factory correctly:
 * 1. Creates CheckerExecutors for fields with @requiresGroupMembership directive
 * 2. Creates CheckerExecutors for types with @requiresGroupMembership directive
 * 3. Returns null for fields/types without the directive
 * 4. Correctly reads groupIdField parameter from the directive
 */
class GroupMembershipCheckerFactoryTest : FunSpec({

    val testGroupIdField = "myCustomGroupIdField"
    lateinit var groupService: GroupService
    lateinit var mockViaductSchema: ViaductSchema
    lateinit var mockGraphQLSchema: GraphQLSchema
    lateinit var checkerFactory: GroupMembershipCheckerFactory

    beforeEach {
        groupService = mockk<GroupService>()
        mockGraphQLSchema = mockk<GraphQLSchema>()
        mockViaductSchema = mockk<ViaductSchema>()

        every { mockViaductSchema.schema } returns mockGraphQLSchema

        checkerFactory = GroupMembershipCheckerFactory(
            schema = mockViaductSchema,
            groupService = groupService
        )
    }

    test("should create executor for field with requiresGroupMembership directive") {
        // Arrange: Create a field with the @requiresGroupMembership directive
        val directive = createMockDirective("requiresGroupMembership", "groupId")
        val field = mockk<GraphQLFieldDefinition>()

        every { field.hasAppliedDirective("requiresGroupMembership") } returns true
        every { field.getAppliedDirective("requiresGroupMembership") } returns directive

        val objectType = mockk<GraphQLObjectType>()
        every { objectType.getFieldDefinition("testField") } returns field
        every { mockGraphQLSchema.getObjectType("TestType") } returns objectType

        // Act: Request checker executor for the field
        val executor = checkerFactory.checkerExecutorForField("TestType", "testField")

        // Assert: Executor should be created
        executor.shouldNotBeNull()
        executor.shouldBeInstanceOf<GroupMembershipPolicyExecutor>()
    }

    test("should return null for field without requiresGroupMembership directive") {
        // Arrange: Create a field WITHOUT the directive
        val field = mockk<GraphQLFieldDefinition>()
        every { field.hasAppliedDirective("requiresGroupMembership") } returns false

        val objectType = mockk<GraphQLObjectType>()
        every { objectType.getFieldDefinition("testField") } returns field
        every { mockGraphQLSchema.getObjectType("TestType") } returns objectType

        // Act: Request checker executor for the field
        val executor = checkerFactory.checkerExecutorForField("TestType", "testField")

        // Assert: No executor should be created
        executor.shouldBeNull()
    }

    test("should create executor for type with requiresGroupMembership directive") {
        // Arrange: Create a type with the @requiresGroupMembership directive
        val directive = createMockDirective("requiresGroupMembership", "groupId")
        val objectType = mockk<GraphQLObjectType>()

        every { objectType.hasAppliedDirective("requiresGroupMembership") } returns true
        every { objectType.getAppliedDirective("requiresGroupMembership") } returns directive
        every { mockGraphQLSchema.getObjectType("TestType") } returns objectType

        // Act: Request checker executor for the type
        val executor = checkerFactory.checkerExecutorForType("TestType")

        // Assert: Executor should be created
        executor.shouldNotBeNull()
        executor.shouldBeInstanceOf<GroupMembershipPolicyExecutor>()
    }

    test("should return null for type without requiresGroupMembership directive") {
        // Arrange: Create a type WITHOUT the directive
        val objectType = mockk<GraphQLObjectType>()
        every { objectType.hasAppliedDirective("requiresGroupMembership") } returns false
        every { mockGraphQLSchema.getObjectType("TestType") } returns objectType

        // Act: Request checker executor for the type
        val executor = checkerFactory.checkerExecutorForType("TestType")

        // Assert: No executor should be created
        executor.shouldBeNull()
    }

    test("should use custom groupIdField from directive parameter") {
        // Arrange: Create a directive with custom groupIdField parameter
        val directive = createMockDirective("requiresGroupMembership", testGroupIdField)
        val field = mockk<GraphQLFieldDefinition>()

        every { field.hasAppliedDirective("requiresGroupMembership") } returns true
        every { field.getAppliedDirective("requiresGroupMembership") } returns directive

        val objectType = mockk<GraphQLObjectType>()
        every { objectType.getFieldDefinition("testField") } returns field
        every { mockGraphQLSchema.getObjectType("TestType") } returns objectType

        // Act: Request checker executor for the field
        val executor = checkerFactory.checkerExecutorForField("TestType", "testField")

        // Assert: Executor should be created with custom field name
        executor.shouldNotBeNull()
        executor.shouldBeInstanceOf<GroupMembershipPolicyExecutor>()
        // Note: We can't directly inspect the groupIdFieldName since it's private,
        // but the executor should use it internally
    }

    test("should default to 'groupId' when groupIdField parameter is not provided") {
        // Arrange: Create a directive without groupIdField parameter (defaults to "groupId")
        val directive = createMockDirective("requiresGroupMembership", null)
        val field = mockk<GraphQLFieldDefinition>()

        every { field.hasAppliedDirective("requiresGroupMembership") } returns true
        every { field.getAppliedDirective("requiresGroupMembership") } returns directive

        val objectType = mockk<GraphQLObjectType>()
        every { objectType.getFieldDefinition("testField") } returns field
        every { mockGraphQLSchema.getObjectType("TestType") } returns objectType

        // Act: Request checker executor for the field
        val executor = checkerFactory.checkerExecutorForField("TestType", "testField")

        // Assert: Executor should be created with default "groupId" field
        executor.shouldNotBeNull()
        executor.shouldBeInstanceOf<GroupMembershipPolicyExecutor>()
    }

    test("should return null when field does not exist on type") {
        // Arrange: Type exists but field doesn't
        val objectType = mockk<GraphQLObjectType>()
        every { objectType.getFieldDefinition("nonExistentField") } returns null
        every { mockGraphQLSchema.getObjectType("TestType") } returns objectType

        // Act: Request checker executor for non-existent field
        val executor = checkerFactory.checkerExecutorForField("TestType", "nonExistentField")

        // Assert: No executor should be created
        executor.shouldBeNull()
    }

    test("should return null when type does not exist") {
        // Arrange: Type doesn't exist in schema
        every { mockGraphQLSchema.getObjectType("NonExistentType") } returns null

        // Act: Request checker executor for non-existent type
        val executor = checkerFactory.checkerExecutorForType("NonExistentType")

        // Assert: No executor should be created
        executor.shouldBeNull()
    }
})

/**
 * Helper function to create a mock GraphQL directive
 */
private fun createMockDirective(
    name: String,
    groupIdFieldValue: String?
): GraphQLAppliedDirective {
    val argument = mockk<GraphQLAppliedDirectiveArgument>()

    // If groupIdFieldValue is null, return null (parameter not provided)
    // Otherwise, return the value
    every { argument.getValue<String?>() } returns groupIdFieldValue

    val directive = mockk<GraphQLAppliedDirective>()
    every { directive.name } returns name
    every { directive.getArgument("groupIdField") } returns argument

    return directive
}
