# LearnBridge AI — Full Audit

**Date:** 2026-07-26
**Commit audited:** `f14c29e` (working tree clean, 1 commit ahead of `origin/main`)
**Repo:** `github.com/Vishnu-3727/Learn_Bridge` · branch `main`
**Verification run for this audit:** `./gradlew testDebugUnitTest` — **155 tests, 0 failures, 0 skipped, 0 errors**

---

## 1. What it is

Offline Android study app. A student feeds it a document — text file, PDF, or a **photo of a textbook
page** — and gets back three things in their own language: five plain-language key points, a
five-question quiz, and free-text Q&A grounded in that document. Read-aloud on all three.

Two Gradle modules:

| Module | Package | Files (main) | Role |
|---|---|---|---|
| `:app` | `com.learnbridge.app` | 19 | ingestion, teaching, persistence, UI |
| `:engine` | `com.bhashabridge.app` | 14 | frozen IndicTrans2 ONNX translation stack + TTS |

The `:engine` namespace is deliberately *not* renamed to `com.learnbridge.engine`: `MtEngine` imports
`com.bhashabridge.app.Direction`, `.bench.Metrics`, `.logDebug` and `BuildConfig`. Renaming means
touching ~20 files and regenerating `BuildConfig` under a new namespace — 20 chances to break a
working, benchmarked inference engine. The cosmetic mismatch is free.

**Size:** 5,720 lines main, 2,240 lines test.
**Toolchain:** AGP 9.3.0, Gradle 9.5.0, JDK 17, compileSdk 36.1, minSdk 24, targetSdk 36.

---

## 2. The correct idea

The thesis: **a student with no data plan and a ₹8,000 phone should get the same tutor as a student
with fibre.** Everything runs on-device — and the app is honest about it, because the merged APK
cannot open a socket.

Three design bets carry that thesis. All three hold up in code.

### Bet 1 — one 459 MB translation export serves 13 languages

The target language is nothing more than **the second token of the source sequence**. No second
model, no reload, no additional resident memory. Switching Hindi → Tamil is a token change.

### Bet 2 — Indic scripts come free by Unicode offset

IndicTrans2 normalises every Indic language into a single script-unified **Devanagari**
representation and transliterates to the native script as post-processing. So the native script is
reachable by a fixed offset:

| Language | Offset | Language | Offset |
|---|---|---|---|
| Bengali | +0x80 | Telugu | +0x300 |
| Punjabi | +0x100 | Kannada | +0x380 |
| Gujarati | +0x180 | Malayalam | +0x400 |
| Odia | +0x200 | Tamil | +0x280 |

Counting subword pieces in the target vocabulary gives Devanagari 72,356, Arabic 16,949, Latin
11,414 — and **zero** multi-character pieces for Tamil, Telugu, Kannada, Malayalam, Gurmukhi and
Odia. Reading that as "those languages cannot be generated" was the wrong conclusion. The model
generates Tamil perfectly well; it writes it in Devanagari. The missing piece was a character
mapping, not model capability — see `BrahmicTransliterator`.

Urdu is the control case that proves the mechanism: Perso-Arabic is not Brahmic, cannot be reached by
an offset, and therefore has genuine subword coverage of its own in the vocabulary.

### Bet 3 — generate once at import, never on screen open

A 20-page document is ~12,000 tokens and will not fit any context this app can afford; decode runs at
single-digit tokens per second on mid-range silicon. So one pass at ingest produces the English
artifacts *and* their translation, writes both to SQLite, and every later screen open is a database
read. This is also what makes the language toggle instant.

`LessonPipeline` enforces the corollary: **exactly one model swap per document.** Generate every
English artifact under one `withTeacher`, then translate every fragment of all of them under one
`withTranslator`. Interleaving would pay the ~14 s swap per item.

**Verdict: the idea is sound and the code serves it.** That is the strongest finding in this audit.

---

## 3. Architecture

