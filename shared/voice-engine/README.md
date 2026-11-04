# Hex Code voice engine

On-device dictation for Hex Code: record a take and turn it into text on the phone. Speech never leaves the
device. Both platforms run NVIDIA's Parakeet TDT 0.6B v2, each on the fastest runtime measured for it.

## What runs where

| Platform | Model | Runtime | Where it's set |
|---|---|---|---|
| iOS | Parakeet TDT 0.6B v2 | FluidAudio 0.15.5 (CoreML, Apple Neural Engine) | `adapter/ios/` (Swift package `VoiceEngineKit`) |
| Android | Parakeet TDT 0.6B v2, int8 ONNX export | sherpa-onnx (ONNX Runtime) with the XNNPACK provider, on the fast cores only | `SpeechModels.DICTATION_DEFAULT` and `EngineOptions` in `adapter/android/` |

The quality reference is Parakeet V2 on transcribe.cpp, the setup the [Hex](https://github.com/anomalyco/hex)
dictation app uses on the desktop; "Hex" in the lab means that reference. Android doesn't run transcribe.cpp for
dictation, because the same model runs about twice as fast on ONNX Runtime with XNNPACK and gives nearly the same
text. Measured with the speech model lab, per 10 s of speech:

| Phone | Runtime | Wait, technical clips | Wait, real speech | Word errors, technical | Word errors, real speech |
|---|---|---:|---:|---:|---:|
| iPhone 16e | FluidAudio (CoreML) | 0.16 s | 0.14 s | 3.1% | 12.1% |
| Pixel 9 (Tensor G4) | sherpa-onnx + XNNPACK | 0.67 s | 0.97 s | 3.5% | 12.9% |
| Pixel 9 | transcribe.cpp (reference) | 1.33 s | 1.57 s | 2.7% | 12.1% |
| Galaxy A15 (Helio G99) | sherpa-onnx + XNNPACK | 1.57 s | 1.53 s | 3.5% | 12.9% |
| Galaxy A15 | transcribe.cpp (reference) | 3.68 s | 3.32 s | 2.7% | 12.1% |

The two test sets are described under [Clip sets](#clip-sets).

**Measured and not adopted:**
- **NNAPI:** no faster than XNNPACK.
- **All cores:** slower than the fast cores only.
- **Q4 versions:** slower than Q8.
- **LiteRT:** Parakeet Ultra on the CPU is the most accurate on accented speech and multilingual. It uses up to
  2 GB of memory, and on the GPU it doesn't fit a 4 GB phone.
- **Smaller models** (110M and below): too many wrong technical words.
- **Android's own recognizer:** fast, but weak on technical terms.

### When to choose again

Run the lab and compare against the tables above when one of these happens:
- a FluidAudio, sherpa-onnx or LiteRT update, or a new model or runtime, looks promising;
- a phone class is added;
- Hex Code adds languages.

**The bar:** an Android option has to keep the reference's words (transcribe.cpp) for at least 95% of words on
real speech, with no more word errors than the reference plus 1 point. Among the options that pass, the shortest
wait on both phones wins.

## Layout

- `Package.swift` and `adapter/ios/`: the Swift package `VoiceEngineKit`.
  - **Public surface:** `VoiceEngine.swift`.
  - **Transcriber:** `ParakeetTranscriber.swift` (FluidAudio).
  - **Recorder:** `TakeRecorder.swift` (16 kHz WAV takes).
  - The transcriber and recorder are adapted from Hex's iOS app (MIT; see `THIRD_PARTY_NOTICES.md`).
- `adapter/android/`: the Gradle module `:voice-engine`.
  - **Engines:** `Engines.kt` (transcribe.cpp, sherpa-onnx, Android's recognizer) and `LiteRtEngine.kt`.
  - **Models:** `SpeechModels.kt` (the model list, downloads and storage).
  - **Lab:** `SpeechLab.kt` (the speech model lab and its word error rate).
  - **Native code:** `src/main/cpp/` (transcribe.cpp at a pinned revision, and CPU pinning).
- `lab/`: scripts that make test clips and model lists, run lab runs and score results.

## The speech model lab (Android)

Hex Code's dictation screen has a **Models** link. It opens the lab, where you can:
- download, delete and switch models;
- choose fast or all cores and the ONNX Runtime provider;
- run every downloaded model through a clip set;
- read a sentence aloud with your own voice, which is scored too;
- share the results as CSV.

Results are kept in `/sdcard/Android/data/dev.opencode.hexcode/files/lab/results.jsonl`, one JSON object per clip.

**Models:**
- The list is `adapter/android/src/main/assets/lab/models.json`, written by `lab/make-models.py` with every file
  pinned to a Hugging Face revision and SHA-256.
- Downloads go to the app's private `files/models/<id>/`.
- To skip downloading on a test phone, copy the files in yourself. `adb push` alone isn't readable by the app on
  recent Android, so use `run-as`:

```bash
adb -s <serial> push <folder with models/<id>/ files>/<id> /data/local/tmp/
```

```bash
adb -s <serial> shell "chmod -R a+rX /data/local/tmp/<id> && run-as dev.opencode.hexcode sh -c 'mkdir -p files/models && cp -r /data/local/tmp/<id> files/models/' && rm -rf /data/local/tmp/<id>"
```

**Running without taps:** debug builds take launch extras:
- `HexCodeLab`: model ids, comma separated, or `all`.
- `HexCodeLabOptions`: `allCores`, plus `cpu`, `xnnpack` or `nnapi`.
- `HexCodeLabSet`: a clip set copied into `files/lab-sets/<name>/`.

`lab/run-android.sh` runs a list of them one after another. It restarts the app for each run, so a model that
runs out of memory only loses its own run:

```bash
shared/voice-engine/lab/run-android.sh <serial> "parakeet-v2-q8,parakeet-v2-onnx-int8|xnnpack|" "parakeet-v2-onnx-int8|xnnpack|edacc"
```

```bash
adb -s <serial> pull /sdcard/Android/data/dev.opencode.hexcode/files/lab/results.jsonl results-<phone>.jsonl
```

The lab keeps the screen on while it runs, because a locked screen throttles the app. Long sessions heat the
phone too, so give it a few minutes between big runs, and repeat any close call.

## The lab on iOS

Debug builds take `-HexCodeLab <set>`. It runs the clips of `Documents/<set>/` (the same `clips.json` and WAVs as
on Android) through FluidAudio after one warm-up run, and writes `Documents/lab/<set>.jsonl` in the Android
result format:

```bash
xcrun devicectl device copy to --device <device id> --domain-type appDataContainer --domain-identifier dev.opencode.hexcode --source <clip set folder> --destination Documents/<set>
```

```bash
xcrun devicectl device process launch --device <device id> --terminate-existing dev.opencode.hexcode -- -HexCodeLab <set>
```

```bash
xcrun devicectl device copy from --device <device id> --domain-type appDataContainer --domain-identifier dev.opencode.hexcode --source Documents/lab/<set>.jsonl --destination results-iphone-<set>.jsonl
```

For the bundled set, use `adapter/android/src/main/assets/lab/` as the clip set folder and `technical` as the name.

## Clip sets

- **technical:** bundled with the app. 12 clips, 81 s and 239 words of technical English, spoken by macOS
  text-to-speech voices. It's clean audio, good for timing and for big quality gaps. `lab/make-technical-clips.py`
  writes it.
- **edacc:** never committed, because the corpus may not be redistributed. 99 utterances, 13 minutes and 2,112
  words of real conversation from [EdAcc](https://huggingface.co/datasets/edinburghcstr/edacc)'s test split, with
  speakers of 21 first languages. Download a few `test-*.parquet` shards, then run
  `lab/make-edacc-set.py <shards> <out>`. Copy the result to `files/lab-sets/edacc/` on Android, or
  `Documents/edacc/` on iOS.

Any folder with a `clips.json` (`{"clips": [{"id", "file", "seconds", "voice", "reference"}]}`) and 16 kHz mono
16-bit WAVs works as a set.

## Scoring

```bash
python3 shared/voice-engine/lab/score.py results-*.jsonl
```

It prints one table per phone and clip set from the latest run of each model and setting:
- **Wait:** time from the end of the audio to the text, scaled to 10 s of speech.
- **Longest clip:** the wait on the longest clip.
- **Word errors:** words wrong against the reference. Case, punctuation, camelCase, digits against words and
  "uh"/"um" don't count.
- **Differs from Hex:** words that differ from the reference, Parakeet V2 Q8 on transcribe.cpp.
- **Load time** and **memory**.

The app uses the same word rules (`Wer` in `SpeechLab.kt`).
