#!/usr/bin/env bash
#
# MCP endpoint smoke tests (JSON-RPC 2.0 over stateless POST).
#
# Usage:
#   ./scripts/test-mcp.sh              # test against localhost:8080
#   ./scripts/test-mcp.sh --lambda     # auto-resolve Lambda Function URL
#   BASE_URL=https://… ./scripts/test-mcp.sh   # explicit URL
#
# The local Ktor app exposes MCP via SSE transport, so most tests
# target the Lambda's stateless POST /mcp handler. For localhost the
# app would need a stateless /mcp POST route to pass these tests.
#
set -euo pipefail

# ── Target resolution ─────────────────────────────────────────────
USE_LAMBDA=false
for arg in "$@"; do
    case "$arg" in
        --lambda) USE_LAMBDA=true ;;
    esac
done

# ── AWS session check ───────────────────────────────────────────────
if ! aws sts get-caller-identity &>/dev/null; then
    echo "AWS session is not active. Attempting 'aws sso login'..."
    aws sso login
    if ! aws sts get-caller-identity &>/dev/null; then
        echo "ERROR: AWS session is still not active after login. Exiting."
        exit 1
    fi
    echo "AWS session is now active."
fi

if [ "$USE_LAMBDA" = true ]; then
    LAMBDA_URL=$(aws lambda get-function-url-config \
        --function-name kokoro-tts-lambda \
        --query "FunctionUrl" --output text 2>/dev/null) || true
    if [ -z "$LAMBDA_URL" ] || [ "$LAMBDA_URL" = "None" ]; then
        echo "ERROR: Could not resolve Lambda Function URL. Is kokoro-tts-lambda deployed?"
        exit 1
    fi
    BASE_URL="${LAMBDA_URL%/}"
else
    BASE_URL="${BASE_URL:-http://localhost:8080}"
fi

# ── Colors & symbols ────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

PASS="${GREEN}PASS${RESET}"
FAIL="${RED}FAIL${RESET}"

passed=0
failed=0
total_time_ms=0
LAST_RESPONSE=""

# Benchmark accumulators (parallel arrays)
bench_names=()
bench_audio_ms=()
bench_gen_ms=()
bench_rtf=()
bench_total_audio_ms=0
bench_total_gen_ms=0

# ── Helpers ─────────────────────────────────────────────────────────

print_header() {
    echo ""
    echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
    echo -e "${BOLD}  $1${RESET}"
    echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
}

print_section() {
    echo ""
    echo -e "  ${BOLD}$1${RESET}"
    echo -e "  ${DIM}$(printf '%.0s─' {1..76})${RESET}"
}

fmt_time() {
    local ms="$1"
    if [ "$ms" -ge 60000 ] 2>/dev/null; then
        awk "BEGIN { printf \"%dm %04.1fs\", int($ms/60000), ($ms%60000)/1000 }"
    elif [ "$ms" -ge 1000 ] 2>/dev/null; then
        awk "BEGIN { printf \"%.2fs\", $ms / 1000 }"
    else
        echo "${ms}ms"
    fi
}

fmt_size() {
    local bytes="$1"
    if [ "$bytes" -ge 1048576 ] 2>/dev/null; then
        awk "BEGIN { printf \"%.2f MB\", $bytes / 1048576 }"
    elif [ "$bytes" -ge 1024 ] 2>/dev/null; then
        awk "BEGIN { printf \"%.1f KB\", $bytes / 1024 }"
    else
        echo "${bytes} B"
    fi
}

audio_duration_ms() {
    local size="$1" fmt="$2"
    case "$fmt" in
        wav) awk "BEGIN { printf \"%d\", ($size - 44) / 48.0 }" ;;
        mp3) awk "BEGIN { printf \"%d\", $size * 8.0 / 128 }" ;;
        *)   echo "0" ;;
    esac
}

