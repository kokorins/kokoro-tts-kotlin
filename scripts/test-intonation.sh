#!/usr/bin/env bash
#
# Intonation post-processing listening test.
# Generates side-by-side audio for statements, questions, and exclamations
# so you can hear the pitch rise and emphasis effects.
#
# Usage:
#   ./scripts/test-intonation.sh              # test against localhost:8080
#   ./scripts/test-intonation.sh --lambda     # auto-resolve Lambda Function URL
#   BASE_URL=https://… ./scripts/test-intonation.sh   # explicit URL
#
set -euo pipefail

# ── Target resolution ─────────────────────────────────────────────
# --lambda flag auto-resolves the Function URL from AWS.
# BASE_URL env var takes precedence over the default localhost.

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
    # Strip trailing slash so BASE_URL/path works correctly
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

# Format milliseconds to human string
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

# Format file size to human string
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

# Compute audio duration in milliseconds from size and format.
#   WAV:  24 kHz, 16-bit mono, 44-byte header → 48000 bytes/sec = 48 bytes/ms
#   MP3:  128 kbps CBR → 16000 bytes/sec = 16 bytes/ms
audio_duration_ms() {
    local size="$1" fmt="$2"
    case "$fmt" in
        wav) awk "BEGIN { printf \"%d\", ($size - 44) / 48.0 }" ;;
        mp3) awk "BEGIN { printf \"%d\", $size * 8.0 / 128 }" ;;
        *)   echo "0" ;;
    esac
}

# Run a test case.
#   $1  test name
#   $2  expected HTTP status
#   $3+ curl args (method, headers, data …)
run_test() {
    local name="$1" expected="$2"
    shift 2

    local tmpfile
    tmpfile=$(mktemp)

    local start_ns end_ns
    start_ns=$(python3 -c 'import time; print(int(time.time()*1e9))')

    local http_code
    http_code=$(curl -s -o "$tmpfile" -w "%{http_code}" "$@") || true

    end_ns=$(python3 -c 'import time; print(int(time.time()*1e9))')

    local elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
    total_time_ms=$((total_time_ms + elapsed_ms))

    local body
    body=$(cat "$tmpfile")
    rm -f "$tmpfile"

    if [ "$http_code" = "$expected" ]; then
        passed=$((passed + 1))
        echo -e "  ${PASS}  ${name}  ${DIM}HTTP ${http_code}  $(fmt_time "$elapsed_ms")${RESET}"
    else
        failed=$((failed + 1))
        echo -e "  ${FAIL}  ${name}  ${DIM}HTTP ${http_code} (expected ${expected})  $(fmt_time "$elapsed_ms")${RESET}"
        echo -e "        ${DIM}${body}${RESET}"
    fi

    # Print response details for successful TTS calls
    if [ "$http_code" = "$expected" ] && echo "$body" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
        local format size_bytes voice url
        format=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('format',''))" 2>/dev/null) || true
        size_bytes=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('sizeBytes',''))" 2>/dev/null) || true
        voice=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('voice',''))" 2>/dev/null) || true
        url=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('url',''))" 2>/dev/null) || true

        if [ -n "$size_bytes" ] && [ "$size_bytes" != "" ] && [ "$size_bytes" -gt 0 ] 2>/dev/null; then
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

            # Accumulate for benchmark table
            bench_names+=("$name")
            bench_audio_ms+=("$dur_ms")
            bench_gen_ms+=("$elapsed_ms")
            bench_rtf+=("$rtf")
            bench_total_audio_ms=$((bench_total_audio_ms + dur_ms))
            bench_total_gen_ms=$((bench_total_gen_ms + elapsed_ms))
        fi
    fi
}

# ── Banner ──────────────────────────────────────────────────────────
print_header "Kokoro TTS — Intonation Post-Processing Test"
echo -e "  ${DIM}Target: ${BASE_URL}${RESET}"

# ── 1. Statement vs Question — same text ────────────────────────────
print_section "Statement vs Question (same text)"

run_test "Statement: Are you sure about this." 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Are you sure about this." }],
        "format": "wav"
    }'

run_test "Question: Are you sure about this?" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Are you sure about this?" }],
        "format": "wav"
    }'

