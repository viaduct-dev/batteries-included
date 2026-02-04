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

run_evaluation() {
    local eval_id="$1"
    local eval_name="$2"
    local eval_query="$3"
    local verify_patterns="$4"
    local schema_addition="$5"

    local suffix=$([[ $USE_SKILL -eq 0 ]] && echo "-noskill" || echo "")
    local claude_output="$OUTPUT_DIR/$eval_id$suffix-claude.txt"
    local build_output="$OUTPUT_DIR/$eval_id$suffix-build.txt"

    echo ""
    echo "============================================================"
    echo -e "${YELLOW}$eval_id: $eval_name${NC}"
    [[ $USE_SKILL -eq 0 ]] && echo -e "${BLUE}(NO SKILL MODE)${NC}"
    echo "============================================================"

    # Setup fresh project with schema for this eval
    if ! setup_project "$schema_addition"; then
        return 1
    fi

    # Get auth token
    echo "  Getting IAP auth token..."
    local auth_token
    auth_token=$(iap-auth https://devaigateway.a.musta.ch 2>/dev/null)
    if [[ -z "$auth_token" ]]; then
        echo -e "  ${RED}Failed to get IAP auth token${NC}"
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

    while [[ $attempt -le $MAX_RETRIES ]]; do
        echo "  Running gradle build (attempt $attempt/$MAX_RETRIES)..."

        if (cd "$WORK_DIR" && ./gradlew classes --no-daemon -q > "$build_output" 2>&1); then
            build_success=1
            echo -e "  ${GREEN}Build: PASSED${NC}"
            break
        else
            echo -e "  ${RED}Build: FAILED${NC}"

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
            else
                tail -10 "$build_output" | sed 's/^/    /'
            fi
        fi
        ((attempt++))
    done

    # Check patterns
    local patterns_found=0
    local patterns_total=0

    if [[ -n "$verify_patterns" ]]; then
        echo "  Checking patterns..."
        while IFS= read -r pattern; do
            if [[ -n "$pattern" ]]; then
                ((patterns_total++))
                if grep -rqE "$pattern" "$WORK_DIR/src" 2>/dev/null; then
                    ((patterns_found++))
                    echo -e "    ${GREEN}✓${NC} $pattern"
                else
                    echo -e "    ${RED}✗${NC} $pattern"
                fi
            fi
        done <<< "$verify_patterns"
    fi

    if [[ $build_success -eq 1 ]] && [[ $patterns_found -eq $patterns_total ]]; then
        echo -e "\n  ${GREEN}✅ PASSED${NC} (attempt $attempt)"
        echo "$attempt"
        return 0
    else
        echo -e "\n  ${RED}❌ FAILED${NC}"
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

    # Track results for summary
    declare -a passed_tests=()
    declare -a failed_tests=()

    for i in $(seq 0 $((eval_count - 1))); do
        local eval_id=$(jq -r ".[$i].id" "$EVAL_FILE")
        local eval_name=$(jq -r ".[$i].name" "$EVAL_FILE")
        local eval_query=$(jq -r ".[$i].query" "$EVAL_FILE")
        local verify_patterns=$(jq -r ".[$i].verify_patterns | .[]?" "$EVAL_FILE" 2>/dev/null || echo "")
        local schema_addition=$(jq -r ".[$i].schema // empty" "$EVAL_FILE" 2>/dev/null || echo "")

        # Filter check - partial match on id or name
        if [[ -n "$FILTER" && "$eval_id" != *"$FILTER"* && "$eval_name" != *"$FILTER"* ]]; then
            ((skipped++)) || true
            continue
        fi

        local attempt_count
        if attempt_count=$(run_evaluation "$eval_id" "$eval_name" "$eval_query" "$verify_patterns" "$schema_addition"); then
            ((passed++))
            passed_tests+=("$eval_id")
            [[ "$attempt_count" == "1" ]] && ((one_shot++))
        else
            ((failed++))
            failed_tests+=("$eval_id")
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
    if [[ ${#passed_tests[@]} -gt 0 ]]; then
        echo -e "${GREEN}PASSED:${NC}"
        for t in "${passed_tests[@]}"; do echo "  - $t"; done
    fi
    if [[ ${#failed_tests[@]} -gt 0 ]]; then
        echo -e "${RED}FAILED:${NC}"
        for t in "${failed_tests[@]}"; do echo "  - $t"; done
    fi
    echo ""
    echo "Outputs: $OUTPUT_DIR"

    [[ $failed -gt 0 ]] && exit 1
    exit 0
}

main
