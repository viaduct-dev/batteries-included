package com.example.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Type-safe representation of a GraphQL request.
 * Replaces unsafe Map-based deserialization with proper type checking.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphQLRequest(
    val query: String = "",
    val variables: Map<String, Any?> = emptyMap(),
    val operationName: String? = null
)