# Try to extract synthesis response details and display benchmark info.
# Reads the JSON-RPC response from LAST_RESPONSE global.
show_synthesis_details() {
    local name="$1" elapsed_ms="$2"

    local synth_json
    synth_json=$(echo "$LAST_RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)
text = d.get('result', {}).get('content', [{}])[0].get('text', '')
try:
    s = json.loads(text)
    if 'url' in s and 'sizeBytes' in s:
        print(json.dumps(s))
except: pass
" 2>/dev/null) || true

    if [ -z "$synth_json" ]; then return; fi

    local format size_bytes voice url
    format=$(echo "$synth_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('format',''))" 2>/dev/null) || true
    size_bytes=$(echo "$synth_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('sizeBytes',0))" 2>/dev/null) || true
    voice=$(echo "$synth_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('voice',''))" 2>/dev/null) || true
    url=$(echo "$synth_json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('url',''))" 2>/dev/null) || true

    if [ -n "$size_bytes" ] && [ "$size_bytes" -gt 0 ] 2>/dev/null; then
        local dur_ms
        dur_ms=$(audio_duration_ms "$size_bytes" "$format")

        local rtf
        if [ "$elapsed_ms" -gt 0 ]; then
            rtf=$(awk "BEGIN { printf \"%.2f\", $dur_ms / $elapsed_ms }")
        else
            rtf="--"
        fi

        local rtf_color="$YELLOW"
        if awk -v r="$rtf" 'BEGIN { exit !(r >= 1.0) }' 2>/dev/null; then
            rtf_color="$GREEN"
        fi

        echo -e "        ${DIM}voice=${voice}  format=${format}  size=$(fmt_size "$size_bytes")${RESET}"
        echo -e "        ${DIM}audio=$(fmt_time "$dur_ms")  gen=$(fmt_time "$elapsed_ms")${RESET}  ${rtf_color}${BOLD}RTF ${rtf}x${RESET}"
        echo -e "        ${DIM}${url}${RESET}"

        bench_names+=("$name")
        bench_audio_ms+=("$dur_ms")
        bench_gen_ms+=("$elapsed_ms")
        bench_rtf+=("$rtf")
        bench_total_audio_ms=$((bench_total_audio_ms + dur_ms))
        bench_total_gen_ms=$((bench_total_gen_ms + elapsed_ms))
    fi
}

# Run an MCP test case.
#   $1  test name
#   $2  expected HTTP status
#   $3  JSON-RPC body
#   $4+ optional check: --has-result | --tools-count N | --contains STR
#                        --error-contains STR | --synthesis
run_mcp_test() {
    local name="$1" expected_http="$2" body="$3"
    shift 3
    local check_mode="" check_value=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --has-result)       check_mode="has_result";       shift ;;
            --tools-count)      check_mode="tools_count";      check_value="$2"; shift 2 ;;
            --contains)         check_mode="contains";         check_value="$2"; shift 2 ;;
            --error-contains)   check_mode="error_contains";   check_value="$2"; shift 2 ;;
            --synthesis)        check_mode="synthesis";         shift ;;
            *) shift ;;
        esac
    done

    local tmpfile
    tmpfile=$(mktemp)

    local start_ns end_ns
    start_ns=$(python3 -c 'import time; print(int(time.time()*1e9))')

    local http_code
    http_code=$(curl -s -o "$tmpfile" -w "%{http_code}" \
        -X POST "$BASE_URL/mcp" \
        -H "Content-Type: application/json" \
        -d "$body") || true

    end_ns=$(python3 -c 'import time; print(int(time.time()*1e9))')

    local elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
    total_time_ms=$((total_time_ms + elapsed_ms))

    LAST_RESPONSE=$(cat "$tmpfile")
    rm -f "$tmpfile"

    # ── HTTP status check ───────────────────────────────────────────
    if [ "$http_code" != "$expected_http" ]; then
        failed=$((failed + 1))
        echo -e "  ${FAIL}  ${name}  ${DIM}HTTP ${http_code} (expected ${expected_http})  $(fmt_time "$elapsed_ms")${RESET}"
        echo -e "        ${DIM}${LAST_RESPONSE:0:200}${RESET}"
        return 0
    fi

    # ── Content validation ──────────────────────────────────────────
    local check_result="ok"

    case "$check_mode" in
        has_result)
            check_result=$(echo "$LAST_RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('ok' if 'result' in d else 'response missing result field')
" 2>&1) || check_result="python error"
            ;;
        tools_count)
            check_result=$(echo "$LAST_RESPONSE" | CHECK_VALUE="$check_value" python3 -c "
import sys, json, os
expected = int(os.environ['CHECK_VALUE'])
d = json.load(sys.stdin)
tools = d.get('result', {}).get('tools', [])
n = len(tools)
print('ok' if n == expected else f'expected {expected} tools, got {n}')
" 2>&1) || check_result="python error"
            ;;
        contains)
            check_result=$(echo "$LAST_RESPONSE" | CHECK_VALUE="$check_value" python3 -c "
import sys, json, os
expected = os.environ['CHECK_VALUE']
d = json.load(sys.stdin)
text = d.get('result', {}).get('content', [{}])[0].get('text', '')
print('ok' if expected in text else f'response does not contain: {expected}')
" 2>&1) || check_result="python error"
            ;;
        error_contains)
            check_result=$(echo "$LAST_RESPONSE" | CHECK_VALUE="$check_value" python3 -c "
import sys, json, os
expected = os.environ['CHECK_VALUE']
d = json.load(sys.stdin)
r = d.get('result', {})
is_error = r.get('isError', False)
text = r.get('content', [{}])[0].get('text', '')
if not is_error:
    print(f'expected isError=true, got false')
elif expected not in text:
    print(f'error text does not contain: {expected}')
else:
    print('ok')
" 2>&1) || check_result="python error"
            ;;
        synthesis)
            check_result=$(echo "$LAST_RESPONSE" | python3 -c "
import sys, json
d = json.load(sys.stdin)
text = d.get('result', {}).get('content', [{}])[0].get('text', '')
try:
    s = json.loads(text)
except Exception as e:
    print(f'synthesis text is not valid JSON: {e}')
    sys.exit(0)
if 'url' not in s:
    print('synthesis response missing url')
elif s.get('sizeBytes', 0) <= 0:
    print('synthesis response has invalid sizeBytes')
else:
    print('ok')
" 2>&1) || check_result="python error"
            ;;
    esac

    if [ "$check_result" != "ok" ]; then
        failed=$((failed + 1))
        echo -e "  ${FAIL}  ${name}  ${DIM}HTTP ${http_code}  $(fmt_time "$elapsed_ms")  — ${check_result}${RESET}"
        echo -e "        ${DIM}${LAST_RESPONSE:0:200}${RESET}"
        return 0
    fi

    passed=$((passed + 1))
    echo -e "  ${PASS}  ${name}  ${DIM}HTTP ${http_code}  $(fmt_time "$elapsed_ms")${RESET}"

    # Show synthesis benchmark details when applicable
    if [ "$check_mode" = "synthesis" ]; then
        show_synthesis_details "$name" "$elapsed_ms"
    fi
}

