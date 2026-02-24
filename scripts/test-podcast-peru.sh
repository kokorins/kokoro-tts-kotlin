#!/usr/bin/env bash
#
# Generates a ~7-minute podcast about Peru.
# Two hosts discuss Peru's geography, history, culture, and cuisine.
# Sends all turns in a single API request; outputs one audio file URL.
#
# Usage:
#   ./scripts/test-podcast-peru.sh              # test against localhost:8080 (requires AWS session)
#   ./scripts/test-podcast-peru.sh --local      # test against localhost:8080 (no AWS needed)
#   ./scripts/test-podcast-peru.sh --lambda     # auto-resolve Lambda Function URL
#   BASE_URL=https://… ./scripts/test-podcast-peru.sh   # explicit URL
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
    BASE_URL="${LAMBDA_URL%/}"
else
    BASE_URL="${BASE_URL:-http://localhost:8080}"
fi

# ── Voices ────────────────────────────────────────────────────────
HOST_A="am_santa:0.1+af_heart:0.9"
HOST_B="am_santa:0.1+am_michael:0.9"

# ── Colors & symbols ────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# ── Helpers ─────────────────────────────────────────────────────────

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

# ── Banner ──────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}  Kokoro TTS — Peru Podcast (~7 min)${RESET}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "  ${DIM}Target: ${BASE_URL}${RESET}"
echo -e "  ${DIM}Host A: ${HOST_A}${RESET}"
echo -e "  ${DIM}Host B: ${HOST_B}${RESET}"
echo ""

# ══════════════════════════════════════════════════════════════════════
# ALL PODCAST TURNS — single API request
# ══════════════════════════════════════════════════════════════════════

A="$HOST_A"
B="$HOST_B"

