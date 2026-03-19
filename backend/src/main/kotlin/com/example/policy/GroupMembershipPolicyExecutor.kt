package com.example.policy

import com.example.GraphQLRequestContext
import com.example.config.RequestContext
import com.example.services.GroupService
import viaduct.api.globalid.GlobalID
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import java.util.Base64

/**
 * Policy executor that checks if a user is a member of a checkbox group.
 * This demonstrates Viaduct's per-row policy check capabilities.
 *
 * The executor reads the group ID from the object data and verifies that
 * the authenticated user is a member of that group.
 */
class GroupMembershipPolicyExecutor(
    private val groupIdFieldName: String,
    private val groupService: GroupService
) : CheckerExecutor {

    /**
     * Specify which fields must be selected for the policy check to work.
     * For simplicity, we're not requiring selection sets in this implementation.
     * The group ID will be extracted from object data if available.
     */
    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()

    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        // Extract the RequestContext from the engine context
        val requestContext = context.requestContext as? RequestContext
            ?: return GroupMembershipErrorResult(
                RuntimeException("Authentication required: request context not found")
            )

        // Get the user ID from the GraphQL context
        val userId = requestContext.graphQLContext.userId

        // Get the object data (empty string key means current object)
        val objectData = objectDataMap[""]

        // If there's no object data, we're checking a field-level policy
        // In this case, check if the groupId is provided as an argument
        if (objectData == null) {
            val groupIdArg = arguments[groupIdFieldName]
            if (groupIdArg != null) {
                // Policy executors run at the engine level, BEFORE argument deserialization.
                // In production, they receive base64-encoded GlobalID strings from GraphQL queries.
                // However, they may also receive GlobalID objects in unit tests or edge cases.
                //
                // Note: Manual Base64 decoding here is unavoidable. Viaduct's GlobalIDCodec is not accessible
                // at this layer because it's part of InternalContext, which policy executors cannot access.
                // This is an architectural constraint of Viaduct's execution pipeline.
                val internalGroupId = when (groupIdArg) {
                    is GlobalID<*> -> {
                        // GlobalID object (from unit tests or internal Viaduct scenarios)
                        groupIdArg.internalID
                    }
                    is String -> {
                        // Base64-encoded GlobalID string from GraphQL queries (normal case)
                        // Format: base64("TypeName:uuid")
                        try {
                            val decoded = String(Base64.getDecoder().decode(groupIdArg))

                            // Validate the GlobalID format (should be "TypeName:id")
                            val parts = decoded.split(":", limit = 2)
                            if (parts.size != 2) {
                                throw IllegalArgumentException(
                                    "Invalid GlobalID format for '$groupIdFieldName': expected 'TypeName:id' but got '$decoded'"
                                )
                            }

                            // Extract and return the UUID part after the type name
                            parts[1]
                        } catch (e: IllegalArgumentException) {
                            // Re-throw validation errors with context
                            throw e
                        } catch (e: IllegalStateException) {
                            // Base64 decoding failed - invalid format
                            throw IllegalArgumentException(
                                "Failed to decode GlobalID for '$groupIdFieldName': invalid base64 encoding",
                                e
                            )
                        } catch (e: Exception) {
                            // Other decoding errors
                            throw IllegalArgumentException(
                                "Failed to decode GlobalID for '$groupIdFieldName': ${e.message}",
                                e
                            )
                        }
                    }
                    else -> throw IllegalArgumentException(
                        "Expected GlobalID or String for argument '$groupIdFieldName' but got ${groupIdArg::class.java.name}"
                    )
                }
                return checkGroupMembership(userId, internalGroupId, context)
            }

            // Check if there's an input object with an 'id' field (for mutations like updateChecklistItem)
            // We need to fetch the item to get its groupId
            val inputArg = arguments["input"]
            if (inputArg != null) {
                // Try to extract the internal ID from the input object's GlobalID
                val internalItemId = try {
                    val idField = inputArg::class.java.getMethod("getId")
                    val idValue = idField.invoke(inputArg)

                    // Ensure it's a GlobalID type
                    if (idValue !is GlobalID<*>) {
                        throw IllegalArgumentException(
                            "Expected GlobalID for input.id but got ${idValue?.let { it::class.java.name } ?: "null"}"
                        )
                    }
                    idValue.internalID
                } catch (e: Exception) {
                    null
                }

                if (internalItemId != null) {
                    // Fetch the item to get its groupId
                    val item = try {
                        requestContext.authenticatedClient.getChecklistItemById(internalItemId)
                    } catch (e: Exception) {
                        return GroupMembershipErrorResult(
                            RuntimeException("Failed to fetch item for authorization check: ${e.message}", e)
                        )
                    }

                    if (item == null) {
                        return GroupMembershipErrorResult(
                            RuntimeException("Item not found")
                        )
                    }

                    // Check if the item belongs to a group and verify membership
                    val itemGroupId = item.group_id
                    if (itemGroupId != null) {
                        return checkGroupMembership(userId, itemGroupId, context)
                    }
                }
            }

            // No group ID available - allow access (this might be a query that returns all groups)
            return CheckerResult.Success
        }

        // Extract the group ID from the object data
        val groupId = try {
            objectData.fetch(groupIdFieldName) as? String
        } catch (e: Exception) {
            // Field not found or null - this might be a legacy item without a group
            null
        }

        // If group ID is null, allow access (backward compatibility for personal items)
        if (groupId == null) {
            return CheckerResult.Success
        }

        // Check if the user is a member of the group
        return checkGroupMembership(userId, groupId, context)
    }

    private suspend fun checkGroupMembership(userId: String, groupId: String, context: EngineExecutionContext): CheckerResult {
        val isMember = try {
            // Get the RequestContext from the engine context
            val requestContext = context.requestContext as? RequestContext
                ?: return GroupMembershipErrorResult(
                    RuntimeException("Authentication required: request context not found")
                )
            groupService.isUserMemberOfGroup(userId, groupId, requestContext)
        } catch (e: Exception) {
            return GroupMembershipErrorResult(
                RuntimeException("Failed to check group membership: ${e.message}", e)
            )
        }

        return if (isMember) {
            CheckerResult.Success
        } else {
            GroupMembershipErrorResult(
                RuntimeException("Access denied: You are not a member of this group")
            )
        }
    }
}

/**
 * Error result for group membership policy check failures.
 */
class GroupMembershipErrorResult(
    override val error: Exception
) : CheckerResult.Error {

    /**
     * Should this error be returned to the resolver?
     * Yes - we want the resolver to know about permission failures.
     */
    override fun isErrorForResolver(ctx: viaduct.engine.api.CheckerResultContext): Boolean = true

    /**
     * How to combine field and type errors when both fail.
     * Field error takes precedence.
     */
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error {
        return fieldResult
    }
}
