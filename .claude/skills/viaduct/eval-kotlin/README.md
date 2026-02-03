# Viaduct Skill Evaluations (Kotlin)

Kotlin/Kotest-based evaluation harness for the Viaduct skill.

## Overview

This module runs skill evaluations as Kotest tests. Each evaluation:

1. Clones `viaduct-batteries-included` to a temp directory
2. Checks out the baseline commit (before features were added)
3. Copies the viaduct skill into `.claude/skills/`
4. Runs Claude with the evaluation query
5. Runs `./gradlew :backend:classes` to verify compilation
6. Checks for expected patterns in the generated code

## Requirements

- Java 17+
- `ANTHROPIC_API_KEY` environment variable
- Claude Code CLI installed
- Git SSH access to `viaduct-dev/viaduct-batteries-included`

## Usage

### Run all evaluations

```bash
cd eval-kotlin
./gradlew test
```

### Run a specific evaluation

```bash
./gradlew test --tests "*eval-01*"
./gradlew test --tests "*field-resolver*"
```

### View detailed output

```bash
./gradlew test --info
```

## Test Output

Results are printed to stdout with:
- Pass/fail status for each evaluation
- Build success/failure
- Pattern matching results
- Duration for each test

## Adding New Evaluations

Add entries to `../evaluations.json`:

```json
{
  "id": "eval-08-new-feature",
  "name": "New Feature Test",
  "skills": ["viaduct"],
  "query": "Implement the new feature using the viaduct skill...",
  "expected_behavior": ["..."],
  "verify_patterns": ["PatternToFind", "AnotherPattern"]
}
```

The test will automatically pick up new evaluations.

## Integration with CI

The tests exit with code 0 if all pass, non-zero otherwise:

```bash
./gradlew test || echo "Some evaluations failed"
```

For GitHub Actions:

```yaml
- name: Run Skill Evaluations
  env:
    ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
  run: |
    cd viaduct/eval-kotlin
    ./gradlew test
```