read -r -d '' REQUEST_BODY <<'ENDJSON' || true
{
    "turns": [
        {"voice": "HOST_A", "text": "Welcome back to the show! Today we are taking you to (South America)[sˈaʊθ əmˈɛɹɪkə]. We are going to talk about (Peru)[pəɹˈuː], a country that honestly blows my mind every time I read about it. Ancient civilizations, extreme geography, incredible food. (Peru)[pəɹˈuː] has everything."},
        {"voice": "HOST_B", "text": "Absolutely. (Peru)[pəɹˈuː] is one of those places where every layer you peel back reveals something unexpected. Most people know (Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː], and rightfully so, but the country has so much more going on. Let us start with the geography because it sets the stage for everything else."},

        {"voice": "HOST_A", "text": "So (Peru)[pəɹˈuː] sits on the western coast of (South America)[sˈaʊθ əmˈɛɹɪkə], right on the Pacific. It borders (Ecuador)[ˈɛkwədɔːɹ] and (Colombia)[kəlˈʌmbiə] to the north, (Brazil)[bɹəzˈɪl] and (Bolivia)[bəlˈɪviə] to the east, and (Chile)[tʃˈɪli] to the south. And within that footprint you get three completely distinct ecological zones."},
        {"voice": "HOST_B", "text": "Right. The (costa)[kˈɑːstə], the (sierra)[siˈɛɹə], and the (selva)[sˈɛlvə]. The (costa)[kˈɑːstə] is the narrow desert strip along the Pacific coast. (Lima)[lˈiːmə], the capital, sits right there. It almost never rains in (Lima)[lˈiːmə]. The city gets about half an inch of rainfall per year. That makes it one of the driest capital cities on Earth."},
        {"voice": "HOST_A", "text": "Then you move inland and hit the (Andes)[ˈændiːz]. The (sierra)[siˈɛɹə]. These are not gentle mountains. We are talking peaks above 6,000 meters. (Huascaran)[wɑːskəɹˈɑːn], the tallest peak in (Peru)[pəɹˈuː], reaches 6,768 meters. The altitude changes everything: the climate, the agriculture, the culture."},
        {"voice": "HOST_B", "text": "And then east of the (Andes)[ˈændiːz], the land drops into the (Amazon)[ˈæməzɑːn] basin. The (selva)[sˈɛlvə]. About 60 percent of (Peru)[pəɹˈuː] is actually tropical rainforest. Most people do not realize that. (Peru)[pəɹˈuː] contains the second largest portion of the (Amazon)[ˈæməzɑːn] after (Brazil)[bɹəzˈɪl]. The biodiversity there is staggering."},

        {"voice": "HOST_A", "text": "Let us talk history. (Peru)[pəɹˈuː] was the heartland of the (Inca)[ˈɪŋkə] Empire, the largest empire in pre-Columbian America. At its peak in the early 1500s, the empire stretched from southern (Colombia)[kəlˈʌmbiə] all the way down to central (Chile)[tʃˈɪli]. They called it (Tawantinsuyu)[tɑːwɑːntɪnsˈuːjuː], which means the four regions together."},
        {"voice": "HOST_B", "text": "And what is remarkable about the (Inca)[ˈɪŋkə] is how they managed a vast empire without a writing system or wheeled vehicles. Instead they used (quipus)[kˈiːpuːz], these knotted strings that encoded information. Historians are still debating exactly how much data (quipus)[kˈiːpuːz] could represent, whether it was just numbers or something closer to a full record keeping system."},
        {"voice": "HOST_A", "text": "The road network was extraordinary too. Over 40,000 kilometers of paved roads connecting the entire empire. The famous (Inca)[ˈɪŋkə] Trail that tourists hike today to reach (Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː] is just a tiny fragment of that system. They had relay runners called (chasquis)[tʃˈɑːskiːz] who could carry messages across the empire in days."},
        {"voice": "HOST_B", "text": "And then of course the Spanish arrived. (Francisco Pizarro)[fɹɑːnsˈɪskoʊ pɪzˈɑːɹoʊ] and about 170 men captured the (Inca)[ˈɪŋkə] emperor (Atahualpa)[ɑːtəwˈɑːlpə] in 1532. It is one of the most dramatic events in world history. The (Inca)[ˈɪŋkə] had tens of thousands of soldiers and the Spanish had fewer than 200, but they had horses, steel armor, and guns that the (Inca)[ˈɪŋkə] had never encountered."},

        {"voice": "HOST_A", "text": "Now, (Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː]. We have to talk about it. Built around 1450 as a royal estate for the (Inca)[ˈɪŋkə] emperor (Pachacuti)[pɑːtʃəkˈuːti]. It sits at about 2,430 meters elevation on a mountain ridge above the (Urubamba)[uːɹuːbˈɑːmbə] River valley. The Spanish never found it during the conquest, which is why it survived."},
        {"voice": "HOST_B", "text": "The construction is mind boggling. The stones are cut so precisely that you cannot fit a knife blade between them, and they did this without iron tools or mortar. The site has about 200 structures including temples, residences, and agricultural terraces. And the drainage system they built still works today, over 500 years later."},
        {"voice": "HOST_A", "text": "(Hiram Bingham)[hˈaɪɹəm bˈɪŋəm], the American explorer, brought it to international attention in 1911. Although locals always knew it was there. Today about 1.5 million people visit annually. The Peruvian government caps daily visitors to protect the site. You need to book months in advance during peak season."},

        {"voice": "HOST_B", "text": "Let us shift to something I am truly passionate about. Peruvian food. (Lima)[lˈiːmə] is now considered one of the great food capitals of the world. Central, the restaurant run by chef Virgilio Martinez, has been ranked among the top restaurants globally multiple times."},
        {"voice": "HOST_A", "text": "And the star of Peruvian cuisine is ceviche. Raw fish cured in citrus juice, typically lime, with onions, chili peppers, and cilantro. The acid in the lime juice denatures the fish proteins, essentially cooking it. Simple ingredients, but the technique and freshness make it extraordinary."},
        {"voice": "HOST_B", "text": "(Peru)[pəɹˈuː] also gave the world the potato. There are over 3,000 varieties of potato native to (Peru)[pəɹˈuː]. Three thousand! They come in every color: purple, yellow, red, blue. The International Potato Center is headquartered in (Lima)[lˈiːmə] and maintains a gene bank with over 4,500 cultivated potato samples."},
        {"voice": "HOST_A", "text": "And then there is the fusion cuisine. Chifa is Chinese Peruvian food, created by Chinese immigrants in the 19th century. Nikkei is Japanese Peruvian fusion. (Lima)[lˈiːmə] has more chifa restaurants than any other type. These are not niche cuisines. They are part of everyday Peruvian life."},

        {"voice": "HOST_B", "text": "We should talk about the (Nazca)[nˈɑːskə] Lines. These are enormous geoglyphs etched into the desert floor in southern (Peru)[pəɹˈuː]. They depict animals, plants, and geometric shapes. Some are over 300 meters across. You can only really see them from the air, which is what makes them so mysterious."},
        {"voice": "HOST_A", "text": "They were created between 500 BC and 500 AD by the (Nazca)[nˈɑːskə] culture. The dry, windless climate preserved them for over two thousand years. There is a hummingbird, a spider, a monkey, a condor. The precision is incredible. How they achieved that scale without aerial perspective is still debated."},

        {"voice": "HOST_B", "text": "(Peru)[pəɹˈuː] is also home to Lake (Titicaca)[tɪtɪkˈɑːkə], the highest navigable lake in the world at 3,812 meters above sea level. It straddles the border between (Peru)[pəɹˈuː] and (Bolivia)[bəlˈɪviə]. The (Uros)[ˈuːɹoʊz] people live on floating islands made entirely of dried totora reeds. They harvest the reeds, bundle them, and literally build their islands from scratch."},
        {"voice": "HOST_A", "text": "The lake is enormous too. About 8,372 square kilometers, roughly the size of (Puerto Rico)[pwˈɛɹtoʊ ɹˈiːkoʊ]. It is so large that it has its own microclimate, moderating temperatures in the surrounding altiplano. The ancient people around (Titicaca)[tɪtɪkˈɑːkə] believed it was the birthplace of the sun and the origin of the (Inca)[ˈɪŋkə] civilization."},

        {"voice": "HOST_A", "text": "I think what strikes me most about (Peru)[pəɹˈuː] is how much living culture there is. This is not a country where the ancient heritage is just in museums. You see it in daily life. (Quechua)[kˈɛtʃwə], the language of the (Inca)[ˈɪŋkə], is still spoken by about 4 million Peruvians today. It is an official language alongside Spanish."},
        {"voice": "HOST_B", "text": "Exactly. Traditional festivals blend pre-Columbian and Catholic traditions in ways that are completely unique. (Inti Raymi)[ˈɪnti ɹˈaɪmi], the Festival of the Sun, is celebrated every June in (Cusco)[kˈuːskoʊ] with thousands of performers reenacting (Inca)[ˈɪŋkə] ceremonies. It is one of the largest festivals in (South America)[sˈaʊθ əmˈɛɹɪkə]."},
        {"voice": "HOST_A", "text": "Well, I think we have barely scratched the surface today. We did not even get to the (Amazon)[ˈæməzɑːn] biodiversity, the colonial architecture of (Cusco)[kˈuːskoʊ], or the surfing culture on the northern coast. (Peru)[pəɹˈuː] is one of those countries that rewards every bit of curiosity you bring to it."},
        {"voice": "HOST_B", "text": "Completely agree. If this episode sparked your interest, start with the food. Find a Peruvian restaurant near you, order the ceviche, and go from there. (Peru)[pəɹˈuː] has a way of pulling you in once you take that first step. Thanks for listening, everyone!"}
    ],
    "speed": 1.1,
    "format": "mp3"
}
ENDJSON

