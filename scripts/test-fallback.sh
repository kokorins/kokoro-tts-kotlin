#!/usr/bin/env bash
#
# Usage:
#   ./scripts/test-fallback.sh              # test against localhost:8080 (requires AWS session)
#   ./scripts/test-fallback.sh --local      # test against localhost:8080 (no AWS needed)
#   ./scripts/test-fallback.sh --lambda     # auto-resolve Lambda Function URL
#   BASE_URL=https://… ./scripts/test-fallback.sh   # explicit URL
#
set -euo pipefail

# ── Target resolution ─────────────────────────────────────────────
# --local   skips AWS session check (for local storage mode)
# --lambda  auto-resolves the Function URL from AWS
# BASE_URL  env var takes precedence over the default localhost

USE_LAMBDA=false
USE_LOCAL=false
for arg in "$@"; do
    case "$arg" in
        --lambda) USE_LAMBDA=true ;;
        --local)  USE_LOCAL=true ;;
    esac
done

if [ "$USE_LOCAL" = false ]; then
    # ── AWS session check ───────────────────────────────────────────
    if ! aws sts get-caller-identity &>/dev/null; then
        echo "AWS session is not active. Attempting 'aws sso login'..."
        aws sso login
        if ! aws sts get-caller-identity &>/dev/null; then
            echo "ERROR: AWS session is still not active after login. Exiting."
            exit 1
        fi
        echo "AWS session is now active."
    fi
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
print_header "Kokoro TTS — Fallback & OOV Test"
echo -e "  ${DIM}Target: ${BASE_URL}${RESET}"

# ── 1. Suffix stripping (-s, -ed, -ing, -er, -less, -ly) ─────────
print_section "Suffix Stripping"

# -s plural: throughputs (throughput+s), optimizes (optimize+s), embeddings (embedding+s)
run_test "Plural -s" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Higher throughputs and lower bottlenecks mean she optimizes embeddings faster." }],
        "format": "wav"
    }'

# -ed past tense: parallelized (parallelize+d), restructured (restructure+d), outperformed (outperform+ed)
run_test "Past tense -ed" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "They parallelized the workload, restructured the pipeline, and outperformed the baseline." }],
        "format": "wav"
    }'

# -ing progressive: refactoring (refactor+ing), synthesizing (synthesize+ing)
run_test "Progressive -ing" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "She is refactoring the code while synthesizing new test cases." }],
        "format": "wav"
    }'

# -er agent noun: tokenizer (tokenize+er), optimizer (optimize+er)
run_test "Agent noun -er" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The tokenizer and the optimizer run in parallel threads." }],
        "format": "wav"
    }'

# -less privative: serverless (server+less), weightless (weight+less)
run_test "Privative -less" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "A serverless architecture feels almost weightless to deploy." }],
        "format": "wav"
    }'

# -ly adverb: aggressively (aggressive+ly), privately (private+ly)
run_test "Adverb -ly" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "They aggressively scaled the cluster while privately questioning the cost." }],
        "format": "wav"
    }'

# -ies plural: multiplies (multiply+ies), replies (reply+ies), copies (copy+ies)
run_test "Plural -ies" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "It multiplies each by its weight, replies with copies, and applies the new policies." }],
        "format": "wav"
    }'

# -es plural: averages (average+s via stemming), synthesizes (synthesize+s)
run_test "Sibilant plural -es" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The stock market averages show that she synthesizes data from many sources." }],
        "format": "wav"
    }'

# ── 2. Compound word splitting ────────────────────────────────────
print_section "Compound Word Splitting"

# Simple compounds: frontend, backend, webhook, changelog, lifecycle
run_test "Two-part compounds" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The frontend and backend share a webhook. Check the changelog for the full lifecycle." }],
        "format": "wav"
    }'

# Compound + suffix: microservices (micro+service+s), outperformed (out+perform+ed)
run_test "Compound + suffix" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Our microservices outperformed the monolith in every benchmark." }],
        "format": "wav"
    }'

# ── 3. Pure LetterPhonemeConverter fallback ───────────────────────
print_section "LetterPhonemeConverter Fallback"

# Words not in any dict and no compound/suffix match
run_test "Pure fallback words" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The kubernetes cluster ran a miscategorized autoscaling job on the fullstack deployment." }],
        "format": "wav"
    }'

# ── 4. Abbreviation expansion ────────────────────────────────────
print_section "Abbreviation Expansion"

# Pure letter abbreviations: TTS, API, CPU spelled letter-by-letter
run_test "Pure letter abbreviations" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The TTS system uses a CPU to run the API." }],
        "format": "wav"
    }'

# Mixed letter+digit abbreviations: MP3, H2O
run_test "Mixed letter+digit abbreviations" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Convert the file to MP3 and add some H2O to the mix." }],
        "format": "wav"
    }'

# Two-letter abbreviations: AI, OK
run_test "Two-letter abbreviations" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "AI is OK for this use case." }],
        "format": "wav"
    }'

# Abbreviations mixed with regular words and punctuation
run_test "Abbreviations in context" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Our GPU runs ONNX models, the CLI sends JSON over HTTP, and the SDK handles AWS S3 uploads." }],
        "format": "wav"
    }'

# ── 5. Number expansion ───────────────────────────────────────────
print_section "Number Expansion"

# Simple integers: single digit, tens, hundreds
run_test "Simple integers" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "She scored 9 out of 10 on the test and ranked 42 out of 100 applicants." }],
        "format": "wav"
    }'

# Large integers: thousands, millions
run_test "Large integers" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The dataset contains 24000 samples and the model has 82000000 parameters." }],
        "format": "wav"
    }'

# Comma-formatted numbers
run_test "Comma-formatted numbers" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The server handles 1,000 requests per second and stores 24,000 voice samples." }],
        "format": "wav"
    }'

# Decimal numbers: trailing zero dropped, fractional digits spoken
run_test "Decimal numbers" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Set the speed to 2.0 and the threshold to 0.5 for a learning rate of 3.14." }],
        "format": "wav"
    }'

# Numbers mixed with regular words, abbreviations, and punctuation
run_test "Numbers in context" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Kokoro has 82 million parameters, runs at 24000 hertz, and achieves an RTF of 0.3 on a 2 core CPU." }],
        "format": "wav"
    }'

# ── 6. Function words & context ───────────────────────────────────
print_section "Function Words & Context"

# "the" before vowel = ði, before consonant = ðə
# "to" = tə, "a" = ɐ
run_test "Context-aware the" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "The engineer asked the architect to update the API and the endpoint." }],
        "format": "wav"
    }'

# ── 7. Mixed: all fallback paths in one sentence ─────────────────
print_section "Mixed Fallback Paths"

run_test "Mixed: dict + suffix + compound" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Welcome back! Today we are discussing how serverless microservices handle throughputs at scale on kubernetes." }],
        "format": "wav"
    }'

run_test "Mixed: suffix + compound heavy" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Great topic. The tokenizer parallelized across multiple nodes optimizes the full lifecycle from frontend to backend." }],
        "format": "wav"
    }'

run_test "Mixed: compound + context the" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "So the changelog shows they restructured the embeddings layer. How does that affect the webhook latency?" }],
        "format": "wav"
    }'

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