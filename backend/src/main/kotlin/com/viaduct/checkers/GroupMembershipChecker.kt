package com.viaduct.checkers

import com.example.config.RequestContext
import com.example.services.GroupService
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.CheckerExecutorFactory
// NOTE: GlobalIDCodec (viaduct.service.api.spi.GlobalIDCodec) is a transitional API that will be
// replaced in a future Viaduct release. This file will need to be updated when that happens.
import viaduct.service.api.spi.GlobalIDCodec

private const val GROUP_ID_KEY = "groupId"

// NOTE: The CheckerExecutorFactory / CheckerExecutor SPI is a transitional mechanism. A
// first-class Viaduct API for type-level access control is forthcoming and will replace this file.
/**
 * Enforces group membership: the requesting user must be a member of the Group being resolved.
 *
 * Uses a RequiredSelectionSet to read the resolved `id` field from the Group object, then
 * queries Supabase to verify membership. This replaces what @requiresGroupMembership expressed
 * as a schema directive in Viaduct versions prior to 0.28.0.
 */
class GroupMembershipCheckerExecutor(
    private val groupService: GroupService,
    groupTypeName: String,
) : CheckerExecutor {

    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = mapOf(
        GROUP_ID_KEY to RequiredSelectionSet(
            selections = SelectionsParser.parse(groupTypeName, "id"),
            variablesResolvers = emptyList(),
            forChecker = true,
        )
    )

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType,
    ): CheckerResult {
        val requestContext = context.requestContext as? RequestContext
            ?: return GroupMembershipError("No request context available")

        val encodedId = objectDataMap[GROUP_ID_KEY]?.fetchOrNull("id") as? String
            ?: return GroupMembershipError("Could not determine group ID for access check")

        val groupId = try {
            context.globalIDCodec.deserialize(encodedId).second
        } catch (e: IllegalArgumentException) {
            return GroupMembershipError("Malformed group ID: ${e.message}")
        }

        val userId = requestContext.graphQLContext.userId

        return try {
            val isMember = groupService.isUserMemberOfGroup(userId, groupId, requestContext)
            if (isMember) CheckerResult.Success
            else GroupMembershipError("User $userId is not a member of group $groupId")
        } catch (e: Exception) {
            GroupMembershipError("Group membership check failed: ${e.message}")
        }
    }
}

class GroupMembershipError(message: String) : CheckerResult.Error {
    override val error: Exception = Exception(message)
    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

/**
 * Factory that registers [GroupMembershipCheckerExecutor] for the Group type.
 *
 * In Viaduct 0.28+ custom schema directives are not allowed, so the set of protected
 * types is declared here in code rather than via a @requiresGroupMembership directive.
 */
class GroupMembershipCheckerExecutorFactory(
    private val groupService: GroupService,
) : CheckerExecutorFactory {

    override fun checkerExecutorForField(
        schema: ViaductSchema,
        typeName: String,
        fieldName: String,
    ): CheckerExecutor? = null

    override fun checkerExecutorForType(
        schema: ViaductSchema,
        typeName: String,
    ): CheckerExecutor? {
        if (typeName != "Group") return null
        return GroupMembershipCheckerExecutor(groupService, typeName)
    }
}