# Substitute voice placeholders with actual voice identifiers
REQUEST_BODY="${REQUEST_BODY//HOST_A/$A}"
REQUEST_BODY="${REQUEST_BODY//HOST_B/$B}"

# Count turns
turn_count=$(echo "$REQUEST_BODY" | python3 -c "import sys,json; print(len(json.load(sys.stdin)['turns']))")

# ── Send request ────────────────────────────────────────────────────
echo -e "  ${DIM}Sending request with ${turn_count} dialogue turns...${RESET}"

tmpfile=$(mktemp)
start_ns=$(python3 -c 'import time; print(int(time.time()*1e9))')

http_code=$(curl -s -o "$tmpfile" -w "%{http_code}" \
    -X POST "$BASE_URL/v1/tts" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_BODY") || true

end_ns=$(python3 -c 'import time; print(int(time.time()*1e9))')
elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))

body=$(cat "$tmpfile")
rm -f "$tmpfile"

# ── Result ──────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

if [ "$http_code" != "200" ]; then
    echo -e "  ${RED}${BOLD}FAILED${RESET}  ${DIM}HTTP ${http_code}  $(fmt_time "$elapsed_ms")${RESET}"
    echo -e "  ${DIM}${body}${RESET}"
    echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
    echo ""
    exit 1
fi

# Parse response
format=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('format',''))" 2>/dev/null) || true
size_bytes=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('sizeBytes',''))" 2>/dev/null) || true
url=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('url',''))" 2>/dev/null) || true

dur_ms="0"
if [ -n "$size_bytes" ] && [ "$size_bytes" -gt 0 ] 2>/dev/null; then
    dur_ms=$(audio_duration_ms "$size_bytes" "$format")
fi

rtf="--"
rtf_color="$DIM"
if [ "$elapsed_ms" -gt 0 ] && [ "$dur_ms" -gt 0 ]; then
    rtf=$(awk "BEGIN { printf \"%.2f\", $dur_ms / $elapsed_ms }")
    if awk -v r="$rtf" 'BEGIN { exit !(r >= 1.0) }' 2>/dev/null; then
        rtf_color="$GREEN"
    else
        rtf_color="$RED"
    fi
fi

echo -e "  ${GREEN}${BOLD}Peru podcast generated successfully${RESET}"
echo -e ""
echo -e "  ${BOLD}URL:${RESET}       ${url}"
echo -e "  ${BOLD}Format:${RESET}    ${format}"
echo -e "  ${BOLD}Size:${RESET}      $(fmt_size "$size_bytes")"
echo -e "  ${BOLD}Duration:${RESET}  ~$(fmt_time "$dur_ms")"
echo -e "  ${BOLD}Gen time:${RESET}  $(fmt_time "$elapsed_ms")"
echo -e "  ${BOLD}RTF:${RESET}       ${rtf_color}${BOLD}${rtf}x${RESET}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
