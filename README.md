# LearnBridge AI

**Your teacher, offline. आपका शिक्षक, ऑफ़लाइन।**

Photograph a textbook page, or import a document. LearnBridge explains it in plain English, says the
same thing in Hindi, reads it aloud, quizzes you on it, and answers your follow-up questions —
entirely on your phone, with **no internet connection and no `INTERNET` permission**.

---

## The problem

Hundreds of millions of students study in a language they do not fully read, on phones with
unreliable or expired data. Every AI study tool — ChatGPT, NotebookLM, Khanmigo, Perplexity — returns
a network error the moment the data runs out. Their entire product class assumes connectivity is a
precondition of learning.

LearnBridge is built for the phone that has no data today.

## What it does

| | |
|---|---|
| **Import** | `.txt`, `.md`, PDF, or a photograph of a printed page |
| **Explain** | Key points in plain language, drawn from the document |
| **हिंदी** | The same lesson in Hindi, instantly — pre-rendered, no waiting |
| **Listen** | Read aloud in either language |
| **Quiz** | Auto-generated questions with plausible wrong answers |
| **Ask** | Follow-up questions answered from the document itself |

---

## Verified offline

This is the central claim, so it was tested rather than asserted. With the device in airplane mode
(`airplane_mode_on = 1`, Wi-Fi disabled, `ping 8.8.8.8` → *Network is unreachable*), a photographed
page was imported and became a full bilingual lesson. From the device log:

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
the APK. Hindi speech synthesis likewise loads from local voice data.

`AndroidManifest.xml` declares exactly one permission — `RECORD_AUDIO`. There is deliberately no
`INTERNET` and no `CAMERA`; page capture hands off to the system camera app via
`ACTION_IMAGE_CAPTURE`, so no camera permission is needed at all.

---

## How it works

```
          ┌──────────────────────────────────────────────────────┐
 import   │  DocImport → Chunker → DocStore (SQLite + FTS4)      │
 (txt/pdf │      .txt/.md direct · PDF via PdfBox                │
  /photo) │      photo via ML Kit (bundled, Devanagari + Latin)  │
          └───────────────────────┬──────────────────────────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │  ModelHost                │   one residency mutex
                    │  withTeacher / withTrans  │   one model swap per document
                    └──────┬─────────────┬──────┘
                           │             │
                 ┌─────────▼───┐   ┌─────▼─────────────────┐
                 │  Teacher    │   │  IndicTrans2 (ONNX)   │
                 │  Gemma 3 1B │   │  EN→HI, INT8, mmapped │
                 │  or         │   └─────┬─────────────────┘
                 │  Extractive │         │
                 └─────────┬───┘         │
                           │             │
                    ┌──────▼─────────────▼──────┐
                    │  artifacts(kind, lang)    │  English AND Hindi persisted
                    └──────────┬────────────────┘
                               │
                    Explain · Ask · Quiz · Listen
```

### Three design decisions worth explaining

**1. Generation happens once, at import — not when you open a lesson.**
A 20-page document is roughly 12,000 tokens, and on-device generation runs at single-digit tokens per
second on mid-range hardware. Generating on demand would mean a spinner every time a student opened a
lesson. Instead everything — key points, quiz, and the Hindi of both — is produced in one pass behind
a progress screen the user expects to wait on. Opening a lesson afterwards is a database read.

**2. Exactly one model swap per document.**
The translation engine and the generative model cannot both stay resident on a mid-range phone.
Rather than interleaving them, the pipeline generates *all* English under one `withTeacher`
acquisition, then translates *everything* under one `withTranslator`. Because both languages are then
persisted in `artifacts(kind, lang)`, **the हिंदी toggle never loads a model at all** — it is a
database read, and it is instant.

**3. The tutor degrades instead of failing.**
`Teacher` has three implementations. `GemmaTeacher` runs Gemma 3 1B int4 via MediaPipe.
`ExtractiveTeacher` uses TextRank-style centrality, TF-IDF term weighting and cloze deletion — no
language model at all, so it runs anywhere and **cannot hallucinate**: every sentence it produces
appears in the source document, and the test suite asserts exactly that. A device that cannot hold a
language model swaps the implementation and keeps every feature. The app never shows an error where a
feature belongs.

---

## Measured on device

Samsung SM-M315F (Exynos 9611, 4×A73 @ 2.3 GHz + 4×A53, 5,573 MB RAM, Android 12, arm64-v8a):

| | |
|---|---|
| Translation, 12-token sentence | **1,069 ms** (tokenize 26 / encoder 221 / decode 798) |
| Cold translation-engine load | 14.2 s, once per install |
| Process PSS, engine resident | 485 MB |
| Extractive lesson (5 points + 5 quiz items) | ~4.3 s |
| Full ingest, text file → bilingual lesson | ~55 s, once per document |

The ONNX graphs are packaged `STORED`, not deflated, so ONNX Runtime memory-maps them from the APK
rather than decompressing to heap — verified by inspecting the packaged APK entries.

---

## Building

```bash
git clone <this repo>
cd LearnBridgeAI
./gradlew :app:assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 36).

**The model weights are not in this repository** (~909 MB of ONNX translation graphs and Vosk
acoustic models). A clone compiles and runs without them, and falls back to the extractive tutor, but
translation and speech will not work. Stage them into `engine/src/main/assets/`.

The optional generative model is **not** bundled during development — push it to the device instead,
so a rebuild does not reinstall a 1.5 GB APK:

```bash
adb push gemma3-1b-it-int4.task /sdcard/Android/data/com.learnbridge.app/files/
```

It is licence-gated: accept the [Gemma Terms of Use](https://ai.google.dev/gemma/terms) and download
from [`litert-community/Gemma3-1B-IT`](https://huggingface.co/litert-community/Gemma3-1B-IT). Without
it the app runs on `ExtractiveTeacher` and every feature still works.

### Tests

```bash
./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest
```

92 JVM unit tests covering chunking, FTS retrieval, the tolerant output parsers, Hindi sentence
splitting, and the extractive tutor's grounding invariants. No device required.

---

## Honest limitations

- **Ask answers are English only.** A freshly generated answer has no pre-rendered Hindi, and
  translating it live would force a model swap mid-conversation — measured at ~14 s, twice per
  question. The Hindi experience lives on the Explain tab, where it is instant.
- **Hindi input is not supported.** The HI→EN translation direction exists in the assets but is not
  wired up, and routing speech → HI→EN → tutor → EN→HI would stack three lossy hops.
- **The extractive tutor selects, it does not paraphrase.** Without a generative model it returns the
  document's own sentences, chosen well, rather than simplified rewrites.
- **Greedy decoding drifts on long sentences.** A beam-search decoder is implemented but not enabled;
  it forces the translation engine onto an uncached path and needs measurement before switching.
- **"Plant" sometimes renders as "संयंत्र"** (industrial plant) rather than "पौधा" — lexical
  ambiguity in the translation model.
- Portrait only. Debug signing. No release hardening.

---

## Third-party models and licences

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Note in particular that the **Gemma Terms of
Use** travel with the Gemma weights and apply to any distribution that includes them.
