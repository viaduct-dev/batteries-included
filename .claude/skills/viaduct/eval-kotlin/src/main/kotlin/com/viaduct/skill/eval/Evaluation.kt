package com.viaduct.skill.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Evaluation(
    val id: String,
    val name: String,
    val skills: List<String>,
    val query: String,
    val files: List<String> = emptyList(),
    @SerialName("expected_behavior")
    val expectedBehavior: List<String>,
    @SerialName("verify_patterns")
    val verifyPatterns: List<String> = emptyList(),
    @SerialName("setup_query")
    val setupQuery: String? = null
)

data class EvaluationResult(
    val id: String,
    val name: String,
    val passed: Boolean,
    val buildSuccess: Boolean,
    val patternsFound: List<String>,
    val patternsMissing: List<String>,
    val claudeOutput: String,
    val buildOutput: String,
    val error: String? = null,
    val durationMs: Long
)
