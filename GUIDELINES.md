# Development Guidelines

Code practices, design rules, and architectural conventions for the Kokoro TTS project.

## Architecture

Clean architecture across five Gradle modules with a strict dependency direction:

```
app / lambda  →  core  →  domain
         ↓        ↑
        infra
```

| Module     | Role                                                                                         | Depends on          |
|------------|----------------------------------------------------------------------------------------------|---------------------|
| **domain** | Pure value types (`VoiceId`, `SpeechRate`, `AudioFormat`, `SynthesisException`), no frameworks | nothing             |
| **core**   | API interfaces (`core.api`), DTOs (`core.dto`), use cases, `TtsService` orchestration        | `:domain`           |
| **infra**  | Adapter implementations (ONNX, G2P, WAV/MP3, S3, MCP)                                       | `:core`             |
| **app**    | Ktor HTTP layer, Koin DI composition                                                         | `:core`, `:infra`   |
| **lambda** | AWS Lambda handler                                                                           | `:core`, `:infra`   |

Dependencies only point inward. `domain` and `core` never reference `infra`, `app`, or `lambda`.

## Visibility

Implementation classes in each module should be marked `internal` to limit their visibility to the module boundary. Only interfaces and types that other modules depend on remain `public`.

In practice, most `core.api` interfaces are public (they define the contract), while all `infra` adapter classes (`OnnxKokoroEngine`, `EnglishPhonemeGenerator`, `S3AudioStorage`, etc.) are `internal` because nothing outside `:infra` references them directly — Koin wires them up by interface.

A class exposed in a public constructor cannot be `internal`. For example, `TtsService` is public and takes `SentencePostProcessor` and `TurnGapGenerator` as constructor parameters, so those must also remain public.

## Dependency Injection

Each module has a `Module.kt` with Koin registrations. The entry-point modules (`:app`, `:lambda`) compose them into a single Koin application.

All non-trivial dependencies are constructor-injected. Even small helper classes like `NumberExpander` and `LetterPhonemeRules` go through Koin rather than being instantiated inline, so they can be swapped in tests.

```kotlin
// infra/Module.kt — register implementations by interface
fun infraModule(config: InfraConfig) =
    module {
        single { LetterPhonemeRules() }
        single { LetterPhonemeConverter(get()) }
        single { NumberExpander() }
        single { PosAwareLexicon(config.goldDictPath, config.silverDictPath, config.gbGoldDictPath, config.gbSilverDictPath) }
        single<PosTagger> { OpenNlpPosTagger(config.posModelPath) }
        single<PhonemeGenerator> {
            EnglishPhonemeGenerator(lexicon = get(), fallback = get(), numberExpander = get(), posTagger = get())
        }
    }
```

Route DI: `configureRouting()` resolves Koin dependencies at the `Application` scope and passes them into route handlers — no `application.get()` inside individual handlers.

## Constants and Configuration

**`domain/Constants`** holds only model-intrinsic values (sample rate, token limits, embedding dimensions) and validation bounds (speed range, max text length).

**`InfraConfig`** holds deployment-specific settings (optional `aws`, required `storagePrefix`). When `aws` is null, local storage is used under `storagePrefix`; otherwise S3 is used. The app reads from `application.yaml`; the lambda reads from environment variables.

**File-level constants** — use `private const val` / `private val` at file level, never inside a `companion object`:

```kotlin
// Correct — file-level constants after the class
class SentencePostProcessor { /* uses the constants below */ }

private const val QUESTION_TAIL_MS = 600
private const val QUESTION_TAIL_GAIN = 1.15f

// Wrong — companion object constants
class SentencePostProcessor {
    companion object {
        private const val QUESTION_TAIL_MS = 600  // don't do this
    }
}
```

## Domain Validation

Value types validate in their constructors and throw `IllegalArgumentException` on bad input:

- `VoiceId` rejects blank strings
- `SpeechRate` enforces the [0.5, 2.0] range
- `VoiceBlendWeight` enforces the (0.0, 1.0] range

Use cases catch these and translate them into `SynthesisException` subtypes.

## Error Handling

`SynthesizeSpeechUseCase.execute()` returns `Result<StoredAudio>`. Routes call `.getOrThrow()` and exceptions bubble up to `StatusPages` in `Errors.kt`, which uses a single `exception<SynthesisException>` handler with a `when` block:

| Exception subtype          | HTTP status |
|----------------------------|-------------|
| `VoiceNotFound`            | 404         |
| `TextTooLong`              | 400         |
| `SpeedOutOfRange`          | 400         |
| `DialogueEmpty`            | 400         |
| `InvalidInput`             | 400         |
| `InferenceFailed`          | 500         |
| `StorageFailed`            | 500         |

`configureStatusPages()` must be called before `configureRouting()` so that exception handlers are registered before routes execute.

## Testing

### Structure

All tests follow the given-when-then pattern with `// given`, `// when`, `// then` comments and blank line separators between sections:

```kotlin
@Test
fun `storage failure returns storage failed`() {
    runTest {
        // given
        val useCase = buildUseCase(storageFails = true)
        val turns = listOf(DialogueTurnInput("af_heart", "Hello"))

        // when
        val result = useCase.execute(turns)

        // then
        assertTrue(result.isFailure)
        assertIs<SynthesisException.StorageFailed>(result.exceptionOrNull())
    }
}
```

Omit `// given` when setup is inline or class-level. Tests that are purely assertion (e.g., `assertFailsWith`) need no section comments.

### Test Fakes

