#!/bin/bash
#
# Viaduct Skill Evaluation Harness
#
# Runs evaluations against the viaduct skill.
# Each evaluation:
#   1. Copies base-template to temp directory
#   2. Appends eval-specific schema types
#   3. Runs Gradle to generate scaffolding
#   4. Runs Claude to implement the feature
#   5. Builds and verifies patterns
#
# Usage:
#   ./run-evaluations.sh [options] [eval-id]
#
# Options:
#   --no-skill    Run without the viaduct skill (baseline test)
#   --skill       Run with the viaduct skill (default)
#
# Environment:
#   MAX_RETRIES=3    Set max retry attempts
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EVAL_FILE="$SCRIPT_DIR/evaluations.json"
OUTPUT_DIR="$SCRIPT_DIR/.eval-outputs"
BASE_TEMPLATE="$SCRIPT_DIR/base-template"
WORK_DIR="/tmp/viaduct-skill-eval"

# Default settings
USE_SKILL=1
FILTER=""
MAX_RETRIES="${MAX_RETRIES:-3}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --no-skill)
            USE_SKILL=0
            shift
            ;;
        --skill)
            USE_SKILL=1
            shift
            ;;
        *)
            FILTER="$1"
            shift
            ;;
    esac
done

mkdir -p "$OUTPUT_DIR"

check_deps() {
    local missing=0
    command -v jq &>/dev/null || { echo -e "${RED}Error: jq required${NC}"; missing=1; }
    command -v claude &>/dev/null || { echo -e "${RED}Error: claude CLI required${NC}"; missing=1; }
    command -v java &>/dev/null || { echo -e "${RED}Error: java 17+ required${NC}"; missing=1; }
    [[ ! -d "$BASE_TEMPLATE" ]] && { echo -e "${RED}Error: base-template not found at $BASE_TEMPLATE${NC}"; missing=1; }
    [[ $missing -eq 1 ]] && exit 1
}

setup_project() {
    local schema_addition="$1"

    echo "  Setting up fresh project from base-template..."

    # Clean and copy base template
    rm -rf "$WORK_DIR"
    cp -r "$BASE_TEMPLATE" "$WORK_DIR"

    # Append schema types for this evaluation
    if [[ -n "$schema_addition" ]]; then
        echo "" >> "$WORK_DIR/src/main/viaduct/schema/Schema.graphqls"
        echo "$schema_addition" >> "$WORK_DIR/src/main/viaduct/schema/Schema.graphqls"
    fi

    # Generate scaffolding with Gradle
    echo "  Generating Viaduct scaffolding..."
    if ! (cd "$WORK_DIR" && ./gradlew viaductCodegen --no-daemon -q 2>&1); then
        echo -e "  ${RED}Scaffolding generation failed${NC}"
        return 1
    fi

    # Install skill if in skill mode
    if [[ $USE_SKILL -eq 1 ]]; then
        echo "  Installing viaduct skill..."
        mkdir -p "$WORK_DIR/.claude/skills/viaduct"
        cp "$SCRIPT_DIR/SKILL.md" "$WORK_DIR/.claude/skills/viaduct/"
    fi

    return 0
}

# Extract the key error from build output
extract_error_summary() {
    local build_output="$1"

    # Look for common error patterns and extract the key line
    if grep -q "Unresolved reference" "$build_output" 2>/dev/null; then
        grep "Unresolved reference" "$build_output" | head -1 | sed 's/.*: //'
    elif grep -q "cannot find symbol" "$build_output" 2>/dev/null; then
        grep "cannot find symbol" "$build_output" | head -1
    elif grep -q "not found" "$build_output" 2>/dev/null; then
        grep -E "not found|Not found" "$build_output" | head -1 | sed 's/.*: //'
    elif grep -q "expected" "$build_output" 2>/dev/null; then
        grep "expected" "$build_output" | head -1
    elif grep -q "error:" "$build_output" 2>/dev/null; then
        grep "error:" "$build_output" | head -1 | sed 's/.*error: //'
    else
        tail -3 "$build_output" | head -1
    fi
}