# ── 2. Statement vs Exclamation — same text ─────────────────────────
print_section "Statement vs Exclamation (same text)"

run_test "Statement: That is incredible." 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "That is incredible." }],
        "format": "wav"
    }'

run_test "Exclamation: That is incredible!" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "That is incredible!" }],
        "format": "wav"
    }'

# ── 3. Multi-clause questions (speed ramp on last clause) ───────────
print_section "Multi-Clause Questions (speed ramp + pitch rise)"

run_test "Multi-clause question" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "So after all that work, you still think it was worth it?" }],
        "format": "wav"
    }'

run_test "Multi-clause statement" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "So after all that work, you still think it was worth it." }],
        "format": "wav"
    }'

run_test "Multi-clause question 2" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "If the model is that good, why does nobody use it?" }],
        "format": "wav"
    }'

# ── 4. Short questions ──────────────────────────────────────────────
print_section "Short Questions (pitch rise only, no speed ramp)"

run_test "Short question: Really?" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Really?" }],
        "format": "wav"
    }'

run_test "Short statement: Really." 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Really." }],
        "format": "wav"
    }'

run_test "Short question: What?" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "What?" }],
        "format": "wav"
    }'

# ── 5. Exclamation emphasis ─────────────────────────────────────────
print_section "Exclamation Emphasis"

run_test "Exclamation: Stop right there!" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Stop right there!" }],
        "format": "wav"
    }'

run_test "Statement: Stop right there." 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Stop right there." }],
        "format": "wav"
    }'

run_test "Exclamation: I can not believe it!" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "I can not believe this is working!" }],
        "format": "wav"
    }'

run_test "Statement: I can not believe it." 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "I can not believe this is working." }],
        "format": "wav"
    }'

# ── 6. Mixed dialogue with all three ────────────────────────────────
print_section "Mixed Dialogue (statements, questions, exclamations)"

run_test "Mixed dialogue — 6 turns" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [
            { "voice": "af_heart", "text": "I just finished the prototype. Want to see it?" },
            { "voice": "am_adam", "text": "Absolutely! Show me what you have got!" },
            { "voice": "af_heart", "text": "So when the user speaks, the model predicts the next token in real time." },
            { "voice": "am_adam", "text": "Wait, it runs that fast? How is that even possible?" },
            { "voice": "af_heart", "text": "We optimized the inference pipeline. The latency dropped by sixty percent." },
            { "voice": "am_adam", "text": "That is amazing! We need to ship this immediately!" }
        ],
        "format": "wav"
    }'

# ── 7. Different voice — verify effects work across voices ──────────
print_section "Different Voice (am_adam)"

run_test "Male statement" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "am_adam", "text": "The experiment was a success." }],
        "format": "wav"
    }'

run_test "Male question" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "am_adam", "text": "Was the experiment a success?" }],
        "format": "wav"
    }'

run_test "Male exclamation" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "am_adam", "text": "The experiment was a success!" }],
        "format": "wav"
    }'

# ── 8. Same phrase, all three intonations in one file ────────────────
print_section "Same Phrase Triple (statement . / question ? / exclamation !)"

run_test "You knew — ./?/!" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [
            { "voice": "af_heart", "text": "You knew about this the whole time." },
            { "voice": "af_heart", "text": "You knew about this the whole time?" },
            { "voice": "af_heart", "text": "You knew about this the whole time!" }
        ],
        "format": "wav"
    }'

run_test "It works — ./?/!" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [
            { "voice": "af_heart", "text": "It actually works." },
            { "voice": "af_heart", "text": "It actually works?" },
            { "voice": "af_heart", "text": "It actually works!" }
        ],
        "format": "wav"
    }'

# ── Benchmark Table ─────────────────────────────────────────────────
if [ "${#bench_names[@]}" -gt 0 ]; then
    print_header "Benchmark Results"
    echo ""

    # Table header
    printf "  ${BOLD}${DIM}%-30s %10s %10s %8s${RESET}\n" "Test" "Audio" "Gen time" "RTF"
    printf "  ${DIM}%-30s %10s %10s %8s${RESET}\n" "──────────────────────────────" "──────────" "──────────" "────────"

    # Table rows
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

    # Totals
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