Tests prefer anonymous `object : Interface { ... }` expressions. MockK is acceptable when faking an interface would be impractical (e.g., large third-party interfaces like `S3Client` or `Context`). Parametrized fakes are built via helper functions:

```kotlin
private fun buildUseCase(
    storageResult: StoredAudio = storedAudio,
    storageFails: Boolean = false,
): SynthesizeSpeechUseCase {
    val storage =
        object : AudioStorage {
            override suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec): StoredAudio {
                if (storageFails) error("S3 down")
                return storageResult
            }
        }
    // ...
}
```

### Route Tests

`RoutingTest` uses Ktor's `testApplication` with manually wired Koin modules to avoid loading the real ONNX model. Fakes for individual adapters can be swapped in per test.

## Code Quality Tooling

Three tools run on every `./gradlew build`:

| Tool       | Purpose                      |
|------------|------------------------------|
| **ktlint** | Code formatting              |
| **detekt** | Static analysis              |
| **Kover**  | Code coverage (minimum 85%)  |

### Style Rules

- Max line length: **140** characters (enforced by `.editorconfig` and `detekt.yml`)
- All files must end with a trailing newline (`NewLineAtEndOfFile`)
- Import order is enforced by ktlint — run `./gradlew ktlintFormat` after adding/reordering imports

### Banned Patterns

| Rule                                | What it prohibits                                            |
|-------------------------------------|--------------------------------------------------------------|
| `ForbiddenMethodCall`               | `println` / `print` — use SLF4J logging instead             |
| `ForbiddenComment`                  | `TODO:`, `FIXME:`, `STOPSHIP:` — these fail the build       |
| `SpreadOperator`                    | `*array` spread in production code — use `copyInto` instead. Allowed in tests. |
| `SuspendFunSwallowedCancellation`   | Swallowing `CancellationException` in coroutines             |

### Complexity Limits

| Rule                       | Limit                                                         |
|----------------------------|---------------------------------------------------------------|
| `LongMethod`               | Max **80** lines per function                                 |
| `LongParameterList`        | Max **5** function params, **6** constructor params (data classes exempt) |
| `CyclomaticComplexMethod`  | Max complexity **14**                                         |
| `ReturnCount`              | Max **5** returns per function (guard clauses excluded)       |
| `ThrowsCount`              | Max **2** throw statements per function                       |
| `LargeClass`               | Max **600** lines                                             |
| `TooManyFunctions`         | Max **20** per file, **15** per class                         |
| `NestedBlockDepth`         | Max **5** levels of nesting                                   |

## File and Member Ordering

### File-level ordering

1. Typealiases
2. Classes / objects
3. File-level constants (`private const val` / `private val`)
4. File-level private functions (only when they cannot be class methods — see below)

### Inside classes

1. Properties (public, then private)
2. `init` blocks
3. Methods (override, then public, then private)

```kotlin
class OnnxKokoroEngine(
    private val modelPath: String,              // constructor params
    private val voiceRepo: VoiceRepository,
) : InferenceEngine, AutoCloseable {
    private val logger = logger()               // instance properties

    override suspend fun infer(/* ... */) { }   // override methods first

    override fun close() { }

    fun warmup(/* ... */) { }                   // public methods

    private fun extractStyle(/* ... */) { }     // private methods last
}
```

### Prefer class methods over file-level functions

Functions should live inside the class that uses them. File-level private functions are acceptable in two cases:

1. The function has no natural owning class (e.g., standalone factory functions, top-level extension functions)
2. Stateless utility functions extracted to keep a class under detekt's `TooManyFunctions` limit (max 15 per class) — prefer this over creating a new class when the functions are tightly coupled to the file and have no reuse potential (see `EnglishPhonemeGenerator.kt` where stemming/suffix functions are file-level)

If the extracted functions have broader reuse potential or form a cohesive group, extract a new class instead.

## Documentation

### KDoc Block Comments

Use multi-line KDoc format for all block comments. Never use single-line `/** ... */` format:

```kotlin
// Correct — multi-line KDoc
/**
 * Strips "'s" suffix, looks up the base word, and appends the appropriate sibilant suffix.
 *
 * @param word lowercase word ending in "'s"
 * @param posTag POS tag for POS-aware dictionary selection
 * @return base phonemes + possessive suffix, or null if base is unknown
 */
private fun lookupPossessive(word: String, posTag: String?): String? {

// Wrong — single-line KDoc
/** Strips "'s" suffix, looks up the base word, and appends the sibilant suffix. */
private fun lookupPossessive(word: String, posTag: String?): String? {
```

Include `@param` and `@return` tags for functions with non-obvious parameters or return values. Omit them only when the function description makes parameters and return values self-evident (e.g., `contains`, simple getters).

### Inline Comments

Add inline comments in method bodies where the logic is critical or non-obvious:

```kotlin
// Scan right-to-left: each position gets the vowel info from the next token
for (i in resolvedPhonemes.indices.reversed()) {
    result[i] = lastVowelInfo
    val ps = resolvedPhonemes[i]
    if (!ps.isNullOrBlank()) {
        // Skip stress marks (ˈ, ˌ) to find the actual first vowel or consonant sound
        val firstSound = ps.firstOrNull { it in IPA_VOWELS || it in IPA_CONSONANTS }
```

Use inline comments for:
- Algorithm steps in multi-pass processing (e.g., "First pass: resolve tokens", "Second pass: apply context overrides")
- Non-obvious conditional logic (e.g., "Sentence-final: use stressed None variant")
- Data structure semantics (e.g., "IPA sibilant consonants — plurals after these get the ᵻz suffix")

Do not use inline comments for code that is self-explanatory.
