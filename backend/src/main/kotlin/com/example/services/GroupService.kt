package com.example.services

import com.example.config.RequestContext

/** Application authorization used by the Viaduct group checker. */
open class GroupService {
    open suspend fun isUserMemberOfGroup(userId: String, groupId: String, requestContext: RequestContext): Boolean =
        requestContext.authenticatedClient.isGroupMember(groupId, userId)
}
