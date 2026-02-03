package com.viaduct.skill.eval

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Kotest spec that runs all skill evaluations as individual tests.
 *
 * Each evaluation is a separate test case that:
 * 1. Clones viaduct-batteries-included
 * 2. Runs Claude with the skill to implement a feature
 * 3. Verifies the code compiles
 * 4. Checks for expected patterns
 *
 * Run with: ./gradlew test
 * Run single eval: ./gradlew test --tests "*eval-01*"
 */
class SkillEvaluationSpec : FunSpec({

    val skillDir = File(System.getenv("SKILL_DIR") ?: "..").canonicalFile
    val runner = EvaluationRunner(skillDir)

    // Check prerequisites
    beforeSpec {
        // Verify claude CLI is available
        val claudeCheck = ProcessBuilder("which", "claude")
            .start()
            .waitFor()
        require(claudeCheck == 0) {
            "Claude CLI must be installed. Run: curl -fsSL https://claude.ai/install.sh | bash"
        }

        // Verify git is available
        val gitCheck = ProcessBuilder("which", "git")
            .start()
            .waitFor()
        require(gitCheck == 0) {
            "Git must be installed"
        }

        // Verify java is available
        val javaCheck = ProcessBuilder("which", "java")
            .start()
            .waitFor()
        require(javaCheck == 0) {
            "Java 17+ must be installed"
        }

        println("Skill directory: ${skillDir.absolutePath}")
        println("Evaluations: ${runner.loadEvaluations().size}")
    }

    // Load evaluations and create test cases
    val evaluations = runner.loadEvaluations()

    context("Viaduct Skill Evaluations") {
        withData(
            nameFn = { "${it.id}: ${it.name}" },
            evaluations
        ) { eval ->
            println("\n${"=".repeat(60)}")
            println("${eval.id}: ${eval.name}")
            println("=".repeat(60))
            println("Query: ${eval.query.take(80)}...")

            val result = runner.runEvaluation(eval)

            // Print result summary
            val status = if (result.passed) "✅ PASSED" else "❌ FAILED"
            println("\nResult: $status (${result.durationMs / 1000.0}s)")
            println("  Build: ${if (result.buildSuccess) "✅" else "❌"}")

            if (result.patternsFound.isNotEmpty()) {
                println("  Patterns found: ${result.patternsFound.size}")
            }
            if (result.patternsMissing.isNotEmpty()) {
                println("  Patterns missing:")
                result.patternsMissing.forEach { println("    - $it") }
            }
            if (result.error != null) {
                println("  Error: ${result.error}")
            }
            if (!result.buildSuccess) {
                println("  Build output (last 500 chars):")
                println("    ${result.buildOutput.takeLast(500).replace("\n", "\n    ")}")
            }

            // Assertions
            result.error shouldBe null
            result.buildSuccess.shouldBeTrue()
            result.patternsMissing.shouldBeEmpty()
            result.passed.shouldBeTrue()
        }
    }
})
