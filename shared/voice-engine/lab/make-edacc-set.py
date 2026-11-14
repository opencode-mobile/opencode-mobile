#!/usr/bin/env python3
"""Picks 100 utterances of real accented speech from EdAcc's test split (edinburghcstr/edacc on Hugging Face) as a
lab clip set: 3-20 s, at least 6 words, at most 5 per first language, converted to 16 kHz mono WAV with afconvert.
The set is for testing only: copy it onto a phone, never commit it.

  pip install pyarrow soundfile
  python3 make-edacc-set.py <folder of downloaded test-*.parquet shards> <output folder>
"""
import collections
import io
import json
import os
import random
import re
import subprocess
import sys

import pyarrow.parquet as pq
import soundfile as sf

shards, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
rows = []
for f in sorted(os.listdir(shards)):
    if f.endswith(".parquet"):
        rows += pq.read_table(os.path.join(shards, f)).to_pylist()
random.seed(7)
random.shuffle(rows)
per_l1 = collections.Counter(); picked = []
for r in rows:
    text = r["text"].strip()
    if re.search(r"[<\[\(]", text) or len(text.split()) < 6: continue
    info = sf.info(io.BytesIO(r["audio"]["bytes"]))
    secs = info.frames / info.samplerate
    if not (3 <= secs <= 20): continue
    if per_l1[r["l1"]] >= 5: continue
    per_l1[r["l1"]] += 1
    picked.append((r, secs))
    if len(picked) == 100: break
clips = []
for i, (r, secs) in enumerate(picked):
    cid = f"edacc{i:03d}"
    raw = f"{out}/{cid}.src.wav"
    open(raw, "wb").write(r["audio"]["bytes"])
    subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", raw, f"{out}/{cid}.wav"], check=True)
    os.remove(raw)
    clips.append({"id": cid, "file": f"{cid}.wav", "seconds": round(secs, 2), "voice": f'{r["l1"]} ({r["accent"]})', "reference": r["text"].strip().lower()})
json.dump({"note": "100 utterances from the EdAcc test split (Edinburgh International Accents of English Corpus): real conversations, speakers of many first languages. Not for redistribution.", "clips": clips}, open(f"{out}/clips.json", "w"), indent=1)
print(len(clips), round(sum(c["seconds"] for c in clips)), "s,", len(per_l1), "first languages:", ", ".join(sorted(per_l1)))
print(sum(len(c["reference"].split()) for c in clips), "words")
