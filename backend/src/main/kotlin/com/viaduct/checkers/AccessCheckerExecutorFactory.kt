package com.viaduct.checkers

import com.example.config.RequestContext
import com.example.services.GroupService
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.api.spi.CheckerExecutorFactory

/** Runtime authorization complements schema visibility and Supabase policies. */
class AccessCheckerExecutorFactory(groupService: GroupService) :
    CheckerExecutorFactory by GroupMembershipCheckerExecutorFactory(groupService) {
    override fun checkerExecutorForField(
        schema: EngineSchema,
        typeName: String,
        fieldName: String,
    ): CheckerExecutor? = when (typeName to fieldName) {
        "Query" to "users", "Mutation" to "setUserAdmin", "Mutation" to "deleteUser" -> AdminChecker
        else -> null
    }
}

private object AdminChecker : CheckerExecutor {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData.Sync>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType,
    ): CheckerResult =
        if ((context.requestContext as? RequestContext)?.graphQLContext?.isAdmin == true) {
            CheckerResult.Success
        } else {
            AdminAccessDenied
        }
}

private object AdminAccessDenied : CheckerResult.Error {
    override val error: Exception get() = IllegalAccessException("Administrator access required")
    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
