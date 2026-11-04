#!/usr/bin/env python3
"""Writes the speech model lab's model list, adapter/android/src/main/assets/lab/models.json.

Every file is pinned to the Hugging Face revision current when this runs, with its size and SHA-256. Published
accuracy comes from transcribe.cpp's catalog (the revision pinned in adapter/android/src/main/cpp/CMakeLists.txt).

  python3 make-models.py <transcribe.cpp checkout>/catalog
"""
import hashlib
import json
import os
import sys
import urllib.request

CAT = sys.argv[1].rstrip("/") + "/"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../adapter/android/src/main/assets/lab/models.json")
def api(url): return json.load(urllib.request.urlopen(url))
def files(repo, names):
    rev = api(f"https://huggingface.co/api/models/{repo}")["sha"]
    tree = {f["path"]: f for f in api(f"https://huggingface.co/api/models/{repo}/tree/{rev}")}
    out = []
    for n in names:
        f = tree[n]
        url = f"https://huggingface.co/{repo}/resolve/{rev}/{n}"
        sha = (f.get("lfs") or {}).get("oid")
        if not sha:
            sha = hashlib.sha256(urllib.request.urlopen(url).read()).hexdigest()
        out.append({"name": n, "url": url, "bytes": f["size"], "sha256": sha})
    return out
def accuracy(variant, quant):
    d = json.load(open(CAT + variant + ".json"))
    acc = {}
    for b in d["accuracy_benchmarks"]:
        if b["language"] == "en" and b["metric"] == "wer" and b["quant"] in (quant, "Q8_0"):
            key = {"librispeech": "librispeechWer", "fleurs": "fleursWer"}[b["dataset"]]
            if b["quant"] == quant or key not in acc: acc[key] = b["err_pct"]
    return d, acc
models = []
def gguf(id, variant, quant, title, note):
    d, acc = accuracy(variant, quant)
    models.append({"id": id, "title": title, "engine": "transcribe.cpp", "kind": "gguf", "params": d["params"],
        "license": d["license"]["display"], "source": f"transcribe.cpp catalog/{variant}.json", "note": note, **acc,
        "files": files(d["published_repo"], [f"{variant}-{quant}.gguf"])})
gguf("parakeet-v2-q8", "parakeet-tdt-0.6b-v2", "Q8_0", "Parakeet V2 · Q8", "Same as Hex on the iPhone, and Hex desktop's Parakeet V2 choice.")
gguf("parakeet-v2-q4", "parakeet-tdt-0.6b-v2", "Q4_K_M", "Parakeet V2 · Q4", "Same model as Hex, stored in 4-bit: a third smaller.")
gguf("parakeet-unified-q8", "parakeet-unified-en-0.6b", "Q8_0", "Parakeet Unified EN · Q8", "Hex desktop's default model. Newer English Parakeet of the same size; can also stream.")
gguf("parakeet-unified-q4", "parakeet-unified-en-0.6b", "Q4_K_M", "Parakeet Unified EN · Q4", "Hex desktop's default model, stored in 4-bit.")
gguf("granite-470m-q8", "granite-speech-5.0-470m-turboctc", "Q8_0", "Granite Speech 470M · Q8", "IBM, English only. A CTC model: no step-by-step decoder.")
gguf("granite-470m-q4", "granite-speech-5.0-470m-turboctc", "Q4_K_M", "Granite Speech 470M · Q4", "Same, stored in 4-bit.")
gguf("canary-180m-q8", "canary-180m-flash", "Q8_0", "Canary 180M Flash · Q8", "NVIDIA, a third the size of Parakeet V2.")
gguf("parakeet-110m-q8", "parakeet-tdt_ctc-110m", "Q8_0", "Parakeet 110M · Q8", "Small Parakeet, about a sixth of the size.")
gguf("moonshine-base-q8", "moonshine-base", "Q8_0", "Moonshine Base · Q8", "Tiny model made for phones. Lower accuracy.")
d, acc = accuracy("parakeet-tdt-0.6b-v2", "Q8_0")
models.append({"id": "parakeet-v2-onnx-int8", "title": "Parakeet V2 · ONNX int8", "engine": "sherpa-onnx", "kind": "nemo_transducer",
    "params": d["params"], "license": d["license"]["display"], "source": "huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
    "note": "Same model as Hex, run by ONNX Runtime instead of ggml.",
    "files": files("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"])})
d, acc = accuracy("parakeet-ultra", "Q8_0")
gguf("parakeet-ultra-q8", "parakeet-ultra", "Q8_0", "Parakeet Ultra · Q8", "Moondream's tuned Parakeet V3, 25 languages. The same model as the LiteRT entries, for a fair comparison.")
ultra = "litert-community/parakeet-ultra"
models.append({"id": "parakeet-ultra-litert-cpu", "title": "Parakeet Ultra · LiteRT CPU", "engine": "litert", "kind": "tdt_cpu",
    "params": d["params"], "license": d["license"]["display"], "source": "huggingface.co/" + ultra, **acc,
    "note": "Google's LiteRT runtime, int8, on the CPU through XNNPACK. Separate graphs for 10, 20 and 30 s windows.",
    "files": files(ultra, [f"parakeet_ultra_mel_{w}s.tflite" for w in (10, 20, 30)] + [f"parakeet_ultra_encoder_{w}s_i8.tflite" for w in (10, 20, 30)] + ["parakeet_ultra_decoder_i8.tflite", "tokenizer.json"])})
models.append({"id": "parakeet-ultra-litert-gpu", "title": "Parakeet Ultra · LiteRT GPU", "engine": "litert", "kind": "tdt_gpu",
    "params": d["params"], "license": d["license"]["display"], "source": "huggingface.co/" + ultra, **acc,
    "note": "Google's LiteRT runtime, float16, on the graphics chip. One 20 s window graph; longer takes run in 20 s pieces.",
    "files": files(ultra, ["parakeet_ultra_mel_20s.tflite", "parakeet_ultra_encoder_20s_f16.tflite", "parakeet_ultra_decoder_i8.tflite", "tokenizer.json"])})
models.append({"id": "android-on-device", "title": "Android on-device speech", "engine": "android", "kind": "system", "params": 0,
    "license": "Part of the phone", "source": "android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer",
    "note": "The phone's own recognizer. Works while you talk, so only the end is left when you let go.", "files": []})
json.dump({"models": models}, open(OUT, "w"), indent=2)
for m in models: print(m["id"], sum(f["bytes"] for f in m["files"])//1_000_000, "MB", m.get("librispeechWer"), m.get("fleursWer"))
