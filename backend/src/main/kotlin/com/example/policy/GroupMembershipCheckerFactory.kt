package com.example.policy

import com.example.services.GroupService
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.ViaductSchema

/**
 * Factory that creates CheckerExecutor instances for group membership policy checks.
 * This reads the @requiresGroupMembership directive from the GraphQL schema
 * and creates appropriate policy executors.
 */
class GroupMembershipCheckerFactory(
    private val schema: ViaductSchema,
    private val groupService: GroupService
) : CheckerExecutorFactory {

    private val graphQLSchema = schema.schema

    /**
     * Create a policy executor for a specific field.
     * Returns null if the field doesn't have the @requiresGroupMembership directive.
     */
    override fun checkerExecutorForField(
        typeName: String,
        fieldName: String
    ): CheckerExecutor? {
        val field = graphQLSchema.getObjectType(typeName)
            ?.getFieldDefinition(fieldName)
            ?: return null

        // Check if the field has the @requiresGroupMembership directive
        if (!field.hasAppliedDirective("requiresGroupMembership")) {
            return null
        }

        val directive = field.getAppliedDirective("requiresGroupMembership")
        return createExecutorFromDirective(directive)
    }

    /**
     * Create a policy executor for a specific type.
     * Returns null if the type doesn't have the @requiresGroupMembership directive.
     */
    override fun checkerExecutorForType(
        typeName: String
    ): CheckerExecutor? {
        val type = graphQLSchema.getObjectType(typeName)
            ?: return null

        // Check if the type has the @requiresGroupMembership directive
        if (!type.hasAppliedDirective("requiresGroupMembership")) {
            return null
        }

        val directive = type.getAppliedDirective("requiresGroupMembership")
        return createExecutorFromDirective(directive)
    }

    /**
     * Create a GroupMembershipPolicyExecutor from a directive.
     * Reads the groupIdField argument from the directive.
     */
    private fun createExecutorFromDirective(
        directive: graphql.schema.GraphQLAppliedDirective
    ): CheckerExecutor {
        // Read the groupIdField argument, defaulting to "groupId"
        val groupIdField = directive.getArgument("groupIdField")
            ?.getValue() as? String ?: "groupId"

        return GroupMembershipPolicyExecutor(
            groupIdFieldName = groupIdField,
            groupService = groupService
        )
    }
}