run_evaluation() {
    local eval_id="$1"
    local eval_name="$2"
    local eval_query="$3"
    local verify_patterns="$4"
    local schema_addition="$5"
    local negative_patterns="$6"

    local suffix=$([[ $USE_SKILL -eq 0 ]] && echo "-noskill" || echo "")
    local claude_output="$OUTPUT_DIR/$eval_id$suffix-claude.txt"
    local build_output="$OUTPUT_DIR/$eval_id$suffix-build.txt"
    local errors_file="$OUTPUT_DIR/$eval_id$suffix-errors.txt"

    echo ""
    echo "============================================================"
    echo -e "${YELLOW}$eval_id: $eval_name${NC}"
    [[ $USE_SKILL -eq 0 ]] && echo -e "${BLUE}(NO SKILL MODE)${NC}"
    echo "============================================================"

    # Setup fresh project with schema for this eval
    if ! setup_project "$schema_addition"; then
        echo "SETUP_FAILED" > "$errors_file"
        return 1
    fi

    # Clear errors file
    > "$errors_file"

    # Get auth token
    echo "  Getting IAP auth token..."
    local auth_token
    auth_token=$(iap-auth https://devaigateway.a.musta.ch 2>/dev/null)
    if [[ -z "$auth_token" ]]; then
        echo -e "  ${RED}Failed to get IAP auth token${NC}"
        echo "AUTH_FAILED" >> "$errors_file"
        return 1
    fi

    # Build query - remove skill reference if no-skill mode
    local full_query
    if [[ $USE_SKILL -eq 1 ]]; then
        full_query="Work ONLY in $WORK_DIR. Implement:

$eval_query"
    else
        local clean_query="${eval_query//Use the viaduct skill for guidance./}"
        clean_query="${clean_query//Use the viaduct skill for guidance/}"
        full_query="Work ONLY in $WORK_DIR. Implement:

$clean_query"
    fi

    echo "  Running Claude (attempt 1/$MAX_RETRIES)..."

    CLAUDE_CODE_USE_BEDROCK=1 \
    ANTHROPIC_BEDROCK_BASE_URL="https://devaigateway.a.musta.ch/bedrock" \
    CLAUDE_CODE_SKIP_BEDROCK_AUTH=1 \
    ANTHROPIC_AUTH_TOKEN="$auth_token" \
    claude -p "$full_query" \
          --dangerously-skip-permissions \
          --no-session-persistence \
          "$WORK_DIR" > "$claude_output" 2>&1 || true

    # Build and fix loop
    local build_success=0
    local attempt=1
    local retry_errors=()

    while [[ $attempt -le $MAX_RETRIES ]]; do
        echo "  Running gradle build (attempt $attempt/$MAX_RETRIES)..."

        # Run viaductCodegen first in case Claude modified the schema (adds new @resolver fields)
        if (cd "$WORK_DIR" && ./gradlew viaductCodegen classes --no-daemon -q > "$build_output" 2>&1); then
            build_success=1
            echo -e "  ${GREEN}Build: PASSED${NC}"
            break
        else
            echo -e "  ${RED}Build: FAILED${NC}"

            # Extract and save the error
            local error_summary=$(extract_error_summary "$build_output")
            echo "Attempt $attempt: $error_summary" >> "$errors_file"
            retry_errors+=("$error_summary")

            # Show the error
            echo -e "    ${CYAN}Error: $error_summary${NC}"

            if [[ $attempt -lt $MAX_RETRIES ]]; then
                echo "  Letting Claude fix..."
                local build_error=$(tail -50 "$build_output")

                CLAUDE_CODE_USE_BEDROCK=1 \
                ANTHROPIC_BEDROCK_BASE_URL="https://devaigateway.a.musta.ch/bedrock" \
                CLAUDE_CODE_SKIP_BEDROCK_AUTH=1 \
                ANTHROPIC_AUTH_TOKEN="$auth_token" \
                claude -p "Build failed. Fix it:
\`\`\`
$build_error
\`\`\`
Work ONLY in $WORK_DIR." \
                      --dangerously-skip-permissions \
                      --no-session-persistence \
                      "$WORK_DIR" >> "$claude_output" 2>&1 || true
            fi
        fi
        ((attempt++))
    done

    # Check patterns
    local patterns_found=0
    local patterns_total=0
    local missing_patterns=()

    if [[ -n "$verify_patterns" ]]; then
        echo "  Checking required patterns..."
        while IFS= read -r pattern; do
            if [[ -n "$pattern" ]]; then
                ((patterns_total++))
                if grep -rqE "$pattern" "$WORK_DIR/src" 2>/dev/null; then
                    ((patterns_found++))
                    echo -e "    ${GREEN}✓${NC} $pattern"
                else
                    echo -e "    ${RED}✗${NC} $pattern"
                    missing_patterns+=("$pattern")
                fi
            fi
        done <<< "$verify_patterns"
    fi

    # Check negative patterns (patterns that should NOT appear)
    local negative_failed=0
    local found_negative=()

    if [[ -n "$negative_patterns" ]]; then
        echo "  Checking forbidden patterns (should NOT appear)..."
        while IFS= read -r pattern; do
            if [[ -n "$pattern" ]]; then
                if grep -rqE "$pattern" "$WORK_DIR/src" 2>/dev/null; then
                    echo -e "    ${RED}✗ FOUND:${NC} $pattern"
                    found_negative+=("$pattern")
                    ((negative_failed++))
                else
                    echo -e "    ${GREEN}✓ not found:${NC} $pattern"
                fi
            fi
        done <<< "$negative_patterns"
    fi

    # Record missing patterns
    if [[ ${#missing_patterns[@]} -gt 0 ]]; then
        echo "Missing patterns:" >> "$errors_file"
        for p in "${missing_patterns[@]}"; do
            echo "  - $p" >> "$errors_file"
        done
    fi

    # Record forbidden patterns that were found
    if [[ ${#found_negative[@]} -gt 0 ]]; then
        echo "Forbidden patterns found (WRONG!):" >> "$errors_file"
        for p in "${found_negative[@]}"; do
            echo "  - $p" >> "$errors_file"
        done
    fi

    if [[ $build_success -eq 1 ]] && [[ $patterns_found -eq $patterns_total ]] && [[ $negative_failed -eq 0 ]]; then
        echo -e "\n  ${GREEN}✅ PASSED${NC} (attempt $attempt)"
        # Write result to file: "attempt|error1|error2|..."
        local result="$attempt"
        for err in "${retry_errors[@]}"; do
            result="$result|$err"
        done
        echo "$result" > "$OUTPUT_DIR/.last-result"
        return 0
    else
        echo -e "\n  ${RED}❌ FAILED${NC}"
        echo "FAILED" > "$OUTPUT_DIR/.last-result"
        return 1
    fi
}

main() {
    echo "Viaduct Skill Evaluation Harness"
    echo "================================"
    echo "Base template: $BASE_TEMPLATE"
    echo "Work directory: $WORK_DIR"
    [[ $USE_SKILL -eq 1 ]] && echo -e "Mode: ${GREEN}WITH SKILL${NC}" || echo -e "Mode: ${BLUE}NO SKILL${NC}"
    echo "Max retries: $MAX_RETRIES"
    echo ""

    check_deps

    local eval_count=$(jq length "$EVAL_FILE")
    local passed=0 failed=0 skipped=0 one_shot=0

    # Track detailed results using a temp file
    local results_file="$OUTPUT_DIR/.results-tmp"
    > "$results_file"

    for i in $(seq 0 $((eval_count - 1))); do
        local eval_id=$(jq -r ".[$i].id" "$EVAL_FILE")
        local eval_name=$(jq -r ".[$i].name" "$EVAL_FILE")
        local eval_query=$(jq -r ".[$i].query" "$EVAL_FILE")
        local verify_patterns=$(jq -r ".[$i].verify_patterns | .[]?" "$EVAL_FILE" 2>/dev/null || echo "")
        local schema_addition=$(jq -r ".[$i].schema // empty" "$EVAL_FILE" 2>/dev/null || echo "")
        local negative_patterns=$(jq -r ".[$i].negative_patterns | .[]?" "$EVAL_FILE" 2>/dev/null || echo "")

        # Filter check - partial match on id or name
        if [[ -n "$FILTER" && "$eval_id" != *"$FILTER"* && "$eval_name" != *"$FILTER"* ]]; then
            ((skipped++)) || true
            continue
        fi

        if run_evaluation "$eval_id" "$eval_name" "$eval_query" "$verify_patterns" "$schema_addition" "$negative_patterns"; then
            ((passed++))

            # Read result from file: "attempt|error1|error2|..."
            local result=$(cat "$OUTPUT_DIR/.last-result")
            local attempt_num=$(echo "$result" | cut -d'|' -f1)
            local errors=$(echo "$result" | cut -d'|' -f2-)

            echo "PASS|$eval_id|$attempt_num|$errors" >> "$results_file"

            [[ "$attempt_num" == "1" ]] && ((one_shot++))
        else
            ((failed++))
            echo "FAIL|$eval_id" >> "$results_file"
        fi
    done

    echo ""
    echo "============================================================"
    echo "SUMMARY"
    echo "============================================================"
    [[ $USE_SKILL -eq 1 ]] && echo -e "Mode: ${GREEN}WITH SKILL${NC}" || echo -e "Mode: ${BLUE}NO SKILL${NC}"
    echo -e "Passed: ${GREEN}$passed${NC} / $((passed + failed))"
    echo -e "One-shot: ${GREEN}$one_shot${NC} / $passed"
    echo -e "Failed: ${RED}$failed${NC}"
    [[ $skipped -gt 0 ]] && echo "Skipped: $skipped"

    echo ""
    echo "DETAILED RESULTS:"
    echo "-----------------"

    # Read results and display
    while IFS='|' read -r status eval_id attempt_num errors; do
        if [[ "$status" == "PASS" ]]; then
            if [[ "$attempt_num" == "1" ]]; then
                echo -e "${GREEN}✓${NC} $eval_id - ${GREEN}one-shot${NC}"
            else
                echo -e "${GREEN}✓${NC} $eval_id - attempt $attempt_num"
                if [[ -n "$errors" ]]; then
                    # Show retry errors
                    local err_num=1
                    local remaining="$errors"
                    while [[ -n "$remaining" ]]; do
                        local err=$(echo "$remaining" | cut -d'|' -f1)
                        remaining=$(echo "$remaining" | cut -d'|' -f2-)
                        [[ "$remaining" == "$err" ]] && remaining=""
                        if [[ -n "$err" ]]; then
                            echo -e "    ${CYAN}retry $err_num: $err${NC}"
                            ((err_num++))
                        fi
                    done
                fi
            fi
        elif [[ "$status" == "FAIL" ]]; then
            echo -e "${RED}✗${NC} $eval_id - FAILED"
            # Show errors from file
            local suffix=$([[ $USE_SKILL -eq 0 ]] && echo "-noskill" || echo "")
            local errors_file="$OUTPUT_DIR/$eval_id$suffix-errors.txt"
            if [[ -f "$errors_file" ]]; then
                while IFS= read -r line; do
                    echo -e "    ${CYAN}$line${NC}"
                done < "$errors_file"
            fi
        fi
    done < "$results_file"

    echo ""
    echo "Outputs: $OUTPUT_DIR"

    [[ $failed -gt 0 ]] && exit 1
    exit 0
}

main
