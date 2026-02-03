/**
 * Viaduct Skill Evaluation Harness
 *
 * Runs evaluations against the viaduct skill using a real project.
 * Each evaluation:
 *   1. Clones viaduct-batteries-included to a temp directory
 *   2. Checks out the baseline commit (before validation features)
 *   3. Runs Claude with the skill to implement the feature
 *   4. Runs ./gradlew build to verify compilation
 *   5. Reports pass/fail
 *
 * Usage:
 *   npx tsx run-evaluations.ts [eval-id]
 *
 * Requirements:
 *   - npm install @anthropic-ai/claude-agent-sdk
 *   - ANTHROPIC_API_KEY environment variable
 *   - Claude Code installed
 *   - Java 17+ and Gradle
 *   - Git
 */

import { query, ClaudeAgentOptions } from "@anthropic-ai/claude-agent-sdk";
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, cpSync } from "fs";
import { join } from "path";
import { execSync, spawn } from "child_process";

// Configuration
const REPO_URL = "git@github.com:viaduct-dev/viaduct-batteries-included.git";
const BASELINE_COMMIT = "a20f9be"; // Before validation features were added
const SKILL_DIR = join(__dirname, ".."); // Parent of this file (the skill root)

interface Evaluation {
  id: string;
  name: string;
  skills: string[];
  query: string;
  files: string[];
  expected_behavior: string[];
  // Additional fields for real evaluation
  setup_query?: string; // Optional setup before main query
  verify_patterns?: string[]; // Patterns to grep for in generated code
}

interface EvalResult {
  id: string;
  name: string;
  query: string;
  passed: boolean;
  build_success: boolean;
  patterns_found: string[];
  patterns_missing: string[];
  claude_output: string;
  build_output: string;
  error?: string;
  duration_ms: number;
}

function runCommand(cmd: string, cwd: string, timeout = 300000): { success: boolean; output: string } {
  try {
    const output = execSync(cmd, {
      cwd,
      encoding: "utf-8",
      timeout,
      stdio: ["pipe", "pipe", "pipe"],
    });
    return { success: true, output };
  } catch (error: any) {
    return {
      success: false,
      output: error.stdout?.toString() || "" + "\n" + error.stderr?.toString() || "",
    };
  }
}

async function runClaude(prompt: string, workDir: string): Promise<string> {
  const outputs: string[] = [];

  try {
    for await (const message of query({
      prompt,
      options: {
        // Load the viaduct skill
        skillPaths: [SKILL_DIR],
        // Allow all code tools
        allowedTools: ["Read", "Glob", "Grep", "Write", "Edit", "Bash"],
        // Auto-accept edits for automation
        permissionMode: "acceptEdits",
        // Set working directory to the cloned repo
        cwd: workDir,
        // Limit turns to prevent runaway
        maxTurns: 50,
      } as ClaudeAgentOptions,
    })) {
      // Capture all output types
      if ("result" in message) {
        outputs.push(String(message.result));
      }
      if ("content" in message) {
        if (typeof message.content === "string") {
          outputs.push(message.content);
        } else if (Array.isArray(message.content)) {
          for (const block of message.content) {
            if (typeof block === "string") {
              outputs.push(block);
            } else if ("text" in block) {
              outputs.push(block.text);
            }
          }
        }
      }
    }
  } catch (error) {
    outputs.push(`Error: ${error}`);
  }

  return outputs.join("\n");
}

async function runEvaluation(eval_: Evaluation, baseDir: string): Promise<EvalResult> {
  const startTime = Date.now();
  const workDir = join(baseDir, eval_.id);

  const result: EvalResult = {
    id: eval_.id,
    name: eval_.name,
    query: eval_.query,
    passed: false,
    build_success: false,
    patterns_found: [],
    patterns_missing: [],
    claude_output: "",
    build_output: "",
    duration_ms: 0,
  };

  try {
    // Clean up any previous run
    if (existsSync(workDir)) {
      rmSync(workDir, { recursive: true, force: true });
    }

    console.log("  Cloning repository...");
    const cloneResult = runCommand(`git clone ${REPO_URL} ${eval_.id}`, baseDir);
    if (!cloneResult.success) {
      throw new Error(`Clone failed: ${cloneResult.output}`);
    }

    console.log(`  Checking out baseline (${BASELINE_COMMIT})...`);
    const checkoutResult = runCommand(`git checkout ${BASELINE_COMMIT}`, workDir);
    if (!checkoutResult.success) {
      throw new Error(`Checkout failed: ${checkoutResult.output}`);
    }

    // Copy the skill into the project's .claude/skills directory
    const skillDestDir = join(workDir, ".claude", "skills", "viaduct");
    mkdirSync(skillDestDir, { recursive: true });
    cpSync(SKILL_DIR, skillDestDir, { recursive: true });

    // Run setup query if provided
    if (eval_.setup_query) {
      console.log("  Running setup...");
      await runClaude(eval_.setup_query, workDir);
    }

    // Run main evaluation query
    console.log("  Running Claude with skill...");
    result.claude_output = await runClaude(eval_.query, workDir);

    // Run gradle build
    console.log("  Running gradle build...");
    const buildResult = runCommand(
      "./gradlew :backend:classes --no-daemon -q",
      workDir,
      600000 // 10 minute timeout for build
    );
    result.build_output = buildResult.output;
    result.build_success = buildResult.success;

    // Check for expected patterns in generated code
    if (eval_.verify_patterns && eval_.verify_patterns.length > 0) {
      const backendSrc = join(workDir, "backend", "src");
      for (const pattern of eval_.verify_patterns) {
        const grepResult = runCommand(`grep -r "${pattern}" . || true`, backendSrc);
        if (grepResult.output.trim()) {
          result.patterns_found.push(pattern);
        } else {
          result.patterns_missing.push(pattern);
        }
      }
    }

    // Determine pass/fail
    result.passed = result.build_success && result.patterns_missing.length === 0;

  } catch (error) {
    result.error = String(error);
  }

  result.duration_ms = Date.now() - startTime;
  return result;
}

