# LearnBridge AI

**Your teacher, offline. आपका शिक्षक, ऑफ़लाइन।**

Photograph a textbook page, or import a document. LearnBridge explains it in plain English, says the
same thing in **thirteen Indian languages**, reads it aloud, quizzes you on it, and answers your
follow-up questions — entirely on your phone, requesting **no permissions at all**.

हिंदी · मराठी · नेपाली · संस्कृतम् · اردو · தமிழ் · తెలుగు · ಕನ್ನಡ · മലയാളം · বাংলা · ગુજરાતી · ਪੰਜਾਬੀ · ଓଡ଼ିଆ

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
| **Translate** | The same lesson in any of 13 Indian languages, instantly — pre-rendered, no waiting |
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
the APK. Speech synthesis likewise loads from local voice data.

### The permission list is empty, and that took more than not declaring one

Not declaring `INTERNET` is not the same as not having it. ML Kit pulls in
`com.google.android.datatransport:transport-backend-cct` — Google's telemetry uploader — which
declares `INTERNET` and `ACCESS_NETWORK_STATE` in its own manifest, and the manifest merger adds them
to yours. Every APK built from this repository before that was found requested network access, and a
user checking the app's permissions would have seen it.

The fix is to strip them at merge time:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
```

which is *stronger* than never declaring them — the process becomes incapable of opening a socket, so
the guarantee is enforced by the OS rather than by our intentions. Confirmed in the merger report:

```
> uses-permission#android.permission.INTERNET
  ADDED from .../app/src/main/AndroidManifest.xml
  REJECTED from [com.google.android.datatransport:transport-backend-cct:2.3.3]
```

The merged manifest now contains no user-visible permission at all:

- no `INTERNET` — everything runs on-device, and the OS enforces it
- no `CAMERA` — page capture hands off to the system camera app via `ACTION_IMAGE_CAPTURE`
- no `RECORD_AUDIO` — this was declared for spoken questions that were never wired up. A dangerous
  permission for code with no call sites is worse than a missing feature, so the permission, the
  recognizer and its 134 MB of acoustic models are all gone until the mic button actually ships.

Backup is restricted too, for the same reason. Auto Backup is performed by the system, so an absent
`INTERNET` permission would not have stopped it: the rules files existed but the manifest referenced
neither, and the platform default includes the `database` domain — every sentence extracted from a
photographed page was eligible for upload. Both files are now wired, and the document database is
excluded from cloud backup while still travelling on a local device-to-device transfer.

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

### Thirteen languages from one 472 MB model

Not thirteen models. The translation export's target language is nothing more than the **second token
of the source sequence**, so switching it needs no reload, no second set of weights and no extra
memory. One loaded engine serves all of them.

Getting there took correcting a wrong conclusion. Counting subword pieces in the target vocabulary
shows Devanagari 72,356, Arabic 16,949, Latin 11,414 — and **zero** multi-character pieces for Tamil,
Telugu, Kannada, Malayalam, Gurmukhi and Odia. That looks decisive: those languages cannot be
generated. It is wrong.

IndicTrans2 normalises every Indic language into one script-unified **Devanagari** representation and
transliterates back to the native script as post-processing. Asking for Tamil produced fluent Tamil
written in Devanagari:

```
नीर् चुऴऱ्चि ऎऩ्ऱु अऴैक्कप्पटुकिऱतु      what the model emits
நீர் சுழற்சி என்று அழைக்கப்படுகிறது       what it should read as
```

The missing piece was a 60-line character mapping, not a model. Unicode lays the Brahmic blocks out
in parallel — same offset, same phoneme — so Devanagari → Tamil is `codepoint + 0x280`. Urdu is the
exception that proves the rule: Perso-Arabic is not Brahmic, cannot be reached by an offset, and
accordingly has real subword coverage of its own in the vocabulary.

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

One APK is produced per ABI — `arm64-v8a`, `armeabi-v7a` and `x86_64`, the last so the app installs
on a standard emulator. MediaPipe publishes no x86_64 generative library, so on that ABI the app runs
its extractive tutor; every feature still works.

**The model weights are not in this repository** (~459 MB of ONNX translation graphs). A clone
compiles and runs without them and falls back to the extractive tutor, so a build with no assets at
all is a supported configuration rather than a broken one — translation is simply unavailable and the
language toggle stays disabled. Stage them into `engine/src/main/assets/`, and stage exactly these
five files:

```
encoder_int8.onnx  decoder_init_int8.onnx  decoder_step_int8.onnx  dict.SRC.json  dict.TGT.json
```

That is the EN→target direction, which is the only one the app constructs. The export also produces
`dict.SRC_HI.json` and `dict.TGT_EN.json` for HI→EN; those are 3.7 MB the APK cannot use, because the
graphs that direction needs are not staged either. Leave them out.

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

137 JVM unit tests covering chunking, FTS retrieval, the tolerant output parsers, Hindi sentence
splitting, the Brahmic script mapping, and the extractive tutor's grounding invariants. No device
required.

One test does need a device, because it is the only way to make the claim it makes:

```bash
./gradlew :app:connectedDebugAndroidTest
```

`AllLanguagesDeviceTest` loads the translation engine once, translates the same English sentence into
all thirteen targets, and asserts each result lands in that language's own Unicode block — that the
*model's* output, not hand-written input, transliterates correctly. It needs the staged model assets.

---

## Honest limitations

- **An Ask answer costs a wait that Explain does not.** The tutor generates in English and the answer
  is then rendered into the lesson's language, which can force a model swap mid-conversation —
  measured at ~14 s. The screen says so while it happens. Explain and Quiz avoid it entirely by being
  rendered once, at import.
- **Hindi input is not supported.** The engine can do HI→EN, but neither its graphs nor its
  vocabularies are staged, so the direction would fail at model load rather than merely go unused.
  Routing a document through HI→EN → tutor → EN→HI would stack three lossy hops anyway.
- **The extractive tutor selects, it does not paraphrase.** Without a generative model it returns the
  document's own sentences, chosen well, rather than simplified rewrites.
- **Greedy decoding drifts on long sentences.** Which is why long text is split before translation
  rather than truncated after. A beam-search decoder was written and never enabled — with the current
  uncached decoder graph it costs a full forward pass per beam per step — so it was removed rather
  than left to rot unmeasured. `MtEngine` still takes its decoder as a parameter if it comes back.
- **"Plant" sometimes renders as "संयंत्र"** (industrial plant) rather than "पौधा" — lexical
  ambiguity in the translation model.
- **A second language costs a second render.** The lesson is rendered during ingest, so the language
  chosen there is the only instant one. Long-pressing the lesson's language toggle offers the other
  twelve: one the document already holds is a database read, any other translates the document again
  and says so before you tap.
- **The chrome translations have not been read by a native speaker.** All thirteen `values-*`
  directories now carry all 55 strings, and every one of them was drafted by a machine. Key coverage
  and format specifiers are checked by lint; wording, register and honorific level are not. Sanskrit
  is the weakest — there is barely a modern software-UI register to draw on. Treat these as a
  starting point for a reader, not as shipped copy.
- **Transliteration is mechanical, not linguistic.** It maps script faithfully, but does not apply the
  orthographic conventions a native typesetter would; Tamil output in particular reads slightly
  transliterated rather than natively composed.
- Portrait only. Debug signing. No release hardening.

---

## Third-party models and licences

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Note in particular that the **Gemma Terms of
Use** travel with the Gemma weights and apply to any distribution that includes them.
