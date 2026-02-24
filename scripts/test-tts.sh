#!/usr/bin/env bash
#
# Usage:
#   ./scripts/test-tts.sh              # test against localhost:8080 (requires AWS session)
#   ./scripts/test-tts.sh --local      # test against localhost:8080 (no AWS needed)
#   ./scripts/test-tts.sh --lambda     # auto-resolve Lambda Function URL
#   BASE_URL=https://… ./scripts/test-tts.sh   # explicit URL
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
print_header "Kokoro TTS — Smoke Tests"
echo -e "  ${DIM}Target: ${BASE_URL}${RESET}"

# ── 1. Health & Voices ──────────────────────────────────────────────
print_section "Health & Discovery"

run_test "Health check" 200 \
    "$BASE_URL/health"

run_test "List voices" 200 \
    "$BASE_URL/v1/voices"

# ── 2. Single-voice synthesis ───────────────────────────────────────
print_section "Single-Voice Synthesis"

run_test "Single turn — WAV" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Hello! This is a single voice WAV test." }],
        "format": "wav"
    }'

run_test "Single turn — MP3" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "And this one tests MP3 encoding." }],
        "format": "mp3"
    }'

run_test "Custom speed (1.5x)" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "This is spoken at one and a half times normal speed." }],
        "speed": 1.5,
        "format": "wav"
    }'

# ── 3. Voice blending ──────────────────────────────────────────────
print_section "Voice Blending"

run_test "Blended voice (60/40)" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart:0.6+af_bella:0.4", "text": "This voice is a blend of two speakers." }],
        "format": "wav"
    }'

# ── 4. Multi-turn dialogue ─────────────────────────────────────────
print_section "Multi-Turn Dialogue"

run_test "2-turn dialogue" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [
            { "voice": "af_heart", "text": "How are you doing today?" },
            { "voice": "am_santa", "text": "I am doing great, thanks for asking!" }
        ],
        "format": "wav"
    }'

run_test "12-turn podcast dialogue" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Welcome back to Deep Dive, the podcast where we explore the ideas shaping our future. I am your host, and today we have a fascinating topic." },
            { "voice": "am_santa", "text": "Thanks for having me! I have been looking forward to this conversation all week. Shall we jump right in?" },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Absolutely. So today we are talking about synthetic speech. It has come a long way from robotic monotone to something almost indistinguishable from a real person." },
            { "voice": "am_santa", "text": "Right, and the key breakthrough was neural vocoders. Instead of stitching together recorded phonemes, we now generate raw audio waveforms from learned representations." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "That is a great point. And one thing listeners might not realize is that voice blending is possible. You can mix two voice profiles to create an entirely new timbre." },
            { "voice": "am_santa", "text": "Exactly. You take the style embeddings from two speakers, apply weighted averages, and the result sounds natural. It is not just interpolation, the model adapts." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Now what about the practical applications? Where do you see this technology heading in the next few years?" },
            { "voice": "am_santa", "text": "Accessibility is huge. People who have lost their voice can bank a few minutes of speech and get a personalized synthetic voice. Audiobooks and podcasts can scale without studios." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "And the ethical side? There are real concerns about deepfakes and impersonation. How do we handle that responsibly?" },
            { "voice": "am_santa", "text": "Watermarking and provenance tracking are essential. Every generated clip should carry metadata proving it is synthetic. Regulation is catching up too, which is encouraging." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Great insights. Before we wrap up, any advice for developers who want to experiment with text to speech in their own projects?" },
            { "voice": "am_santa", "text": "Start with an open model like Kokoro. It runs on CPU with ONNX, the quality is impressive, and you can integrate it into any stack. Just be mindful of the ethical guidelines." }
        ],
        "speed": 1.0,
        "format": "mp3"
    }'