async function main() {
  const args = process.argv.slice(2);
  const evalFilter = args[0];

  // Load evaluations
  const evalPath = join(__dirname, "evaluations.json");
  const evaluations: Evaluation[] = JSON.parse(readFileSync(evalPath, "utf-8"));

  // Filter if specified
  const toRun = evalFilter
    ? evaluations.filter((e) => e.id === evalFilter || e.name.toLowerCase().includes(evalFilter.toLowerCase()))
    : evaluations;

  if (toRun.length === 0) {
    console.error(`No evaluations found matching: ${evalFilter}`);
    console.error(`Available: ${evaluations.map((e) => e.id).join(", ")}`);
    process.exit(1);
  }

  console.log("Viaduct Skill Evaluation Harness");
  console.log("================================");
  console.log(`Repository: ${REPO_URL}`);
  console.log(`Baseline: ${BASELINE_COMMIT}`);
  console.log(`Skill: ${SKILL_DIR}`);
  console.log(`Running ${toRun.length} evaluation(s)`);
  console.log("");

  // Create base directory for all evaluations
  const baseDir = join(__dirname, ".eval-workspace");
  mkdirSync(baseDir, { recursive: true });

  const results: EvalResult[] = [];

  for (const eval_ of toRun) {
    console.log(`\n${"=".repeat(60)}`);
    console.log(`${eval_.id}: ${eval_.name}`);
    console.log(`${"=".repeat(60)}`);
    console.log(`Query: ${eval_.query.slice(0, 80)}...`);

    const result = await runEvaluation(eval_, baseDir);
    results.push(result);

    // Print result
    const status = result.passed ? "✅ PASSED" : "❌ FAILED";
    console.log(`\nResult: ${status} (${(result.duration_ms / 1000).toFixed(1)}s)`);
    console.log(`  Build: ${result.build_success ? "✅" : "❌"}`);

    if (result.patterns_found.length > 0) {
      console.log(`  Patterns found: ${result.patterns_found.length}`);
    }
    if (result.patterns_missing.length > 0) {
      console.log(`  Patterns missing:`);
      for (const p of result.patterns_missing) {
        console.log(`    - ${p}`);
      }
    }
    if (result.error) {
      console.log(`  Error: ${result.error}`);
    }
    if (!result.build_success) {
      console.log(`  Build output (last 500 chars):`);
      console.log(`    ${result.build_output.slice(-500).replace(/\n/g, "\n    ")}`);
    }
  }

  // Summary
  console.log(`\n${"=".repeat(60)}`);
  console.log("SUMMARY");
  console.log("=".repeat(60));

  const passed = results.filter((r) => r.passed).length;
  const buildPassed = results.filter((r) => r.build_success).length;

  console.log(`Overall: ${passed}/${results.length} passed`);
  console.log(`Builds: ${buildPassed}/${results.length} successful`);
  console.log("");

  for (const r of results) {
    const icon = r.passed ? "✅" : "❌";
    const buildIcon = r.build_success ? "🔨" : "💔";
    console.log(`  ${icon} ${buildIcon} ${r.id}: ${r.name} (${(r.duration_ms / 1000).toFixed(1)}s)`);
  }

  // Save detailed results
  const resultsPath = join(__dirname, "evaluation-results.json");
  writeFileSync(resultsPath, JSON.stringify(results, null, 2));
  console.log(`\nDetailed results: ${resultsPath}`);

  // Save individual outputs for debugging
  const outputsDir = join(__dirname, ".eval-outputs");
  mkdirSync(outputsDir, { recursive: true });
  for (const r of results) {
    writeFileSync(join(outputsDir, `${r.id}-claude.txt`), r.claude_output);
    writeFileSync(join(outputsDir, `${r.id}-build.txt`), r.build_output);
  }
  console.log(`Individual outputs: ${outputsDir}/`);

  process.exit(passed === results.length ? 0 : 1);
}

main().catch((err) => {
  console.error("Fatal error:", err);
  process.exit(1);
});
