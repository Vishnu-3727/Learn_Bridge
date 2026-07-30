# LearnBridge AI — Full Technical Report

**Generated:** 2026-07-30
**Repo:** `github.com/Vishnu-3727/Learn_Bridge` · branch `main`
**Working copy:** `C:\Users\vishn\AndroidStudioProjects\LearnBridgeAI`

A single, complete account of the project: what it is, how every part works, every measurement
taken on real hardware, every trap found and closed, and what is still open. Written from the live
repository rather than from notes — where the two disagreed, the repository won and the
disagreement is called out.

---

## Contents

1. [State at time of writing](#1-state-at-time-of-writing)
2. [What the app is](#2-what-the-app-is)
3. [Architecture](#3-architecture)
4. [File-by-file inventory](#4-file-by-file-inventory)
5. [Ingest pipeline](#5-ingest-pipeline)
6. [Persistence](#6-persistence)
7. [Retrieval](#7-retrieval)
8. [The teaching path](#8-the-teaching-path)
9. [Translation](#9-translation)
10. [Learning Twin](#10-learning-twin)
11. [Privacy](#11-privacy)
12. [Text-to-speech](#12-text-to-speech)
13. [UI](#13-ui)
14. [Measurements](#14-measurements)
15. [Packaging and distribution](#15-packaging-and-distribution)
16. [Tests](#16-tests)
17. [Audit findings F1–F17](#17-audit-findings-f1f17)
18. [The simplification pass](#18-the-simplification-pass)
19. [Honest limitations](#19-honest-limitations)
20. [Build and device commands](#20-build-and-device-commands)
21. [Licences](#21-licences)
22. [What is left to do](#22-what-is-left-to-do)

---

## 1. State at time of writing

| | |
|---|---|
| Branch | `main` |
| Local HEAD | `23cb115` — *test: the unfinished-import sweep, through a real reopen* |
| `origin/main` | `6af90ea` — local was **3 commits ahead, unpushed** |
| Working tree | **dirty** — 3 files modified, +286 / −7 lines |
| JVM tests | **241 green** (206 `:app` + 35 `:engine`), 0 skipped, 0 failures |
| Lint | `lintDebug` — **0 errors, 74 warnings** |
| Merged manifest | exactly one `uses-permission`: `com.learnbridge.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (androidx, signature level). **Zero external permissions.** |
| APKs built | arm64-v8a **1050.8 MiB** · armeabi-v7a 1034.8 MiB · x86_64 1059.1 MiB |
| Code size | main **8,010 lines** (26 app files = 6,092 · 13 engine files = 1,918); test **4,252 lines** |

### Uncommitted work at the time this report was written

A **quiz duplicate-option filter** in `LessonParser` and `Prompts`. It exists because of a real
student-facing failure on the SM-M315F: a SystemVerilog packed/unpacked question where the model
wrote its own answer twice in different words, and one of the two identical statements was
arbitrarily marked wrong. A student picking it was told "Not quite" with no way to have known.

- `Prompts.QUIZ_SLOTS` — the four bracketed template slots (`the question`, `the correct answer`,
  `a wrong option`, `a different wrong option`) are now constants interpolated into `Prompts.quiz`,
  so the filter cannot drift from the wording it filters. The rendered prompt is byte-identical, so
  every measurement recorded against that text stays valid — and a test pins that.
- `LessonParser.similarity()` — Jaccard overlap of word sets, lowercased, punctuation stripped. Set
  overlap rather than edit distance, because the failure is rewording, not typos.
- `SAME_OPTION = 0.6` — measured, not picked. The real duplicate pair scored **0.74**; a legitimate
  true-but-not-the-answer distractor scored **0.33**. 0.6 sits in the gap with room either side.
- `usableDistractors()` — drops any option that rewords the answer or another option.
  **Deliberately no floor** that keeps the least-similar one: keeping one leaves either the
  unanswerable pair or a multiple-choice question with a single choice. When nothing survives, the
  whole item is dropped; an empty quiz already has its own screen.
- `isCopiedSlot()` — drops an option that is a copied template slot. The prompt already says "Never
  copy a bracket", and the model obeyed it literally by emitting `X: A different wrong option`. The
  prompt cannot fix this, so the parser does.
- 13 new tests in `LessonParserTest`, every one named after the device observation that produced it.

### Where prior notes were stale

Three claims in the working notes did not survive contact with the repository:

1. "Everything pushed at `6af90ea`" — false. Three commits (`1b7736d`, `d64761f`, `23cb115`, the
   whole any-document/OCR feature) were local only.
2. "217 tests" — now **241**.
3. "All 13 locales at 66 keys" — now **71 keys per locale, 72 in the default**
   (`app_name` is `translatable="false"`).

---

## 2. What the app is

An offline Android study companion. Photograph a textbook page or import any document, and get back
key points, a quiz, grounded Q&A, a translation into any of thirteen Indian languages, and
read-aloud — **with no network permission at all**.

हिंदी · मराठी · नेपाली · संस्कृतम् · اردو · தமிழ் · తెలుగు · ಕನ್ನಡ · മലയാളം · বাংলা · ગુજરાતી · ਪੰਜਾਬੀ · ଓଡ଼ିଆ, plus the
English source.

The thesis: **a student with no data plan and a ₹8,000 phone should get the same tutor as a student
with fibre.** Every AI study tool in this category — ChatGPT, NotebookLM, Khanmigo, Perplexity —
returns a network error the moment the data runs out. Their entire product class assumes
connectivity is a precondition of learning.

### Modules

| Module | Package | Main files | Role |
|---|---|---|---|
| `:app` | `com.learnbridge.app` | 26 | ingestion, teaching, persistence, UI |
| `:engine` | `com.bhashabridge.app` | 13 | frozen IndicTrans2 ONNX translation stack + TTS |

The `:engine` namespace is deliberately **not** renamed to `com.learnbridge.engine`. `MtEngine`
imports `com.bhashabridge.app.Direction`, `.bench.Metrics`, `.logDebug` and `BuildConfig`. Renaming
means touching ~20 files and regenerating `BuildConfig` under a new namespace — twenty chances to
break a working, benchmarked inference engine. The cosmetic mismatch is free.

No `:slm` / `:doc` / `:ui` split either: six build files buy nothing here.

### Toolchain

AGP 9.3.0 · Kotlin 2.2.10 · Gradle 9.5.0 · JDK 17 target (JBR 21 to run) · compileSdk 36.1 ·
minSdk 24 · targetSdk 36 · configuration cache on ·
`org.gradle.jvmargs=-Xmx8192m` because the 2 GB default OOMs the daemon while packaging ~1 GB of
uncompressed assets.

---

## 3. Architecture

```
LibraryActivity ──pick / photograph──► LibraryViewModel.ingest(uri)
                                          │  LessonPipeline.ingest(): Flow<IngestProgress>
                            DocImport ────┤  txt/md · PDF (text layer or OCR) · photo
                                          │  · ZIP-of-XML · HTML · RTF · anything readable
                            Chunker ──────┤  180-word paragraph-greedy, 1-sentence overlap
                            DocStore ─────┤  SQLite: documents · chunks_fts(FTS4)
                                          │          artifacts · mastery
                            ModelHost ────┤  withTeacher    → Gemma 3 1B int4 | ExtractiveTeacher
                            LessonTranslator ─ withTranslator → MtEngine (13 langs, ONE engine)
                                          ▼
LessonActivity ◄── LessonViewModel ◄── DocStore reads only (no model is touched)
```

### Three design bets, all load-bearing

**1. One 459 MB translation export serves thirteen languages.**
The target language is nothing more than the **second token of the source sequence**. No second
model, no reload, no additional resident memory. Switching Hindi → Tamil is a token change.

**2. Indic scripts come free by Unicode offset.**
IndicTrans2 normalises every Indic language into a single script-unified **Devanagari**
representation and transliterates back to the native script as post-processing. The ONNX export
carries the model but not that step, so the native script is reachable by a fixed offset:

| Language | Offset | Language | Offset |
|---|---|---|---|
| Bengali | +0x80 | Telugu | +0x300 |
| Punjabi | +0x100 | Kannada | +0x380 |
| Gujarati | +0x180 | Malayalam | +0x400 |
| Odia | +0x200 | Tamil | +0x280 |

Counting subword pieces in the target vocabulary gives Devanagari 72,356, Arabic 16,949, Latin
11,414 — and **zero** multi-character pieces for Tamil, Telugu, Kannada, Malayalam, Gurmukhi and
Odia. Reading that as "those languages cannot be generated" was the wrong conclusion. The model
generates Tamil perfectly well; it writes it in Devanagari:

```
नीर् चुऴऱ्चि ऎऩ्ऱु अऴैक्कप्पटुकिऱतु      what the model emits
நீர் சுழற்சி என்று அழைக்கப்படுகிறது       what it should read as
```

The missing piece was a 60-line character mapping, not a model. Urdu is the control case that
proves the mechanism: Perso-Arabic is not Brahmic, cannot be reached by an offset, and accordingly
has genuine subword coverage of its own.

**3. Generate once at import, never on screen open.**
A 20-page document is roughly 12,000 tokens and will not fit any context this app can afford;
decode runs at single-digit tokens per second on mid-range silicon. Generating on demand would mean
a spinner every time a student opened a lesson. So one pass at ingest produces the English artifacts
*and* their translation, writes both to SQLite, and every later screen open is a database read.
This is also what makes the language toggle instant.

### `ModelHost` — residency is a measurement, not an assumption

| Tier | Trigger | Behaviour |
|---|---|---|
| `CO_RESIDENT` | totalMem ≥ 6,500 MB | both models stay loaded; toggles instant |
| `EXCLUSIVE` | 3,500–6,500 MB, or co-residency previously failed | one large model at a time; a swap costs 7–18 s |
| `EXTRACTIVE` | < 3,500 MB or `isLowRamDevice` | no generative model at all |

- **One mutex does double duty.** It serialises model loading, and because an entire generation —
  prefill plus every decoded token — happens inside one `withTeacher` block, it also guarantees no
  other coroutine can swap a model out from under an in-flight turn. There is no separate mid-turn
  guard because none is needed.
- **`onTrimMemory` deliberately does NOT take that mutex.** It arrives on the main thread, and
  blocking it behind an in-flight generation would freeze the UI for seconds. At CRITICAL it only
  downgrades the tier, so the *next* borrow frees the idle model; at COMPLETE the app is already a
  kill candidate, so releasing immediately is the entire point. The downgrade is permanent for the
  process — a device that hit critical pressure once with both models loaded will hit it again, and
  retrying co-residency mid-session is how a demo dies.
- **The 6 GB band attempts co-residency once**, then `rememberNoCoResidency()` makes the answer
  stick for every future launch. Try-once-and-remember beats a hardcoded threshold nobody can tune.
- **Decoder ceiling raised at the call site.** The engine's inherited default is
  `maxSteps = 18, minTargetLen = 14`, which caps any single translation at roughly fourteen Hindi
  words — fine for the phrase-sized inputs it was tuned for, ruinous for an explanation. Raised to
  48/48 here, which is a call-site change: `MtEngine` takes the decoder as a constructor parameter,
  so nothing inside the frozen engine module is touched. 48 costs nothing for short inputs because
  decoding stops at the end-of-sentence token regardless (real translations finish in 3–14 steps);
  a high cap does not lengthen output, it only stops long output being cut off mid-sentence. Raised
  32 → 48 so `LessonTranslator` can send whole 18-word sentences instead of splitting at commas.

### Degradation ladder — every rung a working product

```
1. EXTRACTIVE tier, or no .task staged   → ExtractiveTeacher
2. Gemma loads                            → GemmaTeacher
3. Gemma throws (incl. OutOfMemoryError)  → degrade + remember, ExtractiveTeacher
```

A device that cannot run generation swaps the implementation and keeps every feature. **The app
never shows an error where a feature belongs.**

---

## 4. File-by-file inventory

### `:app` — `com.learnbridge.app`

| File | Lines | What it does |
|---|---|---|
| `LearnBridgeApp.kt` | 239 | Hand-written service locator, not DI — three dependencies do not justify KSP on every build. Owns `ModelHost` and `DocStore` at process scope, so a destroyed Activity cannot orphan a native resource and a rotation cannot trigger a multi-second reload. `targetLanguage`'s setter also calls `AppCompatDelegate.setApplicationLocales`. `eraseAllLearnerData()` is the ONE deletion entry point. `createTeacher(tier)` runs the degradation ladder. |
| `ModelHost.kt` | 229 | Residency, tiering, the mutex. See above. |
| `doc/DocImport.kt` | 437 | Format detection and extraction for every supported input. |
| `doc/TextExtract.kt` | 234 | Pure format knowledge — `sniff`, `looksBinary`, `textEntries`, `fromMarkup`, `fromRtf`, `normalize`, `decodeEntities`, `wordCount`. No `Context`, no `Uri`, so all of it is JVM-testable; format guessing is exactly the code that needs tests, because its failure mode is a silently empty lesson rather than a crash. |
| `doc/DocStore.kt` | 421 | The only type that touches the database. |
| `doc/Chunker.kt` | 102 | 180-word paragraph-greedy chunking. |
| `doc/Retrieval.kt` | 100 | FTS4 MATCH plus two heuristics. |
| `doc/Mastery.kt` | 171 | The Learning Twin's value type. |
| `doc/Revision.kt` | 88 | Transparent weighted-sum recommender. |
| `doc/LearnerExport.kt` | 108 | The Twin as prose and as JSON. |
| `lang/SupportedLanguage.kt` | 97 | 14 entries: FLORES tag, BCP-47 code, endonym, sentence terminator, TTS locale, `rtl`, `scriptOffset`. |
| `lang/BrahmicTransliterator.kt` | 92 | Devanagari → target Brahmic script by offset. |
| `lang/LessonTranslator.kt` | 311 | Fragment splitting, memoisation, punctuation repair. |
| `teach/Teacher.kt` | 61 | `TeachRequest` (Explain / Ask / Quiz) and the `Teacher` interface. Intent, not a prompt string — passing pre-formatted Gemma text through here would force every implementation to speak Gemma's dialect, and the non-generative one has no use for prompt text at all. |
| `teach/Prompts.kt` | 230 | The three prompts the tutor ever sends. |
| `teach/Lesson.kt` | 280 | `QuizItem` and `LessonParser`. |
| `teach/GemmaTeacher.kt` | 266 | MediaPipe LLM Inference adapter. |
| `teach/ExtractiveTeacher.kt` | 442 | TextRank + TF-IDF + cloze. Cannot hallucinate. |
| `teach/FakeTeacher.kt` | 51 | Canned streaming at ~10 tok/s. Written *before* the real one, deliberately, so every screen and pipeline stage is testable on the JVM with no device and no 554 MB model. Kept deliberately **slow** — UI built against an instant fake hides exactly the jank streaming causes in production. |
| `teach/LessonPipeline.kt` | 345 | The one place the ingest sequence is defined. |
| `ui/LibraryActivity.kt` | 514 | Launcher screen. |
| `ui/LibraryViewModel.kt` | 66 | Owns the ingest `Job` so it outlives the Activity. |
| `ui/LessonActivity.kt` | 537 | Explain / Ask / Quiz, language toggle, Listen. A pure renderer of state. |
| `ui/LessonViewModel.kt` | 551 | All lesson business logic behind one `StateFlow`. |
| `ui/LanguageChooser.kt` | 42 | One dialog, both screens. English first, then every target, always the same order — a chooser whose options move between screens is one students stop trusting. |
| `ui/Voices.kt` | 78 | Voice catalogue and hand-off to the engine's installer. |

### `:engine` — `com.bhashabridge.app` (frozen inference stack, reused from BhashaBridge V4)

| File | Lines | What it does |
|---|---|---|
| `mt/OnnxModels.kt` | 455 | Owns three `OrtSession`s — encoder, decoder_init, decoder_step. Loads all three **concurrently** (12.3 s serial → 6.3 s measured), with every future awaited before anything is thrown and every already-loaded session closed on failure, so a partial load cannot leak hundreds of MB with no owner. ORT-format cache: bake ALL_OPT once, then mmap NO_OPT. Cache key is `VERSION_CODE \| ORT version \| source asset byte length` — length via a cheap `openFd` rather than a content hash, because hashing hundreds of MB every launch would cost more than the optimisation the cache removes. Every failure path degrades to an uncached source load, so the cache can never break startup. `pastInputNames` is read from the graph, never hardcoded. |
| `mt/MtEngine.kt` | 194 | tokenize → encode → decode → detokenize. `CachedLogitsSource` maps the decoder's full-prefix contract onto the KV-cached graphs: a prefix that extends the previous one by exactly one token runs `decoder_step`; anything else rebuilds via `decoder_init`. Greedy always takes the fast path after the first token; a reordering decoder simply falls back to init — correct, just not accelerated. `decoder_step` has **no** `encoder_hidden_states` input: with the cache present the graph reuses cached cross-attention K/V, so torch.onnx pruned it. |
| `mt/Tokenizer.kt` | 188 | Vocabulary, `tagId`, encode/decode. |
| `mt/Decoder.kt` | 111 | `Decoder` / `LogitsSource` / `DecodeConfig`. `startToken == eosToken == 2` is correct, not a typo — IndicTrans2 is mBART-family and uses one `</s>` id as both "begin decoding" and "stop". Shared rules as internal top-level functions: repetition penalty 1.1, no-repeat-3-gram blocking, argmax with ties to the lowest index. |
| `mt/GreedyDecoder.kt` | 36 | Greedy. EOS and the length cap both stop **without** appending the token being examined. |
| `mt/ExecutionPolicy.kt` | 89 | The benchmark-selected production `OrtTuning`. |
| `mt/CpuCapabilities.kt` | 123 | Big/little core detection for thread affinity. |
| `speech/Tts.kt` | 212 | Engine selection, installed-voice detection, segmentation, install hand-off. |
| `bench/Metrics.kt` · `Stats.kt` · `SystemStats.kt` | 161 / 65 / 208 | Benchmark harness. |
| `Direction.kt` · `Logging.kt` | 24 / 52 | `EN_TO_HI` / `HI_TO_EN`; `logDebug` compiles itself out of release builds via `BuildConfig.DEBUG`. |

`Direction` lives in the root package rather than in `mt/` because three subsystems need it — `mt/`
(which model pair), `speech/` (which voice), `ui/` (which labels). Putting it in `mt/` would force a
`speech/ → mt/` dependency edge for a two-value enum with no behaviour.

### Production ONNX Runtime tuning

`OrtTuning.production()` = `intraThreads = 2`, `cpuArena = false`, `optCache = true`. Both knobs are
independently evidenced on the SM-M315F:

- `intraThreads = 2` pins intra-op work to the two big cores — decode ~10% faster, and more
  importantly **variance collapses** (stdev 97 → 15 ms, p95 −30%) against ORT's default, which
  spreads onto the little cores and jitters.
- `cpuArena = false` — **−37% process memory (983 → 617 MB PSS)** at no measurable latency cost. The
  arena's up-front pool is pure overhead for a steady, one-translation-at-a-time workload.

---

## 5. Ingest pipeline

### 5.1 Format detection

`DocImport.classify` decides by name and MIME first, then by the file's own first bytes. Cheap path
first, honest path always.

1. MIME plus filename extension → IMAGE / PDF / ZIP_XML / RTF / MARKUP / TEXT
2. Still undecided → read the first **512 bytes** and sniff the signature: `%PDF` → PDF, `PK` → ZIP,
   `{\rt` → RTF
3. Still undecided → `looksBinary()` (a NUL byte, or more than 5% control bytes) → UNSUPPORTED,
   otherwise **attempt it as text**

The byte sniff is the fallback rather than the first move deliberately: it is the only thing that
can identify a PDF a chat app handed over as `application/octet-stream`, or a `.docx` a download
manager stripped the extension from — but it costs an extra open of the stream, and for the
overwhelming majority of imports the name already settles it.

The picker filter is `arrayOf("*/*")`. It used to be a text wildcard plus `application/pdf` plus an
image wildcard, which greyed out a student's own `.docx` notes and — worse — greyed out PDFs a chat
app had mislabelled. A wrong "not supported" a student can act on beats a file they cannot select.

Known extensions: images `.jpg .jpeg .png .webp .heic .heif .bmp`; zip
`.docx .pptx .xlsx .odt .odp .ods .epub .docm .pptm .xlsm`; markup `.html .htm .xhtml .xml .svg`;
text `.txt .md .markdown .csv .tsv .json .log .srt .vtt .tex`.

*(Kotlin nests block comments, so a MIME wildcard written literally in a KDoc opens one and the
closing delimiter then ends the wrong comment. That is why they are spelled out in the source.)*

### 5.2 ZIP-of-XML — DOCX, PPTX, XLSX, ODT, ODP, ODS, EPUB

One reader, because they are all the same thing: a ZIP of XML. `java.util.zip` plus tag-stripping
covers every one of them; Apache POI is megabytes of dependency to read text a regex can read.

The archive is walked **twice**, because `ZipInputStream` cannot seek and the *set* of entries
decides which ones are worth reading. Reading a 3 MB container's directory twice is cheaper than
holding every part of it in memory.

Entry priority, in reading order:
`word/document.xml` → `ppt/slides/slideN.xml` (sorted by trailing number, so slide2 orders before
slide10) → `content.xml` (ODF puts the whole document in one part) → `xl/sharedStrings.xml` (a
spreadsheet's words live in the shared string table; the sheets are numbers and formulas, which are
not study material) → `*.xhtml/.html/.htm` not containing "nav", alphabetical (EPUB).

> **Trap:** `zip.reader()` must **never** be wrapped in `use()`. Closing that reader closes the whole
> `ZipInputStream` and the walk ends after the first entry read.

EPUB chapters come back in name order rather than spine order from the OPF — right for every
generator that numbers its files, wrong for the ones that do not. Marked `ponytail:` in the source
with the upgrade path (parse `content.opf`).

### 5.3 PDF — text layer, with an OCR fallback

- `PDFTextStripper` first, under `MemoryUsageSetting.setupTempFileOnly()` so a 40-page document does
  not spike heap alongside a possibly co-resident generative model. Page count and text come out of
  **one** load — parsing a large PDF twice to ask two questions of it is what turns a slow import
  into a failed one on a 4 GB device.
- If `wordCount < MIN_WORDS_PER_PAGE (60) × pages`, render each page with the platform
  `PdfRenderer` at **2000 px on the long edge** and read it with the bundled ML Kit Devanagari
  recognizer that is already in the APK for photographed pages. Both halves are therefore free; only
  the seconds are not. Capped at `MAX_OCR_PAGES = 60`.
- Keep **whichever pass read more words**. A deck with real prose on three slides and pictures on
  twenty must not lose the prose if OCR misfires.
- A malformed page tree can report zero pages, which would make the threshold zero and silently skip
  the fallback on exactly the broken files that need it most — hence `pageCount.coerceAtLeast(1)`.

> **`bitmap.eraseColor(Color.WHITE)` before render is load-bearing.** `PdfRenderer` draws onto
> whatever the bitmap already holds, which for a fresh ARGB_8888 bitmap is transparent black — and
> OCR of dark text on that returns nothing at all, silently.

Bitmaps are recycled page by page: sixty 2000 px ARGB_8888 bitmaps are ~1 GB if held.

**The measurement that produced this.** A 51-page slide deck of semiconductor notes with 126
embedded images had **652 words** in its text layer — thirteen a page — because everything that
mattered lived *inside* the images. That is 5 chunks, 2 sections, 10 key points, and it was the root
cause of "a 22-page PDF only gave 10 points". Nothing downstream was broken. After the fallback:
**652 → 3,144 words (4.8×) in 67 s** on the SM-M315F.

**Known artifact:** the Devanagari recognizer mis-reads some Latin digits — `"৪."` for `"3."`,
`"purp০ses"`. It is the same recognizer photographed pages already use, and no Latin-script model is
bundled, so fixing it means a second recognizer pass over every page.

### 5.4 Progress reporting

`IngestProgress.Reading(page, total)` forced the pipeline from `flow{}` to **`channelFlow{}`**.
`DocImport` reports OCR progress from inside its own `withContext(Dispatchers.IO)`, and a plain flow
rejects an emission made from another coroutine ("Flow invariant is violated"). A channel accepts
sends from anywhere.

The UI shows **percentages, not counts**. `Teaching(3, 6)` rendered as "3 / 6" was read as a page
count by the first person who saw it — reasonably, since the document was a 14-page PDF — and it is
not one: those are prompt-sized sections, six of them for fourteen pages. A percentage cannot be
mistaken for a page and needs no new string in fourteen locales. Single-section documents (the
photographed-page case) show no number at all.

### 5.5 Failure handling

Every failure is a **state**, never an exception reaching the UI: `ImportResult.Failure`,
`IngestProgress.Failed`, `AskOutput.Failed`.

- **`.catch`, not a try/catch around `emit`.** A try/catch wrapping the body also catches exceptions
  thrown by the *collector*, and emitting again from there fails with "Flow exception transparency is
  violated" — masking the real error with a worse one.
- **`.onCompletion` for cancellation**, which does not pass through `.catch`. The collecting scope
  dies whenever the student leaves the screen mid-ingest, and without this the half-built row would
  outlive it: visible in the library, openable, permanently empty.
- `DocImport` catches broadly *and* rethrows `CancellationException` first, so structured concurrency
  still works. The catch is deliberately broad because `IOException` and `SecurityException` do not
  cover what the extractors actually throw — PdfBox raises its own unchecked types for a malformed
  page tree or an encrypted file, and ML Kit resumes the coroutine with whatever its recognizer
  failed with. `OutOfMemoryError` is caught separately: a very large PDF must cost the import, not
  the process.
- `LessonPipeline.attempt()` is used instead of `runCatching` inside the section loop, because
  `runCatching` catches `CancellationException` like any other throwable. With one generation pass
  per document that was survivable; with one per section it is not — a student who leaves the screen
  would cancel a scope the loop then ignores, and the tutor would keep generating, and keep the model
  resident, through every remaining section.
- **`DocStore.onOpen` sweeps documents left at `status='importing'` by a killed process.** The
  pipeline deletes a document whose ingest fails or is cancelled, which covers every case it is still
  alive for. It cannot cover being killed: the row is inserted before minutes of OCR and generation.
  Found on a real device — a resume PDF imported four days earlier, still `importing`, holding its
  chunks and its saved text. `onOpen` is the right hook precisely because it is per-process and runs
  before any import can, so a row in that state is by definition a corpse from a previous process.
- `deleteDocumentIn(db, docId)` takes the database as a parameter rather than fetching it, because
  `onOpen` runs *during* the open that `writableDatabase` is performing, and asking for the database
  from in there deadlocks on `SQLiteOpenHelper`'s own lock.

### 5.6 Chunking

180 words ≈ 250 tokens, so four chunks ≈ 1,000 tokens — the prompt budget. Paragraph-greedy rather
than a configurable recursive splitter: paragraphs are the natural unit a writer already chose.
Over-long paragraphs are pre-split at sentence boundaries. One sentence of overlap is carried from
chunk N into chunk N+1, capped at 25 words for punctuation-free runs, so a fact landing on a
boundary is findable from either side.

---

## 6. Persistence

Plain `SQLiteOpenHelper`, not Room — Room buys type safety at the cost of KSP and annotation
processing on every build. **FTS4, not FTS5** — FTS4 works across the whole minSdk 24 range; FTS5 and
`bm25()` are only guaranteed from roughly API 30, and a demo device is not guaranteed to be that new.

### Schema v2

```sql
documents(id INTEGER PK AUTOINCREMENT, title, sourceUri, wordCount, ingestedAt, status)
chunks_fts USING fts4(doc_id, ordinal, text)
artifacts(docId, kind, lang, ordinal, text)
  UNIQUE INDEX idx_artifacts_lookup ON artifacts(docId, kind, lang, ordinal)
mastery(docId INTEGER PK, mastery, confidence, exposureCount,
        lastSeen, intervalDays, easeFactor, dueAt)
```

### Three SQLite traps, each with the failure that produced it

1. **The column is `doc_id`, not `docId`.** FTS3/4 tables have an implicit, case-insensitive `docid`
   alias for their rowid. A column spelled `docId` collides with it and SQLite refuses the
   `CREATE VIRTUAL TABLE` with "vtable constructor failed". Reproduced against a bare
   `SQLiteDatabase`, so it is a real on-device failure, not a Robolectric artifact.
2. **`ordinal` is stored as TEXT.** FTS4 columns carry no declared type affinity, so an INTEGER value
   and a TEXT bind parameter in a later `doc_id = ?` clause compare as different storage classes and
   never match. Storing both sides as text keeps every comparison in one storage class.
3. **Therefore `ORDER BY CAST(ordinal AS INTEGER) DESC`** in `Retrieval.lastChunk`. Without the CAST
   a plain `ORDER BY ordinal DESC` sorts lexicographically and picks "9" over "12" — so any document
   past nine chunks (~1,800 words, about four pages) silently returned the wrong chunk, on exactly
   the generic-question path the fallback exists to serve.

### `putArtifact` is one statement

`insertWithOnConflict(..., CONFLICT_REPLACE)` against the unique index. The two-statement
delete-then-insert version had a window in which the row simply did not exist: a crash or a process
kill landing there lost the artifact and left the caller believing it had been written. The unique
index is load-bearing rather than defensive — it is what makes the swap atomic — and
`(docId, kind, lang)` remains a usable prefix of it, so the constant UI lookup costs nothing extra
and gets its `ORDER BY ordinal` for free.

### Migrations

`onUpgrade` steps forward one version at a time. **`onCreate` *runs* the migrations** rather than
duplicating their DDL, so the schema a new device gets and the schema an upgraded device reaches
cannot drift apart — which is the usual way migration bugs are introduced.

v2 creates `mastery` and does `DROP TABLE IF EXISTS quiz_results` — the write-only table removed in
F8; this is the one place that will ever clear it off a device that still carries it.

Verified on device: four real documents survived a v1 → v2 install. `DocStoreMigrationTest` builds a
real v1 database and checks it.

### `deleteEverything()`

Empties the tables rather than dropping them, so the schema — and therefore the schema version — is
untouched and the next import needs no migration. Deliberately does **not** touch preferences: the
teaching language and the remembered device tier are settings, not things learned about the student,
and silently resetting the app's language as a side effect of deleting data would be its own
surprise.

### Plain-text mirror

Extracted text is also written to `filesDir/docs/<docId>.txt`, so a schema change never means
re-OCRing or re-parsing a source document — `chunks_fts` can be rebuilt from the saved text alone.

---

## 7. Retrieval

FTS4 has no `bm25()`/`rank()`, so "best match first" is not available cheaply. Two heuristics matter
more than ranking:

1. **Chunk 0 is always included**, whether or not it matched. This alone answers "what is this
   document about" and "summarise this" — the first thing anyone types.
2. **Fewer than two MATCH hits falls back to chunks `[0, 1, last]`.** Intro and conclusion tend to
   answer generic questions when the query shares no vocabulary with any specific chunk. A single
   weak hit is still added on top.

`sanitize()` exists because raw user text is valid FTS query *syntax*: `"`, `*`, `-`, `OR`, `NEAR`,
or an unbalanced quote either throws from `MATCH` or silently changes what it means. Strip the
operator characters, split into words, drop the reserved keywords, re-join with our own `OR`, and
**scope every surviving term to the `text` column** (`text:word`) — `chunks_fts` also indexes
`doc_id` and `ordinal`, and an unscoped MATCH would let a query like "chapter 2" match on the literal
ordinal 2 of an unrelated chunk.

---

## 8. The teaching path

### 8.1 Prompts are shaped as extraction, not generation

The most important design decision in the whole teaching path. A 1B-parameter model is genuinely
good at "find the important sentences in this text and say them more simply" and genuinely bad at
"explain photosynthesis" — the first is grounded in text it can see, the second invites it to invent.
Every instruction points at the supplied text and forbids adding to it.

The "maximum ten words per line" rule appears in all three prompts and does triple duty: a small
model follows length constraints more reliably than any other kind of instruction; short lines are
what the translation engine needs; short lines read better on a phone.

Output formats are **line-prefixed rather than JSON**. A 1B model produces malformed JSON often
enough that a strict parser would throw away otherwise usable output; line prefixes degrade
gracefully, so three good quiz questions survive where five were asked for.

The `NOT_IN_TEXT` sentinel in the Ask prompt is the anti-hallucination lever. Without it, a model
asked something the document does not cover will confidently invent an answer, and the renderer will
then translate that invention into fluent, convincing Hindi. Giving the model a permitted way to
decline is cheaper and more reliable than detecting the invention afterwards.

Quiz uses `Q:` / `A:` / `X:` rather than `A:` / `B:` / `C:` on purpose. Lettered options force the
model to also decide *which letter* is correct and state that somewhere — a second thing to get wrong
and a second thing to parse. Naming the roles makes the correct choice structural, and the UI
shuffles before display so the answer is not always first.

`applyTurnMarkers = true` — **verified on device 2026-07-28** by `TurnMarkerDeviceTest` (the A/B that
F16 asked for). Both arms produced five well-formed bullets on the same source, neither echoed a
turn marker, 276 vs 222 chars. MediaPipe is not double-wrapping destructively. Kept true because the
two are indistinguishable in quality and matching the model's documented format is the safer default
if the bundle ever changes. If a future bundle does start echoing `<start_of_turn>`, that test fails
on an assertion rather than on judgement.

### 8.2 The ceiling, stated once

> **Push this model toward a count and it satisfies the count by copying the template; ask it for
> content it has to invent and it stops after two or three.**

Seven prompt versions measured against the real weights on the SM-M315F, the last four against a
whole page put through the real chunker — which matters, because the model gives up sooner the more
text precedes the instruction, and a hand-trimmed passage flatters it:

| prompt | usable items | wrong options |
|---|---|---|
| "five questions", four-line shape shown once | **1** | real |
| + "five Q lines, twenty lines in total" | **5** | **placeholder text, copied verbatim** |
| + bracketed slots instead of literal placeholders | **2** | real |
| asked for three (short passage) | **3** | real |
| asked for three (whole page) | **2** | real |
| + the count repeated *after* the text | **2** | real |
| one line per question, `Q: … \| answer \| wrong \| wrong` | **5** | **only one per line, some of them true** |
| + "exactly three \| characters", eight words per part | **5** | **"Correct answer \| Wrong answer"** |

One finding explains every row. The single-line format is the clearest case: it fixes the count
outright — five lines every time, five correct answers — and pays for it in the distractors, which
collapse to one per line and are sometimes *true*, which is worse than a missing question.

**What ships is the row that produces correct questions with plausible wrong answers and simply
makes fewer of them.** Asking for a number the model will not reach only hides the shortfall behind
the forgiving parser, where nobody sees it. **Real distractors are the thing to protect: a student
can learn from two good questions and is actively taught wrongly by five where the wrong answer is
correct.**

This is a capacity ceiling on a 1B int4 model, not a wording problem. It looks eminently retryable
and is not.

### 8.3 Sampling was swept, and nothing beat the shipping values

Seven runs on the same page, varying only `GemmaTeacher.temperature` / `topK` / `randomSeed` — now
`@Volatile var`s overridable from instrumentation arguments (`-e temperature 0.7 -e topK 64
-e seed 2`), so an arm costs a run rather than a rebuild.

| temperature / topK | quiz items parsed of 3 | distractor failure mode |
|---|---|---|
| **0.3 / 40 (ships)** | **2** | real |
| 0.7 / 64 | 1 | placeholder prose — "This isn't correct" |
| 1.0 / 64 | 1 | placeholder prose |
| 0.1 / 10 | 1 | statements that are **true** of the text |
| 0.0 / 1 (greedy) | 1 | statements that are **true** |

Heat degrades the wrong options into placeholder prose; cold degrades them into true statements,
which teaches the student the opposite of the point. **Explain and Ask were indifferent to every
arm** — five to six key points and a correct grounded answer in all of them — so sampling is not the
lever on the quiz shortfall.

**The uncomfortable half of that sweep: the count is seed noise.** At the shipping 0.3/40, seed 1
gives 2 items and seeds 2 and 3 give **1** each, same prompt, same page. Six of the seven runs
produced one item. So "ask for three, get two or three" described the shipped seed on one page, not
a rate — a different document is a different roll. The seed is fixed, so a given document behaves
identically every time a student opens it, which is what makes the demo reproducible and also what
hid this: one lucky seed reads as a property of the prompt.

**Volume now comes from the document rather than from the call.** `LessonPipeline` teaches in
prompt-sized sections and concatenates, so a fourteen-page PDF asks six times and gets roughly six
items. Deliberately not fixed with three separate one-question calls: those would cost ~43 s each on
top of a ~90 s section and buy what sectioning already gives.

Engine settings: `MAX_TOKENS = 2048` (must not exceed the context length baked into the `.task` —
the published variants are 1280 and 4096, and asking for more fails at load, not at generation),
`MAX_TOP_K = 64` set above every arm worth trying so raising `topK` cannot fail at generation time,
backend **CPU**.

CPU is the right default because interactive turns are decode-bound: on the reference device GPU
wins prefill by ~7× (2,531 vs 379 tok/s) but is no faster at decode (49 vs 55 tok/s) and costs
~200 MB more.

### 8.4 The Qwen A/B — run, settled, and the loser deleted

SM-M315F, same test, same document, back to back:

| | Gemma 3 1B int4 (529 MB) | Qwen2.5 1.5B int8 ekv4096 (1524 MB) |
|---|---|---|
| load | 11.5 s | ~27 s |
| explain | 48.8 s, 5 of 5 key points | 111.6 s, 9 bullets |
| quiz | 46.7 s, **2 of 3**, silly distractors ("A brown leaf") | 116.4 s, **3 of 3**, genuinely plausible distractors (Carotenoids, Anthocyanins) |
| ask | 38.5 s, **correct** — "Roots absorb water from soil." | 74.0 s, **hallucinated** — "Leaves absorb water from the soil through tiny pores called stomata" |

**Verdict: keep Gemma. Qwen is the better quiz generator, not the better tutor.** It does break the
1B quiz ceiling, at 2.4× the time (a six-section PDF goes from ~9 min to ~22 min), 3× the model size
against an APK already at 1050 MB, and a grounding miss where a study app must be right. n = 1
question per task, so the Ask miss is one sample and not a rate.

Qwen sits on the device renamed `qwen.task.off` (rename back to re-run). The `ModelKind` abstraction
that made the swap a push rather than a rebuild was then **deleted** — a one-case enum plus a
one-value var reads as an extension point for a model that is not coming back. The push-to-swap path
was device-proven both ways before removal, and the note left behind says the thing to restore is
the *pairing* of turn markers to weights, not the constants: sending one model another's markers does
not fail loudly, it degrades output in the way that is hardest to attribute.

### 8.5 `LessonParser` — forgiving by design

The producer is a 1B model that will, unprompted: wrap prefixes in markdown (`**Q:**`), number its
own lines (`1. Q:`), change case, emit an extra blank line, use `B:`/`C:` because it has seen a
thousand lettered quizzes, stop after three questions when asked for five, or append a closing
pleasantry.

Given an unreliable producer, the correct algorithm is to salvage whatever parsed and silently drop
whatever did not. **Nothing here throws on malformed input.**

- `clean()` is **iterative rather than one regex**, because the pieces arrive in any order —
  `**1. text**`, `1. **text**`, `- **2) text**` are all real. A fixed-order pattern strips markdown
  then numbering and leaves the numbering behind whenever it came second. It terminates because each
  pass either shortens the string or changes nothing.
- `parseKeyPoints(max = 6)` falls back to treating every non-empty line as a key point when the model
  ignored the bullet instruction entirely — which it sometimes does while still producing perfectly
  good content. Losing the whole lesson over a missing hyphen would be absurd.
- `parseQuiz(max = 5)` accepts `B:`/`C:`/`D:` as distractors. `flush()` **always clears its state on
  every path** — an early return would leak one item's distractors into the next question, which
  reads as the model malfunctioning.
- `parseAnswer` matches `NOT_IN_TEXT` as **contains**, not equals: a model told to reply with exactly
  one token will still sometimes wrap it in a sentence.
- `isPromptEcho()` drops lines beginning `here are` / `sure,` / `okay` / `text:` / `question:`, or
  equal to `rules:`. Models sometimes restate the instruction before obeying it, and showing that to
  a student looks like a bug.
- **New:** the duplicate-option and copied-slot filter described in §1.

### 8.6 `ExtractiveTeacher` — the fallback that cannot hallucinate

Not a stub. It is the tutor on a device that cannot hold a language model alongside a 605 MB
translation engine, and the tutor that ships if on-device generation proves unusable. It is also the
only implementation that **cannot hallucinate**: every sentence it emits appears verbatim or
near-verbatim in the source, which for a study aid is a genuine advantage. Several tests assert
exactly that, because a paraphrase appearing there would mean a bug that invented content.

What it gives up is paraphrase. Openly a weaker teacher, not a broken one.

Methods: TextRank-style centrality for selection (without the power iteration — one round of
centrality scoring ranks nearly identically to converged PageRank on documents of a few hundred
sentences), smoothed IDF for term weighting, cloze deletion for questions.

**Constants:** `KEY_POINTS = 5`, `QUIZ_ITEMS = 5`, `ANSWER_SENTENCES = 3`, `DISTRACTOR_POOL = 12`,
`MAX_WORDS = 18`, `MIN_CLAUSE_WORDS = 6`, `MIN_SENTENCE_CHARS = 25`, `HEADING_MAX_WORDS = 12`,
`MIN_TERM_LENGTH = 6`, `MIN_QUIZ_SENTENCE_WORDS = 6`, `MIN_TERM_OCCURRENCES = 2`,
`MIN_CANDIDATES = 6`, `MIN_ANSWER_OVERLAP = 0.15`.

**Four behaviours, each bought with a real failure:**

1. **Headings are discarded before sentence splitting.** A heading is short, capitalised and
   grammatical, so it scores well on centrality and reads as a confident key point — the first
   version happily offered "What the plant needs" and the document's own title as two of five key
   facts. The rule that removes them: real prose ends in terminal punctuation, headings do not.
   Applied per paragraph, because once a heading has been glued to the paragraph after it there is no
   reliable way to separate them again. The word-count ceiling keeps a genuinely long line that
   merely lost its full stop from being thrown away with the headings.
2. **`paragraphs()` reflows OCR output first.** OCR does not emit paragraphs, it emits visual lines —
   a photographed page comes back as `"leaves, using nothing but sunlight, water"` / `"and air."`,
   neither of which is a sentence. An earlier version judged headings line by line and therefore
   discarded almost every line of every photographed page, producing a lesson with **zero** key
   points from a perfectly good OCR result.
3. **Rarity alone is the wrong signal — the most important lesson in this class.** The first version
   ranked candidates by IDF only, which produced *"Every living ______ needs energy to stay alive."*
   — answer "thing", distractors "release" and "tiger". Every one of those words appears exactly
   once, so all three scored beautifully on rarity while being educationally worthless. A term worth
   testing is one the document keeps coming back to. So: require a document frequency of at least
   two, then rank the survivors by IDF. Recurrence supplies importance, IDF supplies distinctiveness.
   This fixes the distractors for free, because they are drawn from the same pool.
4. **The quiz loop is term-first, not sentence-first.** Iterating sentences and blanking each one's
   highest-scoring word is what produced answers like "thing", "every" and "inside": every sentence
   yields *some* word, so every sentence produced a question whether or not it had a concept in it.
   Driving the loop from the vetted term list means an answer is always a term that passed the
   filter, and terms the document explicitly defines get asked about first.

`DEFINITION_CUE = \b(called|known as|named|termed|referred to as)\s+([\p{L}]{4,})` — the strongest
signal available without a part-of-speech tagger, because textbook prose announces its own
vocabulary ("tiny structures called chloroplasts", "a green substance called chlorophyll"). Kept to
explicit naming cues only; patterns like "X is a Y" match far too much ordinary prose.

`STOP_WORDS` grew from ~90 entries because the short version produced two bad questions in a row on
device — the answer "thing", then the answer "every". Function words are frequent *and* recurring, so
neither a length filter nor a recurrence filter excludes them; only naming them does. Prepositions
were added later because their absence let **"inside"** become a quiz answer. The list is
deliberately kept clear of subject vocabulary: "cell", "energy", "state", "force" are stop words in
some general-purpose lists and are exactly what a science document is about.

`shorten()` ends with a full stop, **not an ellipsis**. An ellipsis reads as honest about the
truncation but it is not a sentence terminator: the renderer therefore attaches no danda, and
IndicTrans2 given a trailing "…" produced a *question* rather than a statement.

It emits the same `Q:`/`A:`/`X:` shape and the same `NOT_IN_TEXT` sentinel as the generative path, so
`LessonParser` handles both without knowing which teacher produced the output.

### 8.7 `LessonPipeline` — the ingest sequence

**Why the ordering is English-first, then all translation at the end.** On a memory-constrained
device the generative model and the translation engine cannot both stay resident, so acquiring one
releases the other. Interleaving would pay that swap per item. Instead: generate every English
artifact under one `withTeacher`, then translate every fragment of all of them under one
`withTranslator`. **Exactly one model swap per document.** That is also what makes the language
toggle free — the translation is already in the database.

**Why the whole document is taught, and what it costs.** The pipeline used to run
`chunks.take(Prompts.MAX_CHUNKS)` — four chunks, ~720 words. That is the right context for a
photographed page and quietly wrong for a file: a 30-page PDF measured 7,527 words, so a student
importing their unit notes got a lesson about pages 1–3 and a quiz that could not ask about anything
else. The rest was stored and indexed, so Ask could already reach it, which made the gap look like a
parser bug.

Now the document is taught in prompt-sized sections and the output concatenated: section 1's key
points are ordinals 0–4, section 2's are 5–9, so every reader downstream still sees one ordered
lesson and needed no change. The cost is linear and not small — roughly 90 s per section, so a
30-page PDF is a ~15-minute import. Sections are not batched into one bigger prompt because they
cannot be: the model gives up sooner the more text precedes the instruction. If it ever needs to be
cheaper, the lever is generating section 1 at import and the rest on first open, which needs a
per-section artifact key rather than a flat ordinal.

Each artifact is attempted independently — a quiz the model mangles must not cost the student their
explanation, and one bad section must not cost them the other nine.

`translateInto` translates quiz items at import too, rather than lazily per question. Lazily would be
cheaper in total work, but it would move a model swap into the middle of someone answering a quiz,
and the swap is the expensive part, not the translating.

---

## 9. Translation

### `LessonTranslator`

- **`MAX_WORDS = 18` per fragment, and splitting only when a sentence genuinely cannot fit.**
  Splitting is not free: an earlier ten-word limit cut *"A plant makes its own food inside its
  leaves, using nothing but sunlight, water and air."* at the comma, and the orphaned clause came
  back as *"कुछ भी नहीं पानी और हवा"* — grammatically broken, because a translation model needs the
  whole clause to resolve grammar. A whole sentence translated well beats two fragments translated
  badly.
- Split ladder: sentence → `,` `;` `:` → clause words (` because `, ` which `, ` although `,
  ` however `, ` whereas `, ` while `, ` that `, ` and `, ` but `, ` so `, ordered longest-first so
  " which " is not matched as a prefix of something shorter) → hard split at the word limit.
- `Fragment(text, trailing)` — punctuation travels *beside* the fragment rather than being rebuilt
  afterwards. The translator never sees it, so it can never mangle it, and the rejoin is a
  concatenation rather than a guess.
- Memo keyed by `(languageCode, sourceText)`, because the same English sentence renders differently
  per language.
- **All work inside ONE `withTranslator` block.** `targetId` is resolved once per render, not per
  sentence. `translate()` is synchronous, blocking and not thread-safe, so fragments go one at a time
  on `Dispatchers.Default` inside that single acquisition.
- A fragment the engine chokes on falls back to its English text rather than blanking the line.
  Partly-translated output beats a hole.

### `normalizePunctuation` — four real device artifacts

1. The model detokenizes punctuation as separate tokens, so raw output reads `"…बन जाते हैं ।"` with a
   space before the danda, and Sanskrit came back with `" ,"` mid-sentence. `TIGHT_PUNCTUATION`
   closes that gap. Only spaces are dropped, never a preceding letter, so this cannot merge two words.
2. **IndicTrans2's Odia output writes sentence-final `|` (U+007C) rather than `।` (U+0964)** — an
   artefact of the Odia training corpora, where the two are routinely conflated. Left unrepaired, an
   Odia lesson ends every sentence with an ASCII pipe.
3. The model picks its own sentence terminator, which is how Marathi rendered a Latin full stop even
   though `SupportedLanguage.MARATHI` declares a danda: `joinTranslated` saw the fragment as already
   terminated and dropped the danda it was about to add. Normalising here makes the enum
   authoritative, which is what the rest of the class already assumes. Applied to the **final
   character only**, so decimals and abbreviations mid-fragment are untouched.
4. `joinTranslated` does not append a fragment's original punctuation when the translation already
   ends in a terminator of its own — appending unconditionally produced `"…होती है ।।"` in Hindi and
   `"…ہوتا ہے۔।"` in Urdu, where a danda was being stuck onto text that already ended with the Arabic
   full stop.

`SENTENCE_ENDINGS = ['.', '।', '۔']`. `!` and `?` are deliberately absent: they carry meaning the
language's own full stop does not, and are written the same way in every script here.

### `BrahmicTransliterator`

- Devanagari block `0x0900`–`0x097F`. Anything outside — spaces, Latin, punctuation, digits copied
  from the source — passes through untouched.
- **Danda `0x0964` and double danda `0x0965` are shared punctuation.** Every Brahmic script uses them
  as-is; offsetting them lands on unrelated letters.
- **Devanagari digits `0x0966`–`0x096F` *are* remapped**, so a translated sentence does not mix
  scripts.
- **Unassigned target code points are skipped, not emitted.** Not every position is assigned in every
  block — Tamil has no aspirated or voiced stops, so `ख` + 0x280 is unassigned. A model translating
  *into* Tamil should never emit those, but "should never" is not "cannot", and a missing character
  is less damaging on screen than a row of replacement boxes.
- Verified by hand against real model output: `प`+0x280 → `ப`, `ि`+0x280 → `ி`, `ऴ`+0x280 → `ழ`, all
  exact.

IndicTrans2 relies on the same layout: it uses the extended Devanagari letters `ऴ ऱ ऩ ळ` precisely
because they have counterparts at the same offsets in the southern scripts, which makes the round
trip lossless.

---

## 10. Learning Twin

### `Mastery` — a fixed-shape record, not a history

The obvious design is a table of every quiz answer, queried later. That table existed here once and
was deleted, unread. The reason to prefer this shape is not that the log was unused: **a log grows
without bound**, and the thing anyone actually wants from it — how well is this understood, how sure
are they, when will they forget it — is a handful of numbers that can be updated in place. Everything
aggregates into a row of fixed width. Nothing accumulates.

**Mastery and confidence are two numbers rather than one** because a student can be confident and
wrong, or correct and unsure. Those need opposite responses — the first is a misconception to
correct, the second is fragile knowledge to reinforce — and a single combined score cannot tell them
apart. Mastery is measured from answers; confidence is inferred from **answer latency**, which is the
only confidence signal available without asking "how sure were you?" after every question.

| Constant | Value |
|---|---|
| `LEARNING_RATE` | 0.4 — a moving average, so one unlucky run cannot erase a history of good ones and one lucky run cannot claim mastery |
| `MIN_EASE` / `MAX_EASE` | 1.3 / 2.5 |
| `MAX_INTERVAL_DAYS` | 365 |
| `FAST_MS` / `SLOW_MS` | 6,000 / 20,000, linear between |
| `isConfidentlyWrong` | confidence ≥ 0.6 **and** mastery < 0.5 |

**SM-2, graded from the score** rather than from a self-rating the app never asks for:

- `observed ≥ 0.8` → ease + 0.1, interval `ceil(intervalDays × easeFactor)`
- `observed ≥ 0.5` → ease − 0.15, interval `ceil(intervalDays × 1.2)`
- otherwise → ease − 0.2, **interval reset to 1**. Reset, not reduced: a document scored below half
  is one the student has lost, and spacing it out again from a long interval would be scheduling them
  to forget it.

**Forgetting is applied on read, not by a background job.** `decayed()` is exponential, halving over
one interval's worth of days past the due date. There is nothing to schedule, nothing to run while
the app is closed, and no way for a missed job to leave a stale number in the database. The stored
value is always "what they knew when last tested", which is the honest thing to store; what they
probably know today is derived.

Marked `ponytail:` in the source — a fixed learning rate, not the item-response-theory update this
wants. IRT needs a calibrated difficulty per question and the generator does not emit one. Upgrade
path recorded: have the quiz generator persist a difficulty estimate per item, then weight the update
by it.

Device-proven: scored 3 of 5, logged `doc 2: mastery 24%, confidence 0%, due in 1d` (0.4 × 0.6 = 0.24
✓), survived a force-stop.

### `Revision` — a transparent weighted sum

A learned ranker needs training data this app does not have and, worse, cannot explain itself. The
student is entitled to know why they are being asked to revise something, and "because you scored 40%
on it and it fell due yesterday" is an answer a weighted sum can give and a neural ranker cannot.

| Weight | Value | Why |
|---|---|---|
| `W_MASTERY_GAP` | 1.0 | not knowing it |
| `W_OVERDUE` | 0.6 | below the gap weight — something never learned matters more than something learned and slightly late |
| `W_CONFIDENTLY_WRONG` | 0.3 | small, but it breaks ties toward misconceptions, which the student will never pick because they do not know they have them |
| `OVERDUE_SATURATION_DAYS` | 14.0 | a month overdue and a year overdue are both "forgotten"; without a cap one ancient document would outrank everything forever |
| `MASTERED` | 0.85 | at or above this and not yet due → dropped |

A never-quizzed document scores the full gap — it is entirely unknown, which is exactly the state
revision exists to fix.

The UI shows **exactly one** ("Study next: X"), hidden when nothing is due. A ranked list of
everything a student is behind on is a list of reasons to feel behind. The row it points at already
shows what is known and whether it is due, so the line does not restate the reason.

### `LearnerExport` — two formats

"Export your data" means two different things to two different people. A parent wants to read it; a
student moving devices, or anyone checking the claim that this is a compact model rather than a
transcript, wants to parse it. Emitting only JSON satisfies the letter of an export and none of the
point; emitting only prose makes the data unusable anywhere else.

**Neither file contains document text.** The export is what the app concluded — mastery, confidence,
schedule — not a copy of the student's own material, which they already have. `Locale.US` timestamps
deliberately, because a date whose format depends on the exporting phone's locale is a date that has
to be guessed at. `schemaVersion: 1`.

> **Trap found and closed here:** a per-file `writeExport` cleared the export directory on every call,
> so writing the second file deleted the first and the share silently offered a Uri pointing at
> nothing. Now one `writeExports(List<Pair<name, content>>)`, so the misuse is unexpressible.
> Clearing and writing belong in one operation.

---

## 11. Privacy

### The permission list is empty, and that took more than not declaring one

Not declaring `INTERNET` is not the same as not having it. ML Kit pulls in
`com.google.android.datatransport:transport-backend-cct` — Google's telemetry uploader — which
declares `INTERNET` and `ACCESS_NETWORK_STATE` in its own manifest, and the manifest merger adds them
to yours. **Every APK built from this repository before that was found requested network access**, and
a user checking the app's permissions would have seen it.

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

This is *stronger* than never declaring them: the process becomes incapable of opening a socket, so
the guarantee is enforced by the OS rather than by our intentions. Confirmed in the merger report:

```
> uses-permission#android.permission.INTERNET
  ADDED from .../app/src/main/AndroidManifest.xml
  REJECTED from [com.google.android.datatransport:transport-backend-cct:2.3.3]
```

> **The general lesson: always check `app/build/outputs/logs/manifest-merger-debug-report.txt`, not
> just the source manifest, when auditing permissions on any Android project.** A transitive
> permission is invisible in source and obvious in the APK.

Re-verified for this report: the merged manifest carries exactly one `uses-permission`,
`com.learnbridge.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, injected by androidx.core at
`signature` protection level — the app granting itself a permission it also declares. It grants
nothing to any other app and no platform capability. **Zero external permissions** is the accurate
claim.

Also deliberately absent:

- **`CAMERA`** — page capture uses `ACTION_IMAGE_CAPTURE`, which hands off to the system camera app.
  Declaring CAMERA would make the platform enforce it for that intent for no benefit.
- **`RECORD_AUDIO`** — was declared for spoken questions through the offline Vosk recognizer. That
  feature was never wired up: no microphone control existed in any layout and the recognizer had no
  call sites. A dangerous permission for code that cannot run is worse than a missing feature, so the
  permission, the recognizer and its 134 MB of acoustic models are all gone.

### Backup is restricted, not disabled

Both rules files must be *named* in the manifest or they have no effect at all. They existed and the
`<application>` tag referenced neither, so the platform applied its defaults — **which include the
`database` domain**, meaning every sentence extracted from a photographed textbook page was eligible
for upload. Auto Backup is performed by the system, so the deliberately absent INTERNET permission
does not prevent it.

| Domain | Cloud backup | Device transfer |
|---|---|---|
| `database` — documents and quiz history | **excluded** | allowed; moving handsets should not cost a student their library |
| `file` / `external` — baked `.ort` caches and extracted `.onnx` sources, hundreds of MB, regenerable, far past the ~25 MB quota | excluded | excluded |
| `sharedpref` — teaching language, residency decisions; a few bytes, genuinely useful, not study content | included by omission | included by omission |

### FileProvider grants exactly two directories

`captures/` so the system camera can write a photographed page in, and `export/` so the student can
send their own Learning Twin somewhere. Neither is a whole-of-`filesDir` grant: the documents, the
database and the extracted text stay unreachable to every other app. `export/` is the one path by
which anything leaves this app, and it only opens when the student asks.

### The Privacy Centre

"Your data" beside the Library title opens a dialog with a plain-language summary and exactly two
actions — Export, and Delete everything (asked twice: once by opening the dialog, once by
confirming). A dialog rather than a settings screen, because there are two actions and no settings.
The summary is plain language on purpose: this is where a claim about privacy is either kept or
merely asserted, and it is read by parents at least as often as by students. Device-verified end to
end.

`eraseAllLearnerData()` is the **one** deletion entry point — database, captures and exports. It is
one method rather than three calls at the call site, and that is the point: deletion used to be
"empty the database, then prune the captures", and the export directory — which holds a file spelling
out what the app concluded about the student — was simply not on that list. A control labelled
"delete everything" that leaves their data on disk is the one bug in here that would have mattered
most. **Anything added to this app that persists learner data belongs in that method, not in a
caller.**

### Verified offline

With the device in airplane mode (`airplane_mode_on = 1`, Wi-Fi disabled, `ping 8.8.8.8` → *Network
is unreachable*), a photographed page was imported and became a full bilingual lesson:

```
DynamiteModule: Selected local version of com.google.mlkit.dynamite.text.devanagari
nativeloader: Load .../base.apk!/lib/arm64-v8a/libmlkit_google_ocr_pipeline.so ... ok
Loading mlkit-google-ocr-models/gocr/.../Latn_ctc/optical/conv_model.fb
Loading mlkit-google-ocr-models/gocr/.../Deva_ctc/optical/conv_model.fb
PipelineManager: OCR process succeeded via visionkit pipeline.
LessonPipeline: doc 3: 5 key points
LessonPipeline: doc 3: 5 quiz items
```

Text recognition selected its **local** module and loaded its native library and models from inside
the APK.

---

## 12. Text-to-speech

### Trap 1 — never use `isLanguageAvailable` for an offline voice check

It answers with what the engine could *obtain*, not with what is present. Measured on the SM-M315F:
all fourteen lesson locales returned `LANG_COUNTRY_AVAILABLE` while `engine.voices` held **39
installed English voices, 9 Hindi, and zero for the other twelve** — they exist as `Voice` objects
carrying `KEY_FEATURE_NOT_INSTALLED`, a download this app can never do.

So `speak`'s English fallback never fired, the engine was handed Tamil in a voice it did not have,
and **Listen went silent with no log**, on exactly the languages this app exists to serve.

`voiceAvailable` now matches on an *installed* `Voice`, **language-only** — the engine ships `hi-IN`
voices for a request for `hi`, and a student who has the language installed for another region should
be read to rather than told to install something they already have.

### Trap 2 — `TextToSpeech.speak` rejects input over 4,000 characters outright

It returns ERROR and says nothing at all. One key-point list was five lines and could not approach
that; a whole-document lesson is every section's key points concatenated and reaches roughly 7,000
characters. Without segmentation, Listen would go silent on exactly the longest documents — the least
debuggable way for it to fail.

`segmentsFor(text, limit = 3900)` splits preferring a sentence end in the back half of the window,
then a space, then a hard cut. Terminators include the danda and the Urdu full stop. Queues
FLUSH for the first segment, then ADD.

### Trap 3 — the engine is picked, not inherited

`PREFERRED_ENGINES = ["com.google.android.tts", "com.samsung.SMT"]`, else the system default. The
default on a Samsung device is Samsung TTS, which on the SM-M315F carries English and Korean; Google's
engine is the only one with voices for all thirteen lesson languages. Inheriting the default sends a
student to download a Tamil voice from an engine that will never have one.

Chosen by package name rather than by asking each engine what it supports, because an engine reports
availability for voices it would have to download — that question cannot separate "has Tamil" from
"could fetch Tamil" at construction time.

Requires `<queries>` for `TTS_SERVICE` and `INSTALL_TTS_DATA` in the **engine** module's manifest, or
API 30+ shows an empty engine list. Present and correct.

### Voice download hand-off

**The app can never fetch a voice itself.** It holds no INTERNET permission, which is the point of
it. `Tts.voiceDataIntents()` returns, best first: `ACTION_INSTALL_TTS_DATA` aimed at the chosen
engine package (so a device with two engines does not open the one lacking Indic voices), then the
platform's text-to-speech settings page — kept because implementing `INSTALL_TTS_DATA` is *optional*
for an engine, and a dead first choice would leave the student with instructions and no door.

Device-verified: the intent foregrounds
`com.google.android.tts/…voicepack.ui.VoiceDataInstallActivity`.

Offered once per language per lesson visit, then downgraded to the old toast — repeating a dialog on
every Listen for someone who has already declined would be worse than the silence it replaced.
**Fully automatic install is not buildable and was not attempted**: there is no network permission to
do it with, and tens of megabytes of possibly-metered data needs a human yes.

A **Voices** entry beside "Your data" lists all fourteen languages with installed/missing, and a tap
on a missing one opens the installer. Listen's prompt got the same list as a third button — a student
who wants a different language's voice, or wants to see what is already installed before spending
data, would otherwise have to decline and go hunting. **No "download all"**: thirteen voices is a few
hundred megabytes of somebody's data plan, and a student studying in Tamil needs Tamil. Shared
`ui/Voices.kt` so the Lesson and Library copies cannot drift — the second copy would be the one that
forgets `runCatching`.

The list waits for engine init, because `voiceAvailable` answers false until then and a list shown
too early would report every language as missing.

---

## 13. UI

### Never gate an intent on `intent.resolveActivity()`

From API 30 that call answers through the package-visibility filter and returns null for apps this
one cannot see — so the check reports "nothing can receive this" on exactly the devices where the
thing works fine. This trap was hit three times: the camera, the export share, and the voice
download. All three now start the intent inside `runCatching`. The chooser itself always resolves,
and it is the component that tells the student when genuinely nothing can handle the files.

### The camera capture bug

**Symptom:** photograph a page, come back, nothing happens. **Evidence:** three orphaned 1–5 MB JPEGs
in `files/captures` — the camera had written them, so `pruneCaptures` (which runs on the ingest flow's
completion) had never run, so ingest was never reached. Two silent doors:

1. `pendingCaptureUri` was a plain field, lost to process death behind the camera app. On the
   low-memory phones this app targets that is a routine event, not an edge case. Now saved and
   restored through `onSaveInstanceState`.
2. The result code was treated as the authority on whether a photo exists. **Now the file on disk
   decides** (`hasContent(uri)` reads a single byte); the code is only logged. A camera app writes the
   file before it returns, and some — Samsung's among the reported cases — then return something other
   than `RESULT_OK` anyway. A genuine cancel leaves no file, so it still falls through to silence,
   which is correct.

### Streamed tokens are deliberately not part of state

Folding one token per emission into the state class would re-render the whole screen at generation
speed — ten to forty times a second — for a change only one TextView cares about. `tokens` is a
separate `SharedFlow<String>`; the Activity appends directly.

But **only while Ask is the visible pane**: `contentText` is shared with Explain, and appending
regardless typed Gemma's answer onto the end of the bulleted key points. `streamed: StringBuilder`
buffers everything so the pane can be redrawn from scratch after a mid-stream detour through another
tab.

`contentText.text = streamed.toString()` — the `.toString()` matters. TextView keeps the
`CharSequence` it is handed, and handing it the live builder means every later append mutates text
the view believes it has already laid out.

The "thinking" line is cleared by the *first token*, not by a state change — there is no state
transition at that moment for `render()` to react to. An earlier version cleared it when Streaming
began, which left an empty pane with a disabled button while the teacher was still being acquired,
and that reads as a hang. Observed on device.

### The language toggle

A tap swaps between the two languages the document holds — a pure database read, no model touched,
which is exactly why it can be instant. A **long press** opens the full chooser, where a language the
document already has is a database read and any other starts a real render, marked as such before the
tap and shown inline while it runs.

Long-press rather than a second button: switching between the two languages a lesson already has is
the common action and stays one tap, while adding a thirteenth-language rendering — minutes of model
work — is deliberately the less casual gesture. It is announced in the toggle's content description
so it is not discoverable by accident only.

**The button is deliberately NOT gated on `translationAvailable`.** A document imported in English
carries no translation, and the long press is the only route to adding one — gating the button
disabled that route and stranded the lesson in English permanently. The tap is handled instead: with
nothing to toggle between, it opens the chooser.

The label is the target language's own **endonym** — हिंदी, மொழி, தமிழ், اردو — never a hardcoded
language name. A Tamil lesson must not offer a button that says "हिंदी".

### Quiz mechanics

- `shuffledOptions()` is seeded by `question.hashCode()`, so a given item shuffles the same way every
  time it is rendered. A re-render must not move the options under the student's finger, and a demo
  must be reproducible. Shuffling at all is necessary because the prompt asks the model to always put
  the answer on the `A:` line.
- **`preserveProgress` across a language toggle carries score, index and latencies — but deliberately
  not `selectedIndex`.** The translated question shuffles differently, so keeping the selection drew
  the tick and cross against the *previous* permutation: after toggling, the ✓ jumped to another row
  and the ✗ could land on an option the student never touched. The score was already banked and stayed
  correct, which made it read as the app having forgotten the answer. Latencies do come along — how
  long a student took is a fact about them, not about the language it was shown in.
- **`QuizUi.current: QuizItem?` is the single read path.** `isDone` is false for an *empty* quiz (it
  means "reached the end of a list that exists"), so a guard written in terms of it does not cover the
  one case where there is nothing to index. That is what let an empty quiz reach `items[0]`.
- `answer()` is a no-op once answered or once the quiz is done, so a double-tap cannot double-count.
- `shownAt == 0` is guarded: a quiz restored without a display timestamp would otherwise record a
  latency measured from the epoch — roughly fifty-six years of hesitation.
- The Twin is committed **once per completed quiz**, not per answer: a single question answered in
  isolation is a much noisier signal, and per-answer updates would let a student walk away mid-quiz
  having moved their own score.

### Accessibility

Selection is **never colour-only**: bold weight plus `View.isSelected` (read aloud by screen readers)
change alongside the background. Correct/incorrect carries a ✓/✗ glyph prefix as well as a tint.
Content descriptions throughout, including one that announces the long-press gesture, which is
otherwise invisible to a screen reader.

RTL is honoured via `SupportedLanguage.rtl` → `TEXT_DIRECTION_RTL` plus
`TEXT_ALIGNMENT_VIEW_START`. The platform gets bidi inside an Urdu paragraph right on its own, but
nothing was reading `rtl`, so the paragraph itself sat left-aligned in an LTR layout — correct
character order, wrong page.

### Localisation

Adding `values-ta/` and friends does nothing on its own. The app never called
`setApplicationLocales`, so Android resource qualifiers only fired when the *device* locale already
matched, and the in-app "Teach me in" picker never touched the chrome. Now
`LearnBridgeApp.targetLanguage`'s setter calls `AppCompatDelegate.setApplicationLocales` — the one
path every language change takes — plus `res/xml/locales_config.xml` for the platform's per-app
language page.

Startup deliberately does **not** re-apply it. AppCompat persists its own choice and restores it
before the first Activity of the next launch; re-applying would force Hindi chrome on a student who
has never opened the picker, purely because Hindi is the default *teaching* target.

There is no route back to English chrome from the in-app picker, because English is the source
language and never a teaching target — Settings › Apps › LearnBridge › Language offers it. Marked
`ponytail:` with the upgrade path.

**Current coverage: 71 keys in each of the thirteen `values-*` directories, 72 in the default**
(`app_name` is `translatable="false"`). All machine-drafted and flagged unreviewed in the README.

### The renderer/state split

`LessonActivity` is a pure renderer of `LessonViewModel.state`: `render(state)` reads one immutable
snapshot and mutates views, holding no business state of its own. The two exceptions are the Ask
`EditText`'s own text, left to Android's normal view-state handling so typing never fights a
State→View→State feedback loop, and `streamed`.

---

## 14. Measurements

### Samsung SM-M315F — Exynos 9611, 4×A73 @ 2.3 GHz + 4×A53, 5,573 MB, Android 12, arm64-v8a (`RZ8N93BC18A`)

| | |
|---|---|
| Translation, 12-token sentence | **1,069 ms** (tokenize 26 / encoder 221 / decode 798) |
| Cold translation-engine load | 14.2 s, once per install |
| Process PSS, MT engine resident | 485 MB |
| Extractive lesson (5 points + 5 quiz items) | ~4.3 s |
| Gemma weights unpacked from the APK | 3.5 s, once per install (554,661,243 bytes) |
| Gemma engine load | 11.7–12.9 s cold, ~1.4 s warm |
| Explain / Quiz / Ask | 46.8 s / 45.1 s / 38.3 s |
| Model swap (teacher → translator) | **18.4 s** |
| Full ingest, text file → bilingual lesson | ~55 s |
| Full import wall time | ~104 s |
| `TeacherQualityDeviceTest` | 143.5 s |
| PDF OCR, 51 pages | 67 s, 652 → 3,144 words |
| `TtsVoiceDeviceTest` | 21.3 s |
| `DocumentFormatsDeviceTest` | 74 s |

Ask is correctly grounded: "Where does the plant get water?" → "Roots absorb water from the soil."

### vivo V2338 — SM6450 / Snapdragon 6 Gen 1, Android 16, 7.6 GB (`10BE5R0C0D000FG`)

Same APK (arm64, 1.05 GB, installs in 48 s), same document, same seed and sampling.
**Two to three times faster on every stage:**

| | SM-M315F | vivo V2338 |
|---|---|---|
| Engine load | 12–13 s | **5.3 s** cold / 1.3 s warm |
| Explain | 46.8 s | **33.1 s** (5 of 5 key points) |
| Quiz | 45.1 s | **9.4 s** |
| Ask | 38.3 s | **12.8 s**, correctly grounded |
| Whole `TeacherQualityDeviceTest` | 143.5 s | **65 s** |

Quiz parsed **1 of 3** with silly distractors ("A water plant") — the documented seed-noise norm, not
a device regression.

**Two structural differences that change what this device can test:**

1. `ModelHost` logs `totalMem = 7462 MB`, above the 6,500 MB floor, so it runs `tier = CO_RESIDENT`.
   **The 18.4 s swap does not happen on this phone at all.**
2. Google TTS has **all fourteen lesson voices installed** — every one a country match,
   `TtsVoiceDeviceTest` green in 13.0 s — where the M31 had only `[en, hi]`. So Listen works in all
   thirteen languages out of the box and the `ACTION_INSTALL_TTS_DATA` hand-off can never fire here.

> **Keep the SM-M315F for voice-download and model-swap work.** The vivo cannot exercise either path.

This run also **re-proved F17 on second hardware**: the external files directory was empty, so
`stagedModel` unpacked the 528 MB asset out of the APK ("Unpacking … first run only") with no
`adb push` anywhere.

---

## 15. Packaging and distribution

### `noCompress += setOf("onnx", "bin", "pb", "task")` — load-bearing, not boilerplate

ONNX Runtime memory-maps model files from disk. A compressed asset cannot be mmapped and would force
a full decompress-to-memory on every load, taking resident memory from **~605 MB to ~1.1 GB** and
killing any chance of co-residency. Verified by checking the packaged APK for STORED (not DEFLATED)
`.onnx` entries.

The `.onnx` assets live in the `:engine` **library** module, but merged library assets are packaged by
`:app` — so this **app-level** setting is what must cover them.

### ABI splits, universal APK off

`arm64-v8a`, `armeabi-v7a`, `x86_64`. A single APK carrying every ABI plus the packaged models is not
installable on a mid-range phone. x86_64 is included so the app installs on a standard emulator — the
split was arm-only, which meant anyone without a physical arm64 phone (a reviewer, a CI runner, a
teammate) could not run the app at all. MediaPipe publishes no x86_64 generative library, so on that
ABI the app falls back to `ExtractiveTeacher` — the same supported degradation a low-memory phone
takes, and every feature still works.

### F17 — Gemma ships inside the APK

MediaPipe's `LlmInference.LlmInferenceOptions` has `setModelPath` and **no asset or file-descriptor
variant** (checked against the 0.10.27 artifact), so a packaged model must be copied out once.
`GemmaTeacher.stagedModel(context)` = `modelFile()` (the adb-pushed copy, which keeps development
iteration cheap) **or** `unpackFromAssets()`.

The unpack writes to a `.part` file and renames, so a copy killed halfway — the app backgrounded, the
phone dead — leaves no truncated model to be loaded as if it were whole; `modelFile` only accepts the
final name. It refuses outright if `dir.usableSpace < size`, because a device that close to full will
fail somewhere less obvious later.

> Both device tests now resolve through `stagedModel`. They used to probe `modelFile` and therefore
> **skipped silently on exactly the build that ships.**

Verified from a clean install with nothing pushed: unpack 3.5 s, 554,661,243 bytes in `filesDir`,
engine ready 11.7 s, all three prompts generated. Re-verified on the vivo.

### Assets currently on disk

| File | Bytes | MiB |
|---|---|---|
| `app/src/main/assets/gemma3-1b-it-int4.task` | 554,661,243 | 529.0 |
| `engine/src/main/assets/decoder_init_int8.onnx` | 203,610,866 | 194.2 |
| `engine/src/main/assets/decoder_step_int8.onnx` | 193,990,864 | 185.0 |
| `engine/src/main/assets/encoder_int8.onnx` | 74,932,993 | 71.5 |
| `engine/src/main/assets/dict.TGT.json` | 3,390,440 | 3.2 |
| `engine/src/main/assets/dict.SRC.json` | 645,314 | 0.6 |

**No model weights are committed.** The Gemma Terms of Use travel with the weights. The README
documents both staging routes — packaged (what the release APK does) and pushed (what development
does, so a rebuild does not move 1.05 GB). A pushed copy wins, so staging the asset does not take the
fast path away from a developer who already has one on the device.

### Built APKs

| ABI | Bytes | MiB |
|---|---|---|
| arm64-v8a | 1,101,831,273 | 1050.8 |
| armeabi-v7a | 1,085,007,601 | 1034.8 |
| x86_64 | 1,110,568,669 | 1059.1 |

### F13 — distribution is blocked

**Play's base-APK ceiling is 200 MB.** Distribution needs an AAB with **install-time asset packs**
for the 459 MB translation model and the 554 MB `.task`, which keeps the offline guarantee intact
after install — unlike on-first-run download, which would break the entire premise. Direct-download
distribution (a GitHub release) is unaffected.

### F12 — release builds sign with the debug key

`buildTypes.release` uses `signingConfigs.getByName("debug")` and `optimization { enable = false }`.
Not distributable. Blocked on a keystore.

### Install requirements (README)

| | |
|---|---|
| Device | arm64 Android 8.0+; a 32-bit or emulator install falls back to the extractive tutor |
| Free space | ~1.7 GB — the APK plus the 554 MB the model is copied out to |
| First lesson | pays a one-off unpack (3.5 s measured) before the usual ~12 s engine load |
| Memory | ~2 GB RAM devices load it; below that the app degrades rather than failing |

---

## 16. Tests

**241 JVM tests, all green, 0 skipped, 0 failures.** No device required.

| File | Tests |
|---|---|
| `lang/LessonTranslatorTest` | 33 |
| `teach/LessonParserTest` | 26 |
| `ui/LessonViewModelTest` | 25 |
| `teach/ExtractiveTeacherTest` | 16 |
| `doc/MasteryTest` | 14 |
| `doc/DocStoreTest` · `doc/RetrievalTest` · `doc/RevisionTest` · `doc/TextExtractTest` | 11 each |
| `teach/LessonPipelineTest` | 10 |
| `doc/ChunkerTest` · `lang/BrahmicTransliteratorTest` | 9 each |
| `doc/LearnerExportTest` · `EraseLearnerDataTest` · `bench/MetricsTest` · `mt/TokenizerTest` · `mt/CpuCapabilitiesTest` | 6 each |
| `doc/DocStoreMigrationTest` · `mt/DecoderTest` · `speech/TtsSegmentTest` | 5 each |
| `bench/StatsTest` | 4 |
| `teach/GemmaUnpackTest` · `mt/ExecutionPolicyTest` | 3 each |

`DocStore` is `open`, with `open` writes, for exactly one reason: `LessonPipelineTest` subclasses it
to make a write fail on demand. The invariant it checks — nothing thrown inside the pipeline reaches
the collector — cannot be exercised otherwise, because `SQLiteOpenHelper` reopens a closed database
on the next `writableDatabase` call.

Regression tests are named after the device observation that produced them: headings selected as key
points, OCR-wrapped lines discarded, "every"/"together" chosen as quiz answers, `।।` double-danda in
Hindi, an ASCII pipe as a danda in Odia, the SystemVerilog duplicate-option pair.

### Five device test classes, nine tests

| Class | What only a device can prove |
|---|---|
| `teach.TeacherQualityDeviceTest` | Runs Explain + Quiz + Ask against the real weights and logs raw output, parsed counts and wall time under tag `TeacherQuality`. Sampling overridable from instrumentation arguments. Assertions cover only what is unambiguously broken — nothing generated, or nothing parsed from a non-empty response — because whether five key points are *good* key points is a human call. |
| `teach.TurnMarkerDeviceTest` | The F16 A/B on real weights. |
| `lang.AllLanguagesDeviceTest` | Loads the translation engine once, translates one English sentence into all thirteen targets, asserts each result lands in that language's own Unicode block derived from `scriptOffset` — so it verifies the *model's* output, not hand-written input. |
| `lang.TtsVoiceDeviceTest` | The engine's installed-voice list against what `Tts` reports. |
| `doc.DocumentFormatsDeviceTest` | Zipped office documents through a real `ContentResolver` (the two-pass zip read depends on a Uri being openable twice, which Robolectric cannot model) plus the PDF OCR fallback. An absent PDF fixture logs why and passes — an absent fixture is not a regression. |

### Robolectric traps, all three real

1. **It cannot resolve FileProvider roots on Windows** ("Failed to find configured root") even with a
   correct `file_paths.xml`. Do not unit-test anything ending in `getUriForFile`; seed files directly
   and device-verify that path.
2. **It discards `android.util.Log` unless `ShadowLog.stream = System.out`.** Set it in any test whose
   subject reports failure by logging and returning an empty value.
3. **Use `registerInputStreamSupplier`, not `registerInputStream`** — the format sniff opens a Uri
   twice, and the single registered stream comes back exhausted, which reads as "that document looks
   empty".

### CI

`.github/workflows/tests.yml` runs `:app:testDebugUnitTest :engine:testDebugUnitTest`, then
`:app:lintDebug` as its own step so a lint regression reads as a lint failure rather than a test
failure, and uploads reports on failure. Temurin 21; `android-actions/setup-android@v3` because
ubuntu-latest ships an Android SDK but not platform 36.1.

It never assembles an APK and does not try — the weights are not in the repository, and the one test
needing a staged vocabulary skips itself with `assumeTrue`, so a weightless checkout is a green
checkout rather than a lie.

**The workflow has never actually executed** — `gh` is not authenticated locally.

---

## 17. Audit findings F1–F17

`AUDIT.md` at the repo root, dated 2026-07-26, commit `f14c29e`. **Score 78 / 100**, up from 64.

| Dimension | Score |
|---|---|
| Idea and architecture | 9 / 10 |
| Correctness and robustness | 8 / 10 |
| Test coverage and honesty | 9 / 10 |
| Product completeness | 6 / 10 |
| Code clarity | 9 / 10 |
| Dead weight | 6 / 10 |
| Release readiness | 3 / 10 |
| **Security and privacy** | **10 / 10** |

| # | Finding | Status |
|---|---|---|
| F1 | Ask answered English-only | **closed** — rendered into the lesson's language; `AskOutput.Rendering` shows the swap; `Final.lang` drives the TTS voice |
| F2 | Streaming tokens leaked into the Explain pane | **closed** |
| F3 | UI chrome English for 12 of 13 languages | **closed** — took two commits, because the audit's framing was half the fix |
| F4 | Captured pages accumulated forever (2–4 MB each) | **closed** — `pruneCaptures` off the ingest flow's completion |
| F5 | Ingest died with the Activity | **closed** — `LibraryViewModel` + `repeatOnLifecycle(STARTED)`; Done/Failed consumed as events |
| F6 | 3.7 MB of unreachable HI→EN vocabulary | **closed** |
| F7 | `SupportedLanguage.rtl` never read | **closed** |
| F8 | `quiz_results` was write-only | **closed** — table dropped in migration v2 |
| F9 | No re-render into a second language after import | **closed** — long-press chooser |
| F10 | `translationLanguage` nondeterministic by construction | **closed** — `translationLanguages` with `SELECT DISTINCT … ORDER BY` |
| F11 | `BeamSearchDecoder` had no production call site | **closed** — deleted rather than left to rot unmeasured; with the current uncached decoder graph it costs a full forward pass per beam per step. `MtEngine` still takes its decoder as a parameter if it comes back |
| **F12** | Release builds sign with the debug key, no shrinking | **OPEN — blocked on a keystore** |
| **F13** | APKs are 1.03–1.06 GB against Play's 200 MB base ceiling | **OPEN — needs an AAB / asset-pack decision** |
| F14 | `onUpgrade` dropped all four tables | **closed** — stepped migrations; had to be, because the Twin is schema v2 |
| F15 | No CI | **closed** — workflow exists; **never executed** |
| F16 | `applyTurnMarkers` unverified | **closed 2026-07-28** on real weights |
| F17 | The generative tutor was not in the APK | **closed 2026-07-28**, re-proved on second hardware 2026-07-30 |

**Open question, unanswered.** `SupportedLanguage` declares Gujarati and Marathi with `।` as their
terminator, and the punctuation normalizer now enforces it. Modern usage in both often prefers `.`.
One character each to change if that is the call.

---

## 18. The simplification pass

A whole-codebase pass on 2026-07-28 (five commits, `1501366`..`99b56c0`) **found very little, and
that is the finding.**

Only real wins: fully-qualified names that should have been imports; one duplicated language-chooser
dialog (now `ui/LanguageChooser.kt`, used by both Activities); `DocStore`'s repeated cursor loop and
eight-column mastery SELECT; `LessonViewModel`'s duplicated primary/fallback read and threefold pane
reload; four duplicate `STOP_WORDS` entries.

**Net −3 executable lines.** The raw +46 is all KDoc. Both choosers and the language toggle were
device-verified, since JVM tests do not reach those paths.

> **Do not re-attempt a big restructure here; there is nothing left to win.**

Deliberately not done: view binding for `LessonActivity`'s 21 `findViewById` calls — it would rename
roughly a hundred references for a boilerplate-only gain.

---

## 19. Honest limitations

- **An Ask answer costs a wait that Explain does not.** The tutor generates in English and the answer
  is then rendered into the lesson's language, which can force a model swap mid-conversation —
  measured at ~14 s. The screen says so while it happens. Explain and Quiz avoid it entirely by being
  rendered once, at import.
- **The generative quiz produces about one question per section, not the three it asks for.** Seed
  noise on a 1B capacity ceiling; sampling was swept and every arm was equal or worse. Volume comes
  from teaching the document in sections.
- **Hindi input is not supported.** The engine can do HI→EN, but neither its graphs nor its
  vocabularies are staged, so the direction would fail at model load rather than merely go unused.
  Routing a document through HI→EN → tutor → EN→HI would stack three lossy hops anyway.
- **The extractive tutor selects, it does not paraphrase.**
- **Greedy decoding drifts on long sentences**, which is why long text is split before translation
  rather than truncated after.
- **"Plant" sometimes renders as "संयंत्र"** (industrial plant) rather than "पौधा" — lexical ambiguity
  in the translation model.
- **A second language costs a second render.**
- **The chrome translations have not been read by a native speaker.** Key coverage and format
  specifiers are checked by lint; wording, register and honorific level are not. Sanskrit is weakest —
  there is barely a modern software-UI register to draw on. Treat these as a starting point for a
  reader, not as shipped copy.
- **Transliteration is mechanical, not linguistic.** Tamil in particular reads slightly
  transliterated rather than natively composed.
- **PDF-image OCR mis-reads some Latin digits**, because the bundled recognizer is the Devanagari one.
- Portrait only. Debug signing. No release hardening.

---

## 20. Build and device commands

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # java is NOT on PATH

./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest
./gradlew :app:lintDebug
```

`adb` is not on PATH either:
`C:\Users\vishn\AppData\Local\Android\Sdk\platform-tools\adb.exe`

With `MSYS_NO_PATHCONV=1` set (needed so `/sdcard/...` is not mangled into
`C:/Program Files/Git/sdcard/...`), **host paths must be Windows-style** (`C:/Users/…`); a
`/c/Users/…` path fails with `failed to stat`.

### The trap that wastes a push every time

`connectedAndroidTest` **uninstalls the app when it finishes**, and that deletes
`/sdcard/Android/data/com.learnbridge.app/` — the staged `.task` with it. So the Gradle path can
never run twice in a row; the second run silently SKIPs. Install both APKs and drive instrumentation
directly instead:

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push gemma3-1B-it-int4.task \
  /sdcard/Android/data/com.learnbridge.app/files/gemma3-1b-it-int4.task
adb shell am instrument -w -e class com.learnbridge.app.teach.TeacherQualityDeviceTest \
  com.learnbridge.app.test/androidx.test.runner.AndroidJUnitRunner
```

Note the capital B in the Kaggle archive's filename (`gemma3-1B-it-int4.task`); the code wants
lowercase, so rename on push.

Device test classes:
`…teach.TeacherQualityDeviceTest` · `…teach.TurnMarkerDeviceTest` · `…lang.AllLanguagesDeviceTest` ·
`…lang.TtsVoiceDeviceTest` · `…doc.DocumentFormatsDeviceTest`

### The adb gotcha that cost a session

The SM-M315F dropped off USB mid-install and `adb devices` went empty while Windows still listed
`SAMSUNG Android ADB Interface`. **Not** a driver problem and not port 5037 — the fix is phone-side:
toggle USB debugging off and on, revoke authorizations, replug as File transfer, screen unlocked.

### Samsung camera device-test notes

Samsung's `ACTION_IMAGE_CAPTURE` flow ends on a **Retry / OK** confirm screen and writes the file only
on OK. On this 1080×2340 panel the shutter needs `input tap 539 2024` and the OK button
`input tap 773 2131`; `input keyevent 27` and the volume keys do not fire it.
`adb shell am kill com.learnbridge.app` will **not** kill it, so process death cannot be simulated
that way.

---

## 21. Licences

`THIRD_PARTY_NOTICES.md` at the repo root.

### Models

| Model | Licence |
|---|---|
| **IndicTrans2** — `ai4bharat/indictrans2-en-indic-dist-200M` (+ the `indic-en` mirror), ONNX-exported INT8 encoder / decoder-init / decoder-step | MIT — AI4Bharat, IIT Madras |
| **Gemma 3 1B IT** — `litert-community/Gemma3-1B-IT`, variant `gemma3-1b-it-int4.task` | **Gemma Terms of Use** + Prohibited Use Policy — Google LLC |

**The Gemma Terms of Use and the Prohibited Use Policy travel with the weights and apply to any
distribution of this application that includes them.** Redistribution must include the terms and the
use restrictions. This is why the weights are not in the repository, and why the README says
installing the release APK means accepting those terms.

Speech synthesis uses the platform `TextToSpeech` engine and installed voice data — part of the
device, so it carries no notice. Speech recognition (Vosk) was removed with the unwired
spoken-questions feature and is no longer a dependency or a packaged asset.

### Libraries

| Library | Version | Licence |
|---|---|---|
| ONNX Runtime (Android) | 1.27.0 | MIT |
| MediaPipe Tasks GenAI | 0.10.27 | Apache-2.0 |
| ML Kit Text Recognition Devanagari (bundled) | 16.0.1 | Apache-2.0 |
| PdfBox-Android | 2.0.27.0 | Apache-2.0 |
| AndroidX (core, appcompat, activity, constraintlayout, lifecycle) | various | Apache-2.0 |
| Material Components | 1.11.0 | Apache-2.0 |
| Kotlin stdlib / kotlinx.coroutines | 2.2.10 / 1.7.3 | Apache-2.0 |
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Robolectric | 4.11.1 | MIT |

Version pins are deliberate. ONNX Runtime is pinned to the version the engine was benchmarked
against (1.27.1 is a GitHub tag only; no AAR was published). MediaPipe LLM Inference is in
maintenance-only mode with LiteRT-LM as successor — which is a feature here: stable, published, and
it will not move underneath a build. PdfBox-Android's last release was January 2023 — unmaintained
but frozen, and verified against AGP 9.3.

---

## 22. What is left to do

1. **F12 — release signing.** Blocked on a keystore.
2. **F13 — the distribution decision.** An AAB with install-time asset packs, or direct download
   only.
3. **Run the CI workflow once.** It has existed since F15 and has never executed; `gh` is not
   authenticated locally.
4. **Device-verify the OCR path on the vivo.** The 67 s / 51-page figure is SM-M315F only.
5. **Decide the Gujarati/Marathi terminator question** (§17).
6. **Optional:** a second ML Kit recognizer pass for Latin script, if the mis-read digits in OCR'd
   PDFs turn out to matter.

Things deliberately **not** on this list, with the reason:

- More prompt tuning for the quiz. It is a model-capacity ceiling; seven versions and a full sampling
  sweep are recorded so the next person re-measures instead of re-arguing.
- A bigger model. Qwen2.5 1.5B was measured and rejected on time, size and grounding.
- A codebase restructure. The simplification pass found −3 executable lines.
- Automatic voice download. Not buildable without a network permission the app will never have.

---

*This report was produced from the repository at `23cb115` plus the working-tree changes described in
§1, with the JVM suite re-run (241 green) and `lintDebug` re-checked (0 errors, 74 warnings) at the
time of writing.*