run_test "48-turn podcast — The Hidden Science of Everyday Life" 200 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Welcome back to Curiosity Unlocked! I am your host Elena, and today we are diving into the hidden science behind everyday things you never think about." },
            { "voice": "am_santa", "text": "Happy to be here, Elena. I am Doctor James Hartwell, physicist and recovering academic. I left the ivory tower to explain science to normal humans." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Perfect. So let us start with something everyone does every morning. You boil water for coffee. Simple, right? Except it is not simple at all." },
            { "voice": "am_santa", "text": "Not even close. When you watch a pot of water heat up, those first tiny bubbles on the bottom? Those are not boiling. That is dissolved gas escaping because warm water holds less dissolved air." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Wait, so the bubbles I see early on are not steam?" },
            { "voice": "am_santa", "text": "Nope. Real boiling starts when the water hits a hundred degrees Celsius at sea level. Then you get nucleation, where vapor bubbles form at tiny imperfections in the pot. Those scratches on your old kettle actually help water boil more evenly." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "So a brand new perfectly smooth pot would behave differently?" },
            { "voice": "am_santa", "text": "It can superheat! The water goes past a hundred degrees without boiling because there are no nucleation sites. Then you drop in a spoon or a teabag and boom, explosive boiling. It is actually dangerous with microwaved water in smooth ceramic mugs." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "That is terrifying. Okay, let us move to something calmer. Why does toast smell so good? What is actually happening chemically?" },
            { "voice": "am_santa", "text": "Ah, the Maillard reaction! It is not caramelization, that is a common mistake. The Maillard reaction is between amino acids and reducing sugars. It happens around a hundred and forty degrees Celsius." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "And it creates hundreds of different flavor compounds, right?" },
            { "voice": "am_santa", "text": "Hundreds! Each combination of amino acid and sugar produces a different set of volatile molecules. That is why toast smells different from grilled steak, even though both are Maillard reactions. The starting ingredients determine the output." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "So when a chef talks about getting a good sear on a steak, they are really managing chemistry." },
            { "voice": "am_santa", "text": "Exactly. High heat, dry surface, proper salt timing. All of it optimizes the Maillard reaction. If the surface is wet, the water keeps the temperature at a hundred degrees and you get steaming instead of browning." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Let us shift gears. I want to talk about something we all take for granted. Glass. We look through it every day, but it is genuinely one of the strangest materials in existence." },
            { "voice": "am_santa", "text": "Oh, glass is wild. Here is the thing people do not realize. Glass is not a true solid. It is an amorphous solid, meaning its molecules are disordered like a liquid, but they are frozen in place." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "I have heard people say glass flows over time. That old windows are thicker at the bottom. Is that true?" },
            { "voice": "am_santa", "text": "That is a myth! Old windows are thicker at the bottom because of how they were manufactured. Crown glass was spun into discs and cut into panes. The thicker edge was placed at the bottom for stability. Glass does not flow at room temperature on any human timescale." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Oh wow, I have been telling people that myth for years. What about the transparency? Why can we see through glass but not through a brick?" },
            { "voice": "am_santa", "text": "Great question. It comes down to electron energy gaps. In glass, the gap between electron energy levels is larger than the energy of visible light photons. So the photons pass right through. Bricks have smaller gaps that absorb visible light." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "So transparency is not about density, it is about quantum mechanics?" },
            { "voice": "am_santa", "text": "Exactly! Diamond is denser than glass but perfectly transparent. Water is less dense but also transparent. It is all about the electronic structure, not how tightly packed the atoms are." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Alright, here is one that keeps me up at night. How does a microwave actually heat food? I know it is not cooking from the inside out." },
            { "voice": "am_santa", "text": "Another great myth to bust. Microwaves do not heat from the inside out. The magnetron generates electromagnetic waves at two point four five gigahertz. These waves penetrate food about one to two centimeters deep." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "So the inside of a thick piece of food heats by conduction, just like in a regular oven?" },
            { "voice": "am_santa", "text": "Yes! And here is the interesting part. People think microwaves vibrate water molecules. That is oversimplified. The oscillating electric field causes polar molecules to rotate, trying to align with the field. That molecular friction generates heat." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Is that why a completely dry plate does not get hot in the microwave?" },
            { "voice": "am_santa", "text": "Exactly right. A dry ceramic plate has no polar molecules to rotate. It stays cool while the wet food on it heats up. The plate only gets hot from the food transferring heat back into it." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Let us talk about something everyone wonders. Why is the sky blue? And please do not just say Rayleigh scattering and leave it at that." },
            { "voice": "am_santa", "text": "Ha! Fair enough. So sunlight contains all colors. When it hits our atmosphere, the tiny nitrogen and oxygen molecules scatter shorter wavelengths much more than longer ones. Blue light scatters roughly ten times more than red." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "But violet has an even shorter wavelength than blue. So why is the sky not violet?" },
            { "voice": "am_santa", "text": "Two reasons. First, the sun emits less violet light than blue. Second, our eyes are much more sensitive to blue than violet. So even though violet scatters more, our biology makes us perceive the sky as blue." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "So if we had different eyes, we might see a violet sky?" },
            { "voice": "am_santa", "text": "If you could see ultraviolet like some birds and insects can, the sky would look completely different. Bees literally see patterns in flowers that are invisible to us. Their sky probably looks violet or even deeper." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "That is mind bending. Okay, rapid fire section. Why do we get static shocks in winter?" },
            { "voice": "am_santa", "text": "Dry air is a poor conductor. In summer, humidity lets charge dissipate gradually. In dry winter air, charge builds up on your body and discharges all at once when you touch metal. That spark is a tiny lightning bolt." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Why does hot water freeze faster than cold water sometimes?" },
            { "voice": "am_santa", "text": "The Mpemba effect! It is still debated. Leading theories involve evaporation reducing the volume of hot water, dissolved gases escaping, or convection currents distributing heat differently. It does not always happen, which makes it tricky to study." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Why do knuckles crack?" },
            { "voice": "am_santa", "text": "Cavitation! When you pull the joint apart, the pressure in the synovial fluid drops and dissolved gases form a bubble. The crack sound is the bubble forming, not popping. Recent MRI studies confirmed this. And no, it does not cause arthritis." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "Relief for knuckle crackers everywhere. Why does music give us chills?" },
            { "voice": "am_santa", "text": "Your brain releases dopamine in anticipation of a musical peak. The chill, called frisson, happens when the auditory cortex communicates with the reward system. People with more nerve fibers connecting these regions experience it more intensely." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "So some people are literally wired to feel music more deeply?" },
            { "voice": "am_santa", "text": "Yes! Studies show about two thirds of people experience frisson regularly. The rest rarely or never do. It correlates with a personality trait called openness to experience, and with higher emotional engagement generally." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "That is fascinating. One last big topic before we wrap up. Sleep. Why do we need it? After all these years of research, do we actually know?" },
            { "voice": "am_santa", "text": "We know more than ever, but the full picture is still emerging. During deep sleep, your brain activates the glymphatic system. Cerebrospinal fluid flushes through brain tissue and clears metabolic waste, including amyloid beta, which is linked to Alzheimer disease." },
            { "voice": "af_heart:0.6+af_bella:0.4", "text": "So sleep is literally washing your brain?" },
            { "voice": "am_santa", "text": "Precisely. Your brain cells actually shrink during sleep, opening gaps for fluid to flow through. It is like a city doing street cleaning at night when traffic is low. And this only happens effectively during deep non-REM sleep." }
        ],
        "speed": 1.0,
        "format": "mp3"
    }'

# ── 5. Error cases ─────────────────────────────────────────────────
print_section "Error Handling"

run_test "Unknown voice  -> 500" 500 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "zz_nonexistent", "text": "This should fail." }]
    }'

run_test "Empty dialogue -> 400" 400 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{ "turns": [] }'

run_test "Speed too low  -> 400" 400 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Too slow." }],
        "speed": 0.1
    }'

run_test "Speed too high -> 400" 400 \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d '{
        "turns": [{ "voice": "af_heart", "text": "Too fast." }],
        "speed": 5.0
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