```
LibraryActivity ──pick / photograph──► LessonPipeline.ingest(uri): Flow<IngestProgress>
                                          │
                            DocImport ────┤  txt/md · PdfBox · ML Kit OCR (bundled)
                            Chunker ──────┤  180-word paragraph-greedy, 1-sentence overlap
                            DocStore ─────┤  SQLite: documents · chunks_fts(FTS4)
                                          │          artifacts · quiz_results
                            ModelHost ────┤  withTeacher    → Gemma 3 1B int4 | ExtractiveTeacher
                            LessonTranslator ─ withTranslator → MtEngine (13 langs, 1 engine)
                                          ▼
LessonActivity ◄── LessonViewModel ◄── DocStore reads only (no model is touched)
```

### Residency is a runtime decision, not an assumption

`ModelHost.Tier`:

| Tier | Trigger | Behaviour |
|---|---|---|
| `CO_RESIDENT` | totalMem ≥ 6,500 MB | both models stay loaded; toggles instant |
| `EXCLUSIVE` | 3,500–6,500 MB, or co-residency previously failed | one large model at a time; a swap costs ~7–14 s |
| `EXTRACTIVE` | < 3,500 MB or `isLowRamDevice` | no generative model at all |

The 6 GB band *attempts* co-residency once, then `rememberNoCoResidency()` makes the answer stick for
every future launch. Try-once-and-remember beats a hardcoded threshold nobody can tune.

One mutex does double duty: it serialises model loading, **and** because an entire generation —
prefill plus every decoded token — happens inside one `withTeacher` block, it guarantees no other
coroutine can swap a model out from under an in-flight turn. There is no separate mid-turn guard
because none is needed.

`onTrimMemory` deliberately does **not** take that mutex — it arrives on the main thread, and
blocking it behind an in-flight generation would freeze the UI for seconds. At CRITICAL it only
downgrades the tier, so the *next* borrow frees the idle model; at COMPLETE the app is already a kill
candidate, so releasing immediately is the entire point.

### Degradation ladder — every rung a working product

```
1. EXTRACTIVE tier, or no .task staged   → ExtractiveTeacher
2. Gemma loads                            → GemmaTeacher
3. Gemma throws (incl. OutOfMemoryError)  → degrade + remember, ExtractiveTeacher
```

`ExtractiveTeacher` is not a stub. TextRank centrality selection, TF-IDF term weighting, cloze-blank
quiz generation, definition-cue detection. It offers one guarantee Gemma cannot: **every sentence it
produces exists verbatim in the source document.** Several tests assert exactly that, because a
paraphrase appearing there would mean a bug that invented content.

---

## 4. Feature inventory

### 4.1 Shipped and working

| Feature | Implementation detail |
|---|---|
| Import text / markdown | `text/*` wildcard, because content providers label `.md` inconsistently |
| Import PDF | PdfBox-Android, `MemoryUsageSetting.setupTempFileOnly()` so a 40-page doc does not spike heap |
| Photograph a page | `ACTION_IMAGE_CAPTURE` + FileProvider — no CAMERA permission, no CameraX, no preview surface |
| On-device OCR | ML Kit Devanagari **bundled** variant: model inside the APK, no Play Services download |
| Chunking | 180 words ≈ 250 tokens; 4 chunks ≈ 1,000 tokens = the prompt budget |
| FTS4 retrieval | Chunk 0 always included; fewer than 2 MATCH hits falls back to `[0, 1, last]` |
| Explain — 5 key points | Generated at import, persisted, instant on open |
| Quiz — 5 MCQ | Options shuffled, seed derived from question text so the layout is stable across renders |
| Ask — grounded Q&A | Retrieve → prompt → stream tokens; `NOT_IN_TEXT` sentinel is the anti-hallucination lever |
| 13 languages | en, hi, mr, ne, sa, ur, ta, te, kn, ml, bn, gu, pa, or |
| Language chosen pre-import | Correct: rendering happens during ingest, so changing it later means re-translating |
| Language toggle in lesson | Pure DB read, zero model work; carries quiz score and position across the switch |
| Read-aloud | Per-language `Locale`; toast when the device ships no voice data for that language |
| Delete document | Long-press; cascades to chunks, artifacts, quiz results and saved text |
| Zero network capability | `tools:node="remove"` on INTERNET and ACCESS_NETWORK_STATE |
| Backup restricted | Both `fullBackupContent` and `dataExtractionRules` named; `database` domain excluded |
| Accessibility | Selection never colour-only (bold + `isSelected` + ✓/✗ glyphs); content descriptions throughout |

