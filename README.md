# Kokoro TTS Kotlin

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Ktor](https://img.shields.io/badge/Ktor-3.4.0-087CFA?logo=ktor&logoColor=white)](https://ktor.io)
[![ONNX Runtime](https://img.shields.io/badge/ONNX_Runtime-1.23.2-005CED?logo=onnx&logoColor=white)](https://onnxruntime.ai)
[![JDK](https://img.shields.io/badge/JDK-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![MCP](https://img.shields.io/badge/MCP-0.8.4-green)](https://modelcontextprotocol.io)
[![Coverage](https://img.shields.io/badge/Coverage-86%25-brightgreen)]()

A pure-JVM text-to-speech server powered by the [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) neural TTS model. Runs ONNX inference natively on the JVM — no Python, no external services. Serves a REST API via Ktor and an MCP endpoint for AI assistant integration.

Supports single-voice synthesis, multi-voice dialogue with natural turn gaps, voice blending, inline phoneme annotations for foreign words, and WAV/MP3 output at 24 kHz.

## Installation

### Prerequisites

- **JDK 25+** — download from [Oracle](https://www.oracle.com/java/technologies/downloads/) or install via [SDKMAN!](https://sdkman.io/):
  ```bash
  sdk install java 25-open
  ```
- **curl** — required by the data download script (pre-installed on macOS/Linux)
- **AWS credentials** — configured via environment variables or `~/.aws/credentials` (for S3 audio storage)

### Clone and Setup

```bash
git clone https://github.com/alexsobolev/kokoro-tts-kotlin.git
cd kokoro-tts-kotlin
```

### Download Model Files

The TTS pipeline requires model weights, voice embeddings, and pronunciation dictionaries (~400 MB total). The script skips files that already exist.

```bash
./scripts/download-data.sh
```

This downloads into the `data/` directory:

| File | Size | Description |
|------|------|-------------|
| `kokoro-v1.0.int8.onnx` | ~370 MB | Quantized ONNX TTS model |
| `voices-v1.0.bin` | ~25 MB | Voice style embeddings |
| `config.json` | <1 KB | Tokenizer vocabulary |
| `us_gold.json`, `us_silver.json` | ~2 MB | US English pronunciation dictionaries |
| `gb_gold.json`, `gb_silver.json` | ~2 MB | GB English pronunciation dictionaries |
| `en-pos-perceptron.bin` | ~4 MB | OpenNLP POS tagger model |

### Configure Environment

Set the required S3 bucket for audio storage:

```bash
export AWS_REGION=eu-central-1
export S3_BUCKET=my-tts-bucket
```

See [Configuration](#configuration) for all available settings.

### Build and Verify

```bash
./gradlew build    # Compile, lint, static analysis, and tests
```

## Quick Start

```bash
./gradlew :app:run
```

The server starts on port **8080**. Swagger UI at `/swagger`, OpenAPI spec at `/openapi`.

## API

### Synthesize Speech

```
POST /v1/tts
```

**Single voice:**

```json
{
  "turns": [
    { "voice": "af_heart", "text": "Hello, world!" }
  ],
  "speed": 1.0,
  "format": "wav"
}
```

**Multi-voice dialogue** (turns concatenated with randomized 250-500ms silence gaps):

```json
{
  "turns": [
    { "voice": "af_heart", "text": "How are you today?" },
    { "voice": "am_adam", "text": "I am doing great, thanks for asking!" }
  ],
  "speed": 1.2,
  "format": "mp3"
}
```

**Voice blending** (weighted average of style embeddings, weights must sum to 1.0):

```json
{
  "turns": [
    { "voice": "af_heart:0.6+af_bella:0.4", "text": "A blended voice." }
  ]
}
```

**Inline phoneme annotations** for foreign words and proper nouns:

```json
{
  "turns": [
    { "voice": "af_heart", "text": "We visited (Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː] in (Peru)[pəɹˈuː]." }
  ]
}
```

**Response:**

```json
{
  "url": "https://bucket.s3.region.amazonaws.com/tts-audio/af_heart/uuid.wav",
  "key": "tts-audio/af_heart/uuid.wav",
  "expiresInSeconds": 0,
  "sizeBytes": 48044,
  "format": "wav",
  "voice": "af_heart"
}
```

**Defaults:** `speed` = 1.0, `format` = "wav", `voice` = "af_heart".

**Limits:** speed in [0.5, 2.0], text per turn <= 5,000 characters, at least one turn.

### Other Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Health check (returns `OK`) |
| `GET /v1/voices` | List available voices with IDs and languages |
| `/mcp` | MCP endpoint for AI assistant integration (SSE transport) |
| `/swagger` | Interactive API docs |

### Errors

| Status | Condition |
|--------|-----------|
| 400 | Text too long, speed out of range, empty dialogue, malformed JSON |
| 404 | Voice not found |
| 500 | Inference or storage failure |

## MCP Integration

Both the Ktor server and Lambda expose MCP (Model Context Protocol) endpoints, enabling AI assistants like Claude Desktop to synthesize speech as a tool call.

**Tools:**
- **`list_voices`** — returns all voice IDs and languages
- **`synthesize_speech`** — single-voice synthesis with text, voice, speed, format parameters
- **`synthesize_dialogue`** — multi-turn dialogue with different voices per turn

Tool descriptions instruct LLMs to wrap foreign proper nouns in `(word)[IPA]` annotations for correct pronunciation.

**Testing with MCP Inspector:** connect to `http://localhost:8080/mcp` (SSE transport).

**Claude Desktop** (`~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "kokoro-tts": {
      "command": "npx",
      "args": ["mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

## Architecture

Clean architecture across five Gradle modules:

```
app / lambda  -->  core  -->  domain
         \          ^
          -> infra -/
```

- **domain** — Pure value types (`VoiceId`, `SpeechRate`, `AudioFormat`, `SynthesisException`), zero dependencies
- **core** — Port interfaces (`PhonemeGenerator`, `InferenceEngine`, `AudioEncoder`, `VoiceRepository`, `AudioStorage`), DTOs, use cases, TTS service orchestration
- **infra** — Adapters: ONNX inference, POS-aware G2P (OpenNLP + misaki dictionaries), WAV/MP3 encoding, S3 storage, MCP server factory
- **app** — Ktor HTTP layer with Koin DI composition
- **lambda** — AWS Lambda handler with singleton cold-start initialization

### TTS Pipeline

```
Text --> EnglishPhonemeGenerator --> KokoroTokenizer --> OnnxKokoroEngine --> SentencePostProcessor --> LocalAudioEncoder --> S3AudioStorage
         (POS-aware G2P)            (IPA -> tokens)     (ONNX @ 24kHz)      (volume envelopes)        (WAV/MP3)            (upload + URL)
```

### G2P (Grapheme-to-Phoneme)

`EnglishPhonemeGenerator` converts English text to IPA phonemes using a POS-aware hybrid approach matching [misaki](https://github.com/hexgrad/misaki)'s logic. Four misaki JSON dictionaries are merged at startup (US gold > US silver > GB gold > GB silver) into a `PosAwareLexicon` that preserves per-POS pronunciation variants (e.g., "live" as adjective `lˈIv` vs verb `lˈɪv`, "record" as noun `ɹˈɛkəɹd` vs verb `ɹəkˈɔɹd`). A `lexicon_fixes.json` corrections file is deep-merged on top with the highest priority, fixing 3 upstream misaki VBP bugs (read, reread, wound had present-tense VBP mapped to past-tense pronunciations).

**Two-pass pipeline:**

1. **First pass** — POS-tag all tokens with [Apache OpenNLP](https://opennlp.apache.org/) (perceptron model, Penn Treebank tags, original case preserved for tagger accuracy), resolve phonemes via POS-aware dictionary lookup with morphological stemming and letter-rule fallback
2. **Second pass** — Reverse-scan phonemes to compute `futureVowel` context, apply function word overrides ("the" → `ði`/`ðə`, "to" → `tʊ`/`tə`/`tu`), re-lookup sentence-final words with stressed `None`-key variants

**Word resolution** (in priority order):

1. **Inline phoneme annotations** — `(word)[IPA]` syntax bypasses the entire G2P pipeline
2. **Contraction expansion** — e.g., "I'm" is expanded to "I am" before phonemization
3. **Context-sensitive function words** — "the" uses `ði` before vowels / `ðə` before consonants; "to" uses `tʊ` before vowels / `tə` before consonants / `tu` at sentence end
4. **Abbreviations** — words with 2+ uppercase letters spelled out letter-by-letter
5. **Number expansion** — integers, decimals, leading-zero sequences expanded to words
6. **POS-aware dictionary lookup** — selects correct variant based on POS tag with parent-tag normalization (VBD→VERB, NN→NOUN, JJ→ADJ, RB→ADV) and sentence-final stressed forms via `None` key
7. **Morphological stemming** — plurals, past tense, progressive, adverbial, agent, privative suffixes with US English T-flapping (stem-final 't' → 'ɾ' before vowels in -ed/-ing)
8. **Compound word splitting** — tries all split positions (min 3 chars), demotes second-part stress
9. **Letter-to-phoneme fallback** — ~100 English grapheme patterns matched greedily with context-sensitive vowel rules

### Intonation Post-Processing

The Kokoro model doesn't strongly differentiate intonation by punctuation. `TtsService` and `SentencePostProcessor` compensate:

- **Questions (`?`)** — 0.92x speed; rising volume ramp (1.0 -> 1.15x, quadratic) on last 600ms. Multi-clause questions split at the last clause boundary
- **Exclamations (`!`)** — gain boost (1.20x -> 1.0, linear fade) on first 400ms
- **Statements** — unmodified

RMS-windowed speech boundary detection ensures volume effects target voiced content, not model-generated silence.

### Voice Blending

Blended voices (e.g., `af_heart:0.6+af_bella:0.4`) are created by weighted averaging of 256-dimensional style embeddings. After blending, the result vector is L2-renormalized to the weighted average of input norms — without this, blended vectors have smaller magnitude and produce degraded audio.

### ONNX Inference

- Lazy session loading on first call, not at startup
- Phoneme sequences truncated to 510 tokens (model's 512 context window minus BOS/EOS)
- 10ms fade-in/fade-out on every segment to eliminate click artifacts at boundaries
- All available CPU threads via `setIntraOpNumThreads()`

## Deployment

### Docker (Ktor server)

```bash
docker build -t kokoro-tts .
docker run -p 8080:8080 \
  -e AWS_REGION=eu-central-1 \
  -e S3_BUCKET=my-tts-bucket \
  kokoro-tts
```

Non-root user, 3 GB heap, `ExitOnOutOfMemoryError`. Multi-stage build with Gradle dependency caching.

### Lambda

```bash
docker build -f Dockerfile.lambda -t kokoro-tts-lambda .
```

Custom JDK 25 runtime (beyond AWS managed runtimes), 8 GB heap for ONNX model. Koin DI initializes once per container cold start. Auto-detects and decodes base64 request bodies from Lambda Function URLs.

## Configuration

`app/src/main/resources/application.yaml`:

```yaml
tts:
  tokenizer:
    configPath: "data/config.json"
  voices:
    path: "data/voices-v1.0.bin"
  phonemizer:
    goldDictPath: "data/us_gold.json"
    silverDictPath: "data/us_silver.json"
    gbGoldDictPath: "data/gb_gold.json"
    gbSilverDictPath: "data/gb_silver.json"
    fixesDictPath: "data/lexicon_fixes.json"
  pos:
    modelPath: "data/en-pos-perceptron.bin"
  model:
    onnxPath: "data/kokoro-v1.0.int8.onnx"
  aws:
    region: "$AWS_REGION:eu-central-1"
    s3Bucket: "$S3_BUCKET:tts-audio-default"
  storage:
    prefix: "$STORAGE_PREFIX:tts-audio"
```

All settings support environment variable overrides using Ktor's `$ENV_VAR:default` syntax. The Lambda handler reads the same settings from environment variables directly.

| Variable | Default | Description |
|----------|---------|-------------|
| `AWS_REGION` | `eu-central-1` | AWS region for S3 |
| `S3_BUCKET` | — | S3 bucket for audio storage |
| `STORAGE_PREFIX` | `tts-audio` | S3 object key prefix |

## Model Files

Run `./scripts/download-data.sh` to fetch all required files into `data/`. The script skips existing files.

| File | Source | Description |
|------|--------|-------------|
| `kokoro-v1.0.int8.onnx` | [kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0) | Quantized Kokoro TTS model |
| `voices-v1.0.bin` | [kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx/releases/tag/model-files-v1.0) | Voice embedding weights (NPZ) |
| `config.json` | [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | Tokenizer vocabulary |
| `us_gold.json`, `us_silver.json` | [misaki](https://github.com/hexgrad/misaki) | US English pronunciation dictionaries |
| `gb_gold.json`, `gb_silver.json` | [misaki](https://github.com/hexgrad/misaki) | GB English pronunciation dictionaries |
| `lexicon_fixes.json` | Local | Pronunciation corrections for upstream misaki VBP bugs |
| `en-pos-perceptron.bin` | [Apache OpenNLP](https://opennlp.sourceforge.net/models-1.5/) | English POS tagger model (3.9 MB, perceptron) |

## Building

```bash
./gradlew build              # Compile + ktlint + detekt + tests
./gradlew :app:run           # Dev server on port 8080
./gradlew buildFatJar        # Fat JAR at app/build/libs/app-all.jar
./gradlew ktlintFormat       # Auto-format code
./gradlew koverHtmlReport    # Merged code coverage report → build/reports/kover/html/
./gradlew koverXmlReport     # Merged XML coverage report (for CI)
```

### Code Quality

The build runs **ktlint** (formatting), **detekt** (static analysis), and all tests as a single gate. **Kover** enforces a minimum **85%** line coverage across all five modules with merged reporting at the root level. All tests follow the given-when-then pattern with `// given`, `// when`, `// then` section comments.

See [GUIDELINES.md](GUIDELINES.md) for detailed coding conventions, architecture rules, and design decisions.

## Tech Stack

Kotlin 2.3.0, JDK 25, Ktor 3.4.0, Koin 4.1.1, ONNX Runtime 1.23.2, Apache OpenNLP 2.5.3, kotlinx.serialization 1.8.1, AWS SDK for Kotlin 1.6.12, MCP SDK 0.8.4, jump3r (LAME MP3 encoder).