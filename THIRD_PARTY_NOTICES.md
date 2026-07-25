# Third-Party Notices

LearnBridge AI runs entirely on-device. It bundles pre-trained models and native runtimes from the
projects below. Their licences apply to the distributed application, including the model weights
shipped inside it.

No model weights are committed to this repository — see the README for how to stage them.

---

## Models

### IndicTrans2 (translation, EN↔HI)

- Source: `ai4bharat/indictrans2-en-indic-dist-200M` and `ai4bharat/indictrans2-indic-en-dist-200M`
- Licence: MIT
- Copyright: AI4Bharat, IIT Madras
- Used as: ONNX-exported, INT8-quantized encoder / decoder-init / decoder-step graphs
- Paper: *IndicTrans2: Towards High-Quality and Accessible Machine Translation Models for all 22
  Scheduled Indian Languages* (Gala et al., TMLR 2023)

### Gemma 3 1B IT (on-device generation)

- Source: `litert-community/Gemma3-1B-IT`, variant `gemma3-1b-it-int4.task`
- Licence: **Gemma Terms of Use** — https://ai.google.dev/gemma/terms
- Copyright: Google LLC
- **The Gemma Terms of Use and the Gemma Prohibited Use Policy travel with these weights and apply
  to any distribution of this application that includes them.** Redistribution must include the
  terms and the use restrictions.
- Prohibited Use Policy: https://ai.google.dev/gemma/prohibited_use_policy

### Vosk acoustic models (offline speech recognition)

- Source: Vosk small models for English (Indian accent) and Hindi
- Licence: Apache License 2.0
- Copyright: Alpha Cephei Inc.

---

## Libraries

| Library | Version | Licence | Copyright |
|---|---|---|---|
| ONNX Runtime (Android) | 1.27.0 | MIT | Microsoft Corporation |
| MediaPipe Tasks GenAI | 0.10.27 | Apache-2.0 | Google LLC |
| ML Kit Text Recognition (Devanagari, bundled) | 16.0.1 | Apache-2.0 | Google LLC |
| Vosk Android | 0.3.47 | Apache-2.0 | Alpha Cephei Inc. |
| PdfBox-Android | 2.0.27.0 | Apache-2.0 | Tom Roush; Apache Software Foundation |
| AndroidX (core, appcompat, activity, constraintlayout, lifecycle, cardview) | various | Apache-2.0 | The Android Open Source Project |
| Material Components for Android | 1.11.0 | Apache-2.0 | Google LLC |
| Kotlin stdlib / kotlinx.coroutines | 2.2.10 / 1.7.3 | Apache-2.0 | JetBrains s.r.o. |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 | JUnit contributors |
| Robolectric | 4.11.1 | MIT | Robolectric contributors |

Text-to-speech uses the Android platform `TextToSpeech` engine and the voice data installed on the
user's device. No speech synthesis model is bundled.

---

## Apache License 2.0

Licensed under the Apache License, Version 2.0 (the "License"); you may not use these files except
in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied. See the License for the specific language governing permissions and limitations under the
License.

## MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