### 4.2 Built but not reachable from the UI

- `LessonPipeline.translateInto` — public, needs no model reload, but nothing lets a student re-render
  an existing document into a second language
- `quiz_results` table — written on every answer, **read by nothing**
- `SupportedLanguage.rtl` — declared, never read
- `BeamSearchDecoder` — full implementation, tests only, no production call site
- `dict.SRC_HI.json` + `dict.TGT_EN.json` — HI→EN vocabularies, 3.7 MB, direction unreachable
- `Prompts.applyTurnMarkers` — mutable global, never assigned, never verified against a real `.task`
- `GemmaTeacher.Backend.GPU` — implemented; CPU is hardcoded, and that is the correct default because
  interactive turns are decode-bound (GPU wins prefill ~7× but is no faster at decode and costs
  ~200 MB more)

### 4.3 Deliberately absent

Voice input (Vosk removed together with its RECORD_AUDIO permission — no mic button existed in any
layout), Glossary (deleted in `f14c29e`; nothing could reach it), accounts, sync, analytics.

---

## 5. What changed since the previous audit

| Metric | Before | After |
|---|---|---|
| Permissions in the merged APK | 3 | 0 external |
| Assets packaged | 909 MB | 459 MB |
| Installable ABIs | 2 (arm only) | 3 (+ x86_64, so emulators work) |
| JVM tests | 137 | 155 |
| 13-language verification | a comment claim | an on-device test |

Every P0/P1 from that audit is closed — eight fix commits, each built and tested individually.

---

## 6. Findings

### P1 — visible defects

#### F1. Ask answers only in English
`LessonViewModel.sendQuestion` never translates its output. A student reading a Tamil lesson taps
Ask, and the answer comes back in English, in an English voice. This is the largest hole in the
product's own claim — and it is small work: the answer is 1–3 short lines, `LessonTranslator.render`
already takes a list, and the translation engine is the one that was just released. The real cost is
one model swap per question in `EXCLUSIVE` tier, which is the honest price and worth surfacing in the
UI rather than hiding by answering in the wrong language.

#### F2. Streaming tokens leak into the Explain pane
`LessonActivity.kt:90` appends every streamed token to `contentText` unconditionally. `contentText` is
shared by **Explain and Ask** — only QUIZ hides it (`LessonActivity.kt:168`). So switching
Ask → Explain mid-generation shows Gemma's answer typing itself onto the end of the bulleted key
points. It self-corrects when generation finishes and `AskOutput.Final` triggers a re-render, so the
pollution is transient — but it is on screen and it reads as broken. The comment at
`LessonActivity.kt:87` says "the hidden TextView"; that premise holds only for the Quiz tab.

#### F3. UI chrome is English for 12 of 13 languages
`values-hi/strings.xml` carries **8 of 53** strings. There is no `values-ta`, `-te`, `-kn`, `-ml`,
`-bn`, `-gu`, `-pa`, `-or`, `-ur`, `-mr`, `-ne` or `-sa` at all. An app whose entire thesis is "learn
in your language" labels its own tabs, buttons and error messages in English. Ironic rather than
broken — but for a learning app this is a product-level finding, not a polish item.

#### F4. Captured pages accumulate forever
`LearnBridgeApp.newCaptureUri` writes `filesDir/captures/page_<timestamp>.jpg` and nothing ever
deletes them. Each is 2–4 MB. Ingest reads the image once and never needs it again. On the
low-storage phones this app targets, that is the wrong leak to have.

