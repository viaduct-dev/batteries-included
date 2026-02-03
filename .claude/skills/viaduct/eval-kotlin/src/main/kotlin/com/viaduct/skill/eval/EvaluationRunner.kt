package com.viaduct.skill.eval

import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs skill evaluations against viaduct-batteries-included.
 *
 * Each evaluation:
 * 1. Clones the repository to a temp directory
 * 2. Checks out the baseline commit
 * 3. Copies the skill into .claude/skills/
 * 4. Runs Claude with the evaluation query
 * 5. Runs gradle build to verify compilation
 * 6. Checks for expected patterns in generated code
 */
class EvaluationRunner(
    private val skillDir: File,
    private val workspaceDir: File = File(skillDir, ".eval-workspace"),
    private val repoUrl: String = "git@github.com:viaduct-dev/viaduct-batteries-included.git",
    private val baselineCommit: String = "a20f9be"
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        workspaceDir.mkdirs()
    }

    /**
     * Load evaluations from the JSON file.
     */
    fun loadEvaluations(): List<Evaluation> {
        val evalFile = File(skillDir, "evaluations.json")
        require(evalFile.exists()) { "Evaluations file not found: ${evalFile.absolutePath}" }
        return json.decodeFromString(evalFile.readText())
    }

    /**
     * Run a single evaluation.
     */
    fun runEvaluation(eval: Evaluation): EvaluationResult {
        val startTime = System.currentTimeMillis()
        val workDir = File(workspaceDir, eval.id)

        var buildSuccess = false
        var claudeOutput = ""
        var buildOutput = ""
        val patternsFound = mutableListOf<String>()
        val patternsMissing = mutableListOf<String>()
        var error: String? = null

        try {
            // Clean up previous run
            if (workDir.exists()) {
                workDir.deleteRecursively()
            }

            // Clone repository
            println("  Cloning repository...")
            val cloneResult = runCommand(listOf("git", "clone", repoUrl, eval.id), workspaceDir)
            if (!cloneResult.success) {
                throw RuntimeException("Clone failed: ${cloneResult.output}")
            }

            // Checkout baseline
            println("  Checking out baseline ($baselineCommit)...")
            val checkoutResult = runCommand(listOf("git", "checkout", baselineCommit), workDir)
            if (!checkoutResult.success) {
                throw RuntimeException("Checkout failed: ${checkoutResult.output}")
            }

            // Copy skill into project
            println("  Installing skill...")
            val skillDestDir = File(workDir, ".claude/skills/viaduct")
            skillDestDir.mkdirs()
            skillDir.copyRecursively(skillDestDir, overwrite = true)

            // Run setup query if provided
            eval.setupQuery?.let { setupQuery ->
                println("  Running setup...")
                runClaude(setupQuery, workDir)
            }

            // Run main evaluation query
            println("  Running Claude with skill...")
            claudeOutput = runClaude(eval.query, workDir)

            // Run gradle build
            println("  Running gradle build...")
            val gradleResult = runCommand(
                listOf("./gradlew", ":backend:classes", "--no-daemon", "-q"),
                workDir,
                timeoutMinutes = 10
            )
            buildOutput = gradleResult.output
            buildSuccess = gradleResult.success

            // Check for expected patterns
            if (eval.verifyPatterns.isNotEmpty()) {
                println("  Checking patterns...")
                val srcDir = File(workDir, "backend/src")
                for (pattern in eval.verifyPatterns) {
                    val grepResult = runCommand(
                        listOf("grep", "-rE", pattern, "."),
                        srcDir,
                        ignoreExitCode = true
                    )
                    if (grepResult.output.isNotBlank()) {
                        patternsFound.add(pattern)
                        println("    ✓ $pattern")
                    } else {
                        patternsMissing.add(pattern)
                        println("    ✗ $pattern")
                    }
                }
            }

        } catch (e: Exception) {
            error = e.message
        }

        val durationMs = System.currentTimeMillis() - startTime
        val passed = buildSuccess && patternsMissing.isEmpty() && error == null

        return EvaluationResult(
            id = eval.id,
            name = eval.name,
            passed = passed,
            buildSuccess = buildSuccess,
            patternsFound = patternsFound,
            patternsMissing = patternsMissing,
            claudeOutput = claudeOutput,
            buildOutput = buildOutput,
            error = error,
            durationMs = durationMs
        )
    }

    /**
     * Run Claude CLI with the given prompt.
     */
    private fun runClaude(prompt: String, workDir: File): String {
        val result = runCommand(
            listOf(
                "claude",
                "--print",
                "--dangerously-skip-permissions",
                "--allowedTools", "Read,Glob,Grep,Write,Edit,Bash",
                "-p", prompt
            ),
            workDir,
            timeoutMinutes = 10,
            ignoreExitCode = true
        )
        return result.output
    }

    /**
     * Run a command and return the result.
     */
    private fun runCommand(
        command: List<String>,
        workDir: File,
        timeoutMinutes: Long = 5,
        ignoreExitCode: Boolean = false
    ): CommandResult {
        return try {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)

            if (!completed) {
                process.destroyForcibly()
                CommandResult(false, "Command timed out after $timeoutMinutes minutes")
            } else {
                val success = ignoreExitCode || process.exitValue() == 0
                CommandResult(success, output)
            }
        } catch (e: Exception) {
            CommandResult(false, "Command failed: ${e.message}")
        }
    }

    private data class CommandResult(
        val success: Boolean,
        val output: String
    )
}
