#!/usr/bin/env python3
"""Writes the speech model lab's model list, adapter/android/src/main/assets/lab/models.json.

Every file is pinned to the Hugging Face revision current when this runs, with its size and SHA-256. The published
word error rates (LibriSpeech test-clean, FLEURS English, percent) are copied from transcribe.cpp's model catalog,
which measured them for the same weights.

  python3 make-models.py
"""
import hashlib
import json
import os
import urllib.request

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../adapter/android/src/main/assets/lab/models.json")


def api(url):
    return json.load(urllib.request.urlopen(url))


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


PARAKEET_V2 = {"params": 617869958, "license": "CC-BY-4.0"}
PARAKEET_ULTRA = {"params": 627265543, "license": "CC-BY-4.0", "librispeechWer": 1.8, "fleursWer": 4.26}
V2_ONNX = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
ULTRA = "litert-community/parakeet-ultra"

models = [
    {"id": "parakeet-v2-onnx-int8", "title": "Parakeet V2 · ONNX int8", "engine": "sherpa-onnx", "kind": "nemo_transducer",
     **PARAKEET_V2, "source": "huggingface.co/" + V2_ONNX,
     "note": "The default. The model the iPhone runs, on ONNX Runtime with XNNPACK.",
     "files": files(V2_ONNX, ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"])},
    {"id": "parakeet-ultra-litert-cpu", "title": "Parakeet Ultra · LiteRT CPU", "engine": "litert", "kind": "tdt_cpu",
     **PARAKEET_ULTRA, "source": "huggingface.co/" + ULTRA,
     "note": "Google's LiteRT runtime, int8, on the CPU through XNNPACK. Separate graphs for 10, 20 and 30 s windows.",
     "files": files(ULTRA, [f"parakeet_ultra_mel_{w}s.tflite" for w in (10, 20, 30)]
                    + [f"parakeet_ultra_encoder_{w}s_i8.tflite" for w in (10, 20, 30)]
                    + ["parakeet_ultra_decoder_i8.tflite", "tokenizer.json"])},
    {"id": "parakeet-ultra-litert-gpu", "title": "Parakeet Ultra · LiteRT GPU", "engine": "litert", "kind": "tdt_gpu",
     **PARAKEET_ULTRA, "source": "huggingface.co/" + ULTRA,
     "note": "Google's LiteRT runtime, float16, on the graphics chip. One 20 s window graph; longer takes run in 20 s pieces.",
     "files": files(ULTRA, ["parakeet_ultra_mel_20s.tflite", "parakeet_ultra_encoder_20s_f16.tflite",
                            "parakeet_ultra_decoder_i8.tflite", "tokenizer.json"])},
    {"id": "android-on-device", "title": "Android on-device speech", "engine": "android", "kind": "system", "params": 0,
     "license": "Part of the phone", "source": "android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer",
     "note": "The phone's own recognizer. Works while you talk, so only the end is left when you let go.", "files": []},
]
json.dump({"models": models}, open(OUT, "w"), indent=2)
for m in models:
    print(m["id"], sum(f["bytes"] for f in m["files"]) // 1_000_000, "MB")