# ── Banner ──────────────────────────────────────────────────────────
print_header "Kokoro TTS — MCP Smoke Tests"
echo -e "  ${DIM}Target: ${BASE_URL}/mcp${RESET}"

# ── 1. Protocol ─────────────────────────────────────────────────────
print_section "MCP Protocol"

run_mcp_test "Initialize" 200 \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-script","version":"1.0"}}}' \
    --has-result

run_mcp_test "List tools" 200 \
    '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
    --tools-count 3

# ── 2. Tool Calls ──────────────────────────────────────────────────
print_section "Tool Calls"

run_mcp_test "list_voices" 200 \
    '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_voices","arguments":{}}}' \
    --contains "af_heart"

run_mcp_test "synthesize_speech — MP3" 200 \
    '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"synthesize_speech","arguments":{"text":"Hello! This is a test of the MCP speech synthesis tool.","voice":"af_heart","speed":1.0,"format":"mp3"}}}' \
    --synthesis

run_mcp_test "synthesize_speech — IPA" 200 \
    '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"synthesize_speech","arguments":{"text":"The city of (São Paulo)[sˌaʊ̃ pˈaʊ̯lʊ] is beautiful.","voice":"af_heart","speed":1.0,"format":"mp3"}}}' \
    --synthesis

run_mcp_test "synthesize_dialogue" 200 \
    '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"synthesize_dialogue","arguments":{"turns":[{"voice":"af_heart","text":"How are you doing today?"},{"voice":"am_santa","text":"I am doing great, thanks for asking!"}],"speed":1.0,"format":"mp3"}}}' \
    --synthesis