#### F5. Ingest dies with the Activity
`LibraryActivity.ingest` collects on `lifecycleScope`. The `.onCompletion` handler correctly discards
the half-built document on cancellation — that part is right. But it means backgrounding the app
during a 30–60 s import loses the entire import. The portrait lock stops rotation; it does not stop a
system kill, a multi-window resize, or a font-size change. Ingest belongs in a ViewModel scope at
minimum.

### P2 — dead weight and latent bugs

#### F6. 3.7 MB of unreachable vocabulary
`dict.SRC_HI.json` (3.1 MB) + `dict.TGT_EN.json` (0.6 MB) ship for `Direction.HI_TO_EN`. Its ONNX
graphs are **not** in assets — they were moved out with the 451 MB. So the direction is not merely
unused: it would fail at model load if anything requested it. Delete both files, or remove
`HI_TO_EN` from the app's reach explicitly.

#### F7. `SupportedLanguage.rtl` is never read
Urdu paragraphs get correct bidi from the platform but stay left-aligned in an LTR layout. One
`textDirection` on `contentText` / `quizQuestion` closes it.

#### F8. `quiz_results` is write-only
Four columns, an insert path, a delete cascade and a test — and no reader anywhere. Either build the
progress view it was meant for, or drop the table.

#### F9. No re-render into a second language after import
`LessonPipeline.translateInto` is public and language switching needs no model reload, yet no UI
reaches it. A document imported in Hindi can only ever be read in Hindi or English. The cheapest real
feature left on the table.

#### F10. `DocStore.translationLanguage` is nondeterministic by construction
`SELECT lang FROM artifacts WHERE docId = ? AND lang <> 'en' LIMIT 1` — no `ORDER BY`. Harmless today
because a document carries exactly one target language. Becomes a real bug the moment F9 ships.

#### F11. `BeamSearchDecoder` has no production call site
Fully implemented, tested, unreachable.

### P3 — release readiness (previously flagged, still open)

| # | Finding |
|---|---|
| **F12** | Release builds sign with the **debug key** and set `optimization { enable = false }`. Not distributable. Blocked on your keystore. |
| **F13** | APKs are **530–556 MB** (arm64 547, v7a 530, x86_64 556 — debug, unminified). Play's base-APK ceiling is 200 MB. Distribution needs an AAB with **install-time asset packs** for the 459 MB model, which keeps the offline guarantee intact after install (unlike on-first-run download). |
| **F14** | `onUpgrade` drops all four tables. Correct for schema v1; a data-loss bug the day a v2 ships. |
| **F15** | No CI. 155 tests exist and nothing runs them on push. |
| **F16** | ~~`Prompts.applyTurnMarkers = true` is unverified.~~ **CLOSED 2026-07-28.** `TurnMarkerDeviceTest` ran on the SM-M315F against the real `gemma3-1b-it-int4.task`: both arms produced 5 well-formed bullets on the same source, neither echoed a turn marker, 276 vs 222 chars. No destructive double wrapping — setting stays `true`. |
| **F17** | **The generative tutor is not in the APK.** Gemma weights must be `adb push`ed to `getExternalFilesDir`. Out of the box, on every device, `createTeacher` logs "No .task model staged" and returns `ExtractiveTeacher`. The app works — that is the point of the ladder — but anyone installing this today gets the extractive tutor, and "Gemma 3 1B on-device" should not read as shipped. |

### 6.1 Correction to a previously reported number

