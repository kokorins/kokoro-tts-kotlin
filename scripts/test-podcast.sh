#!/usr/bin/env bash
#
# Generates a ~30-minute podcast from kokoro-tts-complete-article.md
# Two hosts discuss the article as a conversational dialogue.
# Sends all turns in a single API request; outputs one audio file URL.
#
# Usage:
#   ./scripts/test-podcast.sh              # test against localhost:8080 (requires AWS session)
#   ./scripts/test-podcast.sh --local      # test against localhost:8080 (no AWS needed)
#   ./scripts/test-podcast.sh --lambda     # auto-resolve Lambda Function URL
#   BASE_URL=https://… ./scripts/test-podcast.sh   # explicit URL
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
HOST_A="af_heart"
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
echo -e "${BOLD}  Kokoro TTS — Podcast Generator (~30 min)${RESET}"
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
        {"voice": "HOST_A", "text": "Welcome to the show! Today we are doing a deep dive into self-hosted text to speech. Specifically, we are going to talk about Kokoro, an open weight model with just 82 million parameters that is producing speech quality close to commercial APIs. The whole story comes from a detailed engineering article about building a production TTS service using Kotlin, Ktor, and ONNX Runtime on AWS."},
        {"voice": "HOST_B", "text": "Yeah, this is a fascinating project. The author was working on a podcast generator for a blog when they discovered Kokoro. They were not trying to cut costs. They were just curious whether a model that small could produce usable speech. And that curiosity turned into a full production service."},
        {"voice": "HOST_A", "text": "What I find really interesting is the economics angle. Cloud TTS charges per request or per character, so the bill grows linearly with usage. Self hosting works differently. You pay for the server, and within its capacity, the cost per request goes down as traffic goes up. The cost grows in steps, not linearly."},
        {"voice": "HOST_B", "text": "Right. And the specific numbers are striking. Cloud TTS ranges from four dollars to nearly two hundred dollars per million characters depending on the provider. Meanwhile, self hosting Kokoro on a small EC2 instance costs about seventy dollars per month, and every additional audio minute is free after that."},

        {"voice": "HOST_A", "text": "So let us talk about why Kokoro can compete with much larger models. The key insight is something called the G2P compression hypothesis. G2P stands for grapheme to phoneme. The idea is simple but powerful."},
        {"voice": "HOST_B", "text": "Think about the word read. Is it read as in I like to read, or read as in I read that yesterday? Same spelling, different sounds. Large models like Higgs Audio, which has nearly six billion parameters, solve this internally. They dedicate a chunk of their capacity to figuring out pronunciation from raw text."},
        {"voice": "HOST_A", "text": "But Kokoro takes a different approach. It converts text to phonemes before feeding it to the model. This lowers the input entropy. The model no longer needs to spend parameters learning pronunciation rules. It can use all of its capacity for the actual speech generation task."},
        {"voice": "HOST_B", "text": "And that is the compression hypothesis in a nutshell. If you handle pronunciation externally, a much smaller model can achieve comparable quality. Kokoro sits at 82 million parameters with phoneme input, compared to billions for end to end models. The quality gap is surprisingly narrow."},
        {"voice": "HOST_A", "text": "The article frames this on a spectrum. At one end you have tiny models like Piper, five to thirty two million parameters, fast but lower quality. At the other end, models with billions of parameters that take raw text. Kokoro found a sweet spot in between."},

        {"voice": "HOST_B", "text": "Now, Kokoro is not built from scratch. It inherits from StyleTTS 2, which was published in 2023 by researchers at Columbia University. The key innovation there was using a diffusion model to generate a style vector that encodes tone, rhythm, and expressiveness."},
        {"voice": "HOST_A", "text": "But Kokoro simplifies that significantly. According to the model card, there is no diffusion in the released model. Instead of generating a fresh style vector for each input, Kokoro uses precomputed voice packs. These are tensor files containing a fixed style representation for each voice."},
        {"voice": "HOST_B", "text": "You lose dynamic style variation per sentence, but you gain simplicity and speed. And the decoder uses something called iSTFTNet from NTT Corporation, which replaces some neural network layers with classical signal processing. That makes waveform generation faster without meaningful quality loss."},
        {"voice": "HOST_A", "text": "So three factors come together. G2P preprocessing keeps the model small. The StyleTTS 2 decoder, trained with adversarial training, ensures natural sounding speech. And the iSTFTNet decoder makes generation efficient. The community then converted it to ONNX format, which is what makes running it on a JVM server possible."},

        {"voice": "HOST_B", "text": "Let us talk about how Kokoro actually performs against the competition. On the TTS Arena V2, which is a blind voting platform on HuggingFace, Kokoro v1 ranks number seventeen overall with an Elo of fourteen ninety eight and a forty five percent win rate based on over three thousand votes."},
        {"voice": "HOST_A", "text": "And here is the important part. It is the highest ranked open weight model on the entire leaderboard. All sixteen models above it are proprietary. That is a remarkable position for an 82 million parameter model."},
        {"voice": "HOST_B", "text": "The article is honest about the limitations though. Kokoro has narrower emotional range, only 54 voices compared to thousands in commercial offerings, and no voice cloning. The claim is not parity with commercial APIs. The claim is that quality is close enough to make the cost tradeoff interesting."},
        {"voice": "HOST_A", "text": "And Kokoro is no longer alone in this weight class. Pocket TTS from Kyutai at 100 million parameters and KittenTTS at 80 million have appeared with open licenses. The pattern of small open models competing with commercial APIs is becoming a real trend."},

        {"voice": "HOST_A", "text": "Alright, let us get into the technical details. The article does a great job of demystifying what is inside the ONNX model file. It expects exactly three inputs and produces one output."},
        {"voice": "HOST_B", "text": "The first input is token IDs. That is the phoneme sequence converted to integers. Each phoneme character maps to a number according to the model vocabulary. The sequence gets padded with zeros at the start and end, and the maximum length is 512 tokens."},
        {"voice": "HOST_A", "text": "The second input is the style vector. This is a row of 256 floats selected from the voice embedding matrix. Each voice has 510 style vectors, one per possible token length, and you pick the row matching your input length. The third input is just the speed, a single float."},
        {"voice": "HOST_B", "text": "And the output is a one dimensional array of floats representing the raw audio waveform at 24 thousand hertz. So every second of speech is 24 thousand samples. A ten second clip would be 240 thousand floats. Pretty straightforward once you understand the tensor shapes."},
        {"voice": "HOST_A", "text": "The article also covers int8 quantization. The full precision model is 310 megabytes. The int8 version is 92 megabytes, roughly four times smaller, and it runs faster because modern CPUs process integer arithmetic more efficiently than floating point. The quality difference is nearly imperceptible."},

        {"voice": "HOST_B", "text": "The voice system is clever. Kokoro ships a single binary file called voices v1 dot bin that contains all voice embeddings. Despite the bin extension, it is actually a ZIP archive of NumPy arrays, one per voice."},
        {"voice": "HOST_A", "text": "Each voice is stored as a three dimensional array with shape 510 by 1 by 256. The 510 rows correspond to different token lengths. When you run inference, you select the row matching your token count. If your phoneme sequence has 47 tokens, you pick row 47."},
        {"voice": "HOST_B", "text": "The naming convention is fun too. The first letter indicates accent, a for American, b for British. The second letter indicates gender, f for female, m for male. So af sarah is an American female voice named Sarah, and bm george is a British male voice named George."},
        {"voice": "HOST_A", "text": "And since there is no Kotlin or Java library for reading NumPy binary format, the article walks through parsing it manually. The format is actually simple. A magic header, a version number, the shape metadata, and then raw binary floats. About 30 lines of Kotlin handles the whole thing."},

        {"voice": "HOST_A", "text": "Now we get to what the article calls the single biggest lever for perceived speech quality: phonemization. This is the process of converting written text into a representation of how it sounds."},
        {"voice": "HOST_B", "text": "The article makes a really important point here. If the phonemizer produces the wrong pronunciation, the model will faithfully generate perfectly clear audio of the wrong word. A mispronounced word is more noticeable to listeners than slightly lower audio fidelity."},
        {"voice": "HOST_A", "text": "There are three flavors of G2P. Lookup is the simplest, just a dictionary mapping words to pronunciations. Rules based systems like espeak apply handwritten pronunciation rules. And neural G2P uses a trained model to predict phonemes."},
        {"voice": "HOST_B", "text": "Misaki, the G2P library built for Kokoro, takes a hybrid approach. It tries dictionary lookup first using four JSON files covering about 190 thousand words. If a word is not found, it falls back to either rules or neural methods depending on configuration."},
        {"voice": "HOST_A", "text": "The phoneme system itself uses 49 characters for English. Capital letters represent diphthongs. A for the vowel in price, I for choice, W for mouth, O for goat. It is compact but expressive enough to capture English pronunciation."},

        {"voice": "HOST_B", "text": "The dictionary merging strategy is interesting. There are four dictionary files: US gold, US silver, GB gold, and GB silver. Gold means high confidence pronunciations, silver means additional entries with slightly lower confidence."},
        {"voice": "HOST_A", "text": "The merge order determines which pronunciation wins when the same word appears in multiple files. US gold takes highest priority, then US silver, then GB gold, then GB silver. In Kotlin, this is handled naturally by the order in which you combine maps."},
        {"voice": "HOST_B", "text": "For words not in any dictionary, the implementation uses a letter to phoneme converter as a fallback. It walks through the word character by character and maps each letter to its most likely phoneme. Not perfect, but reasonable for most English words."},
        {"voice": "HOST_A", "text": "The alternative would be espeak, which has better accuracy but requires a native system library. The article chose simplicity. The dictionary covers the vast majority of real world text, and the letter based fallback handles the rest acceptably."},

        {"voice": "HOST_A", "text": "This is where it gets really practical for backend developers. ONNX Runtime on the JVM means you add a single Gradle dependency and run inference with native Java types. No Python, no PyTorch, no framework installation."},
        {"voice": "HOST_B", "text": "The implementation uses Kotlin lazy delegates to defer session creation until first inference. This keeps startup fast while guaranteeing the session is created exactly once regardless of how many threads hit it simultaneously."},
        {"voice": "HOST_A", "text": "One critical detail for Ktor servers: ONNX inference is blocking CPU bound work. If you run it on Ktor coroutine dispatcher, you block the event loop and stall everything. The solution is wrapping inference calls in withContext Dispatchers IO."},
        {"voice": "HOST_B", "text": "The article also emphasizes native memory management. Every ONNX tensor must be closed after inference to release native memory. The garbage collector cannot reclaim it because it is allocated outside the JVM heap. Leaked tensors lead to out of memory crashes that are hard to diagnose because JVM heap metrics look normal."},
        {"voice": "HOST_A", "text": "That is a great point. Kotlin use blocks, which are the equivalent of Java try with resources, guarantee cleanup even when exceptions occur. The article nests three use blocks for the three input tensors plus one for the session results."},

        {"voice": "HOST_B", "text": "Now let us talk architecture. The author uses hexagonal architecture with four Gradle modules: domain, core, synthesis, and app. The dependency direction flows strictly inward."},
        {"voice": "HOST_A", "text": "Domain contains pure value types with zero external dependencies. Core contains port interfaces and the TTS service orchestration. Synthesis contains all the adapter implementations, ONNX inference, dictionary phonemizer, audio encoder. And app is the Ktor HTTP layer with dependency injection."},
        {"voice": "HOST_B", "text": "What I love about this is that Gradle enforces it at compile time. If someone adds an ONNX Runtime import inside the domain module, the build fails. These are not code review conventions. They are hard guarantees from the build system."},
        {"voice": "HOST_A", "text": "The author identifies three bounded contexts: phonemization, synthesis, and audio delivery. Each has its own vocabulary. The word token means a phoneme character in the phonemization context but an integer ID in the synthesis context. The module boundaries prevent that ambiguity from leaking."},

        {"voice": "HOST_A", "text": "The domain model uses some of my favorite Kotlin features. Value classes provide zero cost type safe wrappers. A VoiceId is a String at runtime but a distinct type at compile time. You cannot accidentally pass a file path where a voice ID is expected."},
        {"voice": "HOST_B", "text": "And the init block validation means that if a SpeechRate instance exists, it is guaranteed to hold a valid value between 0.5 and 2.0. There is no way to construct an invalid one. Validation happens at the earliest possible point."},
        {"voice": "HOST_A", "text": "Sealed interfaces model closed variants beautifully. AudioFormat is either WAV or MP3. VoiceSpec is either a single voice or a blend with weights. Because they are sealed, the compiler forces exhaustive when expressions everywhere."},
        {"voice": "HOST_B", "text": "The error handling is worth highlighting too. SynthesisException is a sealed class with variants for each failure mode: VoiceNotFound, TextTooLong, SpeedOutOfRange, InferenceFailed. Each variant carries diagnostic data, and they are wrapped in Kotlin Result, never thrown."},

        {"voice": "HOST_A", "text": "Let us dig deeper into the cost comparison because the numbers are really interesting. Cloud TTS pricing as of early 2026 spans a huge range. At the low end, Google WaveNet Legacy and Amazon Polly Standard both charge four dollars per million characters."},
        {"voice": "HOST_B", "text": "The neural tier from Google, Amazon, Azure, and OpenAI clusters around fifteen to sixteen dollars per million characters. Premium voices land at thirty dollars. And ElevenLabs sits well above that, with effective rates from sixty to nearly two hundred dollars per million characters."},
        {"voice": "HOST_A", "text": "Using the Kokoro model card conversion of roughly a thousand characters per audio minute, that means cloud TTS costs somewhere between four tenths of a cent and about twenty cents per audio minute. That is a two orders of magnitude spread."},
        {"voice": "HOST_B", "text": "Meanwhile, self hosting on a c7i flex large at seventy dollars per month is a fixed cost. Once the server is running, every additional audio minute is free. At even moderate volume, say a few hours of audio per day, the math shifts heavily in favor of self hosting."},
        {"voice": "HOST_A", "text": "And the key insight is that self hosting costs grow in steps, not linearly. You pay for the server. Within its capacity, cost per request goes down as traffic goes up. You only add another server when you exceed capacity. That is fundamentally different from per request pricing."},

        {"voice": "HOST_B", "text": "I want to go deeper on voice blending because it is technically elegant. Each voice is a matrix of 510 style vectors, each 256 floats. When you request a blended voice like 60 percent heart and 40 percent bella, the system loads both embedding matrices."},
        {"voice": "HOST_A", "text": "Then for the specific token count of your input, it extracts the corresponding row from each voice, multiplies each by its weight, and sums them element by element. The result is a synthetic style vector that the model has never seen during training."},
        {"voice": "HOST_B", "text": "And the remarkable thing is that it works. The model does not know the style vector is synthetic. It just sees 256 floats and generates speech accordingly. The blended voice sounds natural, with characteristics from both source voices."},
        {"voice": "HOST_A", "text": "The validation is elegant too. VoiceSpec dot BlendedVoice requires at least two voices and validates that weights sum to 1.0 within floating point tolerance. If you try to construct an invalid blend, the init block throws immediately. No way to get an invalid blend past the constructor."},

        {"voice": "HOST_B", "text": "Let me walk through how a request flows from HTTP to audio. The Ktor route deserializes the JSON body, calls the use case, and maps the result to an HTTP response. No business logic in the route, just translation."},
        {"voice": "HOST_A", "text": "The use case constructs domain objects from raw input. It builds a SpeechRate from the float, resolves voice names into VoiceSpec instances, and checks for invalid values. If anything fails, the error is captured before any inference runs."},
        {"voice": "HOST_B", "text": "Then TtsService orchestrates the actual synthesis. For each dialogue turn, it splits text into sentences, phonemizes each sentence, runs inference, and collects the raw float samples. Between turns from different speakers, it inserts randomized silence gaps."},
        {"voice": "HOST_A", "text": "The silence gaps are a nice touch. A fixed gap sounds robotic. The TurnGapGenerator produces silence with randomized duration between 250 and 500 milliseconds, scaled inversely with speech speed. It accepts a seeded random for deterministic tests."},
        {"voice": "HOST_B", "text": "Finally, the combined samples go to the audio encoder for WAV or MP3 encoding, then to S3 storage which returns a presigned URL. The route serializes the URL and metadata as JSON. End to end, every architectural layer is touched exactly once in each direction."},

        {"voice": "HOST_A", "text": "The streaming story is elegant. The 510 token limit made sentence splitting unavoidable. Any real paragraph exceeds that limit. But once input is already split into sentences, adding a buffered flow between phonemization and inference was a small step."},
        {"voice": "HOST_B", "text": "The pipeline is a three stage Kotlin flow. Split text into sentences, phonemize each one with a buffer of one ahead, then run inference. That buffer is the key detail. Without it, the pipeline is strictly sequential."},
        {"voice": "HOST_A", "text": "With buffer one, phonemization of sentence N plus one starts while inference of sentence N is still running. For a ten sentence paragraph where each sentence takes 400 milliseconds, time to first audio drops from four seconds to roughly 400 milliseconds."},
        {"voice": "HOST_B", "text": "The article also discusses channelFlow for true HTTP streaming where the producer and consumer run concurrently. And it warns against using conflate with audio, because conflation drops intermediate values. That makes sense for UI updates but creates gaps when those values are audio samples."},

        {"voice": "HOST_A", "text": "Multi speaker dialogue is where this gets really fun. A DialogueTurn pairs a voice spec with a text segment. The request body contains an ordered list of turns, and the service processes them in sequence."},
        {"voice": "HOST_B", "text": "Voice switching happens naturally because each inference call receives its own style vector. The ONNX model produces audio in the requested voice without any warmup or state carryover between turns. The final audio is encoded as a single continuous file."},
        {"voice": "HOST_A", "text": "And you can even blend voices. The article shows how a blended voice works. You load multiple voice embeddings concurrently, multiply each by its weight, and sum them. The ONNX model does not know or care that the style vector is synthetic."},
        {"voice": "HOST_B", "text": "In fact, that is exactly what we are doing right now! My voice in this podcast is a blend of two Kokoro voices. Ten percent Santa and ninety percent Michael. The blending creates a unique voice timbre that neither source voice has on its own."},

        {"voice": "HOST_B", "text": "The deployment story is clean. A multi stage Dockerfile separates the build environment from runtime. The first stage runs Gradle on Amazon Corretto JDK 25 to produce a fat JAR. The second stage copies only the JAR and model files into a slim headless image."},
        {"voice": "HOST_A", "text": "The layer caching strategy is deliberate. Gradle wrapper and build files are copied first, then dependencies are downloaded. This layer is cached as long as build files do not change, which means code changes skip the multi minute dependency download."},
        {"voice": "HOST_B", "text": "They chose to bake the model into the Docker image rather than downloading it at startup. The 92 megabyte cost is a one time addition, but you get deterministic deployments with no runtime dependency on S3 being reachable during startup."},
        {"voice": "HOST_A", "text": "A nice security detail: the container runs as a dedicated non root user. And the health check uses a TCP connection test instead of installing curl, keeping the image smaller. No unnecessary dependencies just for health checking."},

        {"voice": "HOST_A", "text": "The AWS deployment uses ECR for the container registry and EC2 for compute. The instance type is a c7i flex large, which is 2 vCPUs and 4 gigs of RAM. That is enough for the int8 model at reasonable throughput."},
        {"voice": "HOST_B", "text": "What stands out is the use of SSM instead of SSH for all remote operations. No SSH key pairs to manage, no port 22 open to the internet. Every command through SSM is logged in CloudTrail with the IAM identity that executed it."},
        {"voice": "HOST_A", "text": "Common operations become one liners. Pull the latest image through SSM. Restart the container through SSM. Check logs through SSM. You never establish a direct network connection to the machine. The security group only opens port 80 for HTTP."},
        {"voice": "HOST_B", "text": "The deployment script automates everything from scratch. It provisions the S3 bucket, IAM role, ECR repository, security group, and EC2 instance. Every step checks whether the resource already exists, so rerunning the script is safe. That is good infrastructure as code practice."},

        {"voice": "HOST_B", "text": "The performance section introduces a key concept: Real Time Factor, or RTF. It measures how fast the engine generates audio relative to real time playback. An RTF of 0.3 means one second of audio is produced in 0.3 seconds."},
        {"voice": "HOST_A", "text": "But here is the crucial insight about concurrency. OrtSession is thread safe, so multiple threads can call run simultaneously. However, each concurrent inference call competes for the same CPU cores. If a single call takes 400 milliseconds on four cores, four concurrent requests each take roughly four times longer."},
        {"voice": "HOST_B", "text": "Total throughput stays approximately the same because the machine is fully utilized. But per request latency degrades linearly with concurrency. That degradation curve tells you exactly how many requests a single instance can handle at your target latency."},
        {"voice": "HOST_A", "text": "The article references benchmarks from the Kokoros Rust implementation showing that total processing time stays roughly constant as parallelism increases, but time to first audio increases. This suggests memory bandwidth is the bottleneck, not raw CPU compute."},

        {"voice": "HOST_A", "text": "The article covers two important audio post processing steps: peak normalization and silence trimming. Both happen directly on the float array before encoding, adding negligible processing time."},
        {"voice": "HOST_B", "text": "Peak normalization scans the waveform for the loudest sample, then scales everything so the peak reaches a target amplitude, usually 0.95. This gives consistent volume regardless of which voice generated the audio."},
        {"voice": "HOST_A", "text": "Silence trimming removes leading and trailing silent samples from each chunk before concatenation. Without it, micro silences accumulate and make multi sentence output feel sluggish."},
        {"voice": "HOST_B", "text": "There is an interesting tradeoff between per chunk and full waveform normalization. Per chunk ensures consistent volume within each sentence but can create subtle jumps between sentences. Full waveform is smoother but requires all chunks first, which conflicts with streaming. The implementation uses per chunk for streaming and full waveform for batch."},

        {"voice": "HOST_B", "text": "The testing strategy takes full advantage of the clean architecture. Unit tests for phonemization verify known words return expected phoneme strings. They run in milliseconds because no model is loaded."},
        {"voice": "HOST_A", "text": "Domain logic tests use fake implementations of the contract interfaces. The project uses anonymous object expressions, not mocking libraries, which I think is a great pattern. You implement the interface inline with exactly the behavior your test needs."},
        {"voice": "HOST_B", "text": "Integration tests load the real ONNX model and verify the output: non empty, no NaN values, amplitude within bounds, duration roughly matching expectations. The seeded Random in TurnGapGenerator makes silence durations deterministic so assertions do not flake."},
        {"voice": "HOST_A", "text": "And the article makes an honest point: no automated test can tell you whether synthesized speech actually sounds natural. Writing output to a WAV file for manual listening during development is a necessary complement to automated checks."},

        {"voice": "HOST_A", "text": "The final part of the article looks ahead to edge and mobile deployment. Before Kokoro, on device TTS for independent developers meant Piper. Fast but noticeable quality gap. There was nothing in between Piper and cloud APIs."},
        {"voice": "HOST_B", "text": "Kokoro fills that gap. The int8 model is 92 megabytes. The dictionaries compress well. There are no native dependencies beyond ONNX Runtime, which ships prebuilt for Android, iOS, Windows, macOS, and Linux on both ARM and x86."},
        {"voice": "HOST_A", "text": "The clean architecture pays off here. The contract interfaces define exactly where the platform boundary sits. The common module would contain everything platform independent: domain model, interfaces, TTS service, tokenizer, phoneme converter. That is the majority of the codebase."},
        {"voice": "HOST_B", "text": "On Android, the existing JVM code works with minimal changes. ONNX Runtime has an official Android AAR. iOS requires more work because there is no KMP wrapper for ONNX Runtime, so you would need Kotlin Native interop with the C API. But the model and dictionary files are platform independent."},

        {"voice": "HOST_B", "text": "Stepping back, I think the most valuable takeaway from this article is not about TTS specifically. It is about how to structure ML workloads in production backend systems."},
        {"voice": "HOST_A", "text": "Exactly. The hexagonal architecture, the contract interfaces, the compile time enforcement through Gradle modules. These patterns transfer to any ML inference workload. Whether you are running a language model, an image classifier, or a speech synthesizer."},
        {"voice": "HOST_B", "text": "And the broader trend is clear. Small open models are getting good enough that self hosting makes economic sense for many use cases. Not all. The article is careful to say cloud APIs are the right choice for most teams and most projects. But the gap is closing."},
        {"voice": "HOST_A", "text": "The fact that Kokoro is the highest ranked open model on the TTS Arena, beating everything except proprietary services, is a strong signal. And with Pocket TTS and KittenTTS appearing in the same weight class, this is a trend, not a one off."},

        {"voice": "HOST_A", "text": "Alright, let us wrap up. If you are an engineer curious about self hosted ML inference, this article is one of the most thorough practical guides I have seen. It covers research, implementation, architecture, deployment, and production metrics all in one piece."},
        {"voice": "HOST_B", "text": "For me the standout sections are the clean architecture design and the ONNX Runtime integration. The idea that you can run a competitive TTS model on a JVM server with a single Gradle dependency, no Python anywhere, that is powerful."},
        {"voice": "HOST_A", "text": "And the KMP angle for mobile deployment is exciting. Imagine an app that does high quality text to speech entirely on device, no network required, no per request charges. That changes what is possible for independent developers."},
        {"voice": "HOST_B", "text": "The article ends with a line I really like: the only evaluation that matters is yours. Deploy the service, pick a voice, synthesize a paragraph of your own content, and listen. Benchmarks measure what machines can measure. Your ears decide the rest."},
        {"voice": "HOST_A", "text": "Thanks for listening! If you found this interesting, check out the full article and the open source repository. All the code is in Kotlin, but the patterns transfer to any JVM language. Until next time!"}
    ],
    "speed": 1.1,
    "format": "mp3"
}
ENDJSON

# Substitute voice placeholders with actual voice identifiers
REQUEST_BODY="${REQUEST_BODY//HOST_A/$A}"
REQUEST_BODY="${REQUEST_BODY//HOST_B/$B}"

# ── Send request ────────────────────────────────────────────────────
echo -e "  ${DIM}Sending request with 103 dialogue turns...${RESET}"

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

echo -e "  ${GREEN}${BOLD}Podcast generated successfully${RESET}"
echo -e ""
echo -e "  ${BOLD}URL:${RESET}       ${url}"
echo -e "  ${BOLD}Format:${RESET}    ${format}"
echo -e "  ${BOLD}Size:${RESET}      $(fmt_size "$size_bytes")"
echo -e "  ${BOLD}Duration:${RESET}  ~$(fmt_time "$dur_ms")"
echo -e "  ${BOLD}Gen time:${RESET}  $(fmt_time "$elapsed_ms")"
echo -e "  ${BOLD}RTF:${RESET}       ${rtf_color}${BOLD}${rtf}x${RESET}"
echo -e "${BOLD}${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