# ── 3. Error Handling ──────────────────────────────────────────────
print_section "Error Handling"

run_mcp_test "Missing text param" 200 \
    '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"synthesize_speech","arguments":{"voice":"af_heart"}}}' \
    --error-contains "'text'"

run_mcp_test "Unknown voice" 200 \
    '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"synthesize_speech","arguments":{"text":"This should fail.","voice":"zz_nonexistent"}}}' \
    --error-contains "not found"

run_mcp_test "Invalid JSON-RPC" 400 \
    'this is not json'

# ── Benchmark Table ─────────────────────────────────────────────────
if [ "${#bench_names[@]}" -gt 0 ]; then
    print_header "Benchmark Results"
    echo ""

    printf "  ${BOLD}${DIM}%-30s %10s %10s %8s${RESET}\n" "Test" "Audio" "Gen time" "RTF"
    printf "  ${DIM}%-30s %10s %10s %8s${RESET}\n" "──────────────────────────────" "──────────" "──────────" "────────"

    for i in "${!bench_names[@]}"; do
        local_audio=$(fmt_time "${bench_audio_ms[$i]}")
        local_gen=$(fmt_time "${bench_gen_ms[$i]}")
        local_rtf="${bench_rtf[$i]}"

        local_rtf_color="$YELLOW"
        if awk -v r="$local_rtf" 'BEGIN { exit !(r >= 1.0) }' 2>/dev/null; then
            local_rtf_color="$GREEN"
        fi

        printf "  %-30s %10s %10s ${local_rtf_color}${BOLD}%7sx${RESET}\n" \
            "${bench_names[$i]}" "$local_audio" "$local_gen" "$local_rtf"
    done

    printf "  ${DIM}%-30s %10s %10s %8s${RESET}\n" "──────────────────────────────" "──────────" "──────────" "────────"

    overall_rtf="--"
    if [ "$bench_total_gen_ms" -gt 0 ]; then
        overall_rtf=$(awk "BEGIN { printf \"%.2f\", $bench_total_audio_ms / $bench_total_gen_ms }")
    fi

    overall_rtf_color="$YELLOW"
    if [ "$bench_total_gen_ms" -gt 0 ] && awk -v r="$overall_rtf" 'BEGIN { exit !(r >= 1.0) }' 2>/dev/null; then
        overall_rtf_color="$GREEN"
    fi

    printf "  ${BOLD}%-30s %10s %10s ${overall_rtf_color}%7sx${RESET}\n" \
        "Total" "$(fmt_time "$bench_total_audio_ms")" "$(fmt_time "$bench_total_gen_ms")" "$overall_rtf"

    echo ""
    echo -e "  ${DIM}RTF \(Real-Time Factor\) = audio duration / generation time${RESET}"
    echo -e "  ${DIM}RTF > 1.0 means faster than real-time${RESET}"
fi

# ── Summary ─────────────────────────────────────────────────────────
total=$((passed + failed))

echo ""
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
if [ "$failed" -eq 0 ]; then
    echo -e "  ${GREEN}${BOLD}All ${total} tests passed${RESET}  ${DIM}($(fmt_time "$total_time_ms") total)${RESET}"
else
    echo -e "  ${RED}${BOLD}${failed}/${total} tests failed${RESET}  ${DIM}($(fmt_time "$total_time_ms") total)${RESET}"
fi
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""

[ "$failed" -eq 0 ]