An earlier note said "permissions 3 → 0". Precisely: the merged manifest carries **one**
`uses-permission` — `com.learnbridge.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, injected by
androidx.core at `signature` protection level, the app granting itself a permission it also declares.
It grants nothing to any other app and no platform capability. **Zero external permissions** is the
accurate claim.

---

## 7. Engineering quality

**Strong — genuinely above what the code volume suggests.**

- Every non-obvious decision carries a comment naming the failure it prevents:
  - `doc_id`, not `docId` — FTS3/4 tables have an implicit case-insensitive `docid` rowid alias, and a
    column spelled `docId` collides with it; SQLite refuses the `CREATE VIRTUAL TABLE` with "vtable
    constructor failed". Reproduced against a bare `SQLiteDatabase`, so it is a real on-device
    failure, not a Robolectric artifact.
  - `ordinal` stored as TEXT and `CAST(ordinal AS INTEGER)` in `ORDER BY` — FTS4 columns carry no
    declared type affinity. Without the CAST, a plain `ORDER BY ordinal DESC` sorts lexicographically
    and picks "9" over "12", so any document past nine chunks (~1,800 words) silently returns the
    wrong chunk on exactly the generic-question path the fallback exists to serve.
  - `noCompress` for `.onnx` — ONNX Runtime mmaps model files from disk; a compressed asset cannot be
    mmapped and would force a full decompress-to-memory on every load, taking resident memory from
    ~605 MB to ~1.1 GB.
- **Kotlin Flow exception transparency handled correctly** — `.catch` for failures, `.onCompletion`
  for cancellation, with a comment explaining why a `try`/`catch` around `emit` would itself be the
  bug (it also catches collector exceptions, and re-emitting from there fails with "Flow exception
  transparency is violated", masking the real error with a worse one).
- Failures are **states**, not exceptions: `ImportResult.Failure`, `IngestProgress.Failed`,
  `AskOutput.Failed`. `DocImport` catches broadly *and* rethrows `CancellationException` first so
  structured concurrency still works.
- Prompt output formats are line-prefixed rather than JSON, because a 1B model produces malformed
  JSON often enough that a strict parser would throw away otherwise usable output. The parsers
  salvage: three good quiz items survive where five were asked for.
- Regression tests are named after the device observation that produced them — headings selected as
  key points, OCR-wrapped lines discarded, "every"/"together" chosen as quiz answers, `।।`
  double-danda in Hindi, ASCII pipe as a danda in Odia.
- **155 tests, 0 failures.** 15 JVM test files plus one instrumented all-13-languages device test.

**Weak.** No CI. No release configuration. Schema has no migration path.
`Prompts.applyTurnMarkers` is a mutable global. The `:engine` / `:app` namespace mismatch is
deliberate and documented, but a new reader trips on it.

---

## 8. Score

| Dimension | Score |
|---|---|
| Idea and architecture | 9 / 10 |
| Correctness and robustness | 8 / 10 |
| Test coverage and honesty | 9 / 10 |
| Product completeness | 6 / 10 — F1 and F3 gate this |
| Code clarity | 9 / 10 |
| Dead weight | 6 / 10 |
| Release readiness | 3 / 10 |
| Security and privacy | 10 / 10 |

### **78 / 100** (previous audit: 64 / 100)

---

## 9. Recommended order of work

1. **F2** — one-line guard on the token collector. Visible bug, smallest diff in this report.
2. **F4** — delete the capture file after ingest. Two lines.
3. **F1** — translate Ask answers. The product gap that most contradicts the pitch.
4. **F6, F7, F8, F11** — one deletion pass: 3.7 MB of dead vocabulary, the unread `rtl` field,
   `quiz_results` (or its reader), `BeamSearchDecoder`.
5. **F5** — move ingest to a ViewModel scope.
6. **F3** — localize the chrome. 53 strings × the languages worth doing. `LessonTranslator` can
   bootstrap drafts, but this needs a human reader per language before shipping.
7. **F15** — GitHub Actions running `testDebugUnitTest`. Cheap, and it stops F12–F14 from rotting
   further.

**Blocked on things outside the codebase:** F12 (your keystore), F13 (a Play listing decision),
F14 (a real v2 schema need). ~~F16~~ is closed — the weights were staged on 2026-07-28 and the A/B
ran; the Gemma Terms of Use and Prohibited Use Policy still travel with them, which is why they stay
out of the repository.

---

## 10. Open question, unanswered

`SupportedLanguage` declares Gujarati and Marathi with `।` as their terminator, and the punctuation
normalizer now enforces it. Modern usage in both languages often prefers `.`. One character each to
change if that is the call.
