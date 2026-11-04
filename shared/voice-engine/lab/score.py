#!/usr/bin/env python3
"""Scores speech model lab results: one Markdown table per phone and clip set, from the latest run of each model
and setting. Takes the Android lab's lab/results.jsonl and the iOS lab's lab/<set>.jsonl files alike, and scores
them the same way as the app (Wer in SpeechLab.kt): case, punctuation, camelCase, digits against words and
hesitations ("uh", "um") don't count as errors. "Differs from Hex" compares against Parakeet V2 Q8 on transcribe.cpp
from the same phone, or from any Android phone for the iPhone (transcribe.cpp gives the same text everywhere).

  python3 score.py <results.jsonl> [<results.jsonl> ...]
"""
import collections
import json
import re
import sys

FILLERS = {"uh","um","uhm","umm","er","erm","ah","eh","hmm","hm","mm","mhm","mmm"}
def words(t):
    t = re.sub(r"([a-z])([A-Z])", r"\1 \2", t); t = re.sub(r"([A-Za-z])([0-9])", r"\1 \2", t); t = re.sub(r"([0-9])([A-Za-z])", r"\1 \2", t)
    t = t.replace("%", " percent ").replace("'", "").lower()
    ones = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
    tens = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
    def spell(n):
        if n < 20: return [ones[n]]
        if n < 100: return [tens[n // 10]] + ([ones[n % 10]] if n % 10 else [])
        if n < 1000: return [ones[n // 100], "hundred"] + (spell(n % 100) if n % 100 else [])
        return spell(n // 1000) + ["thousand"] + (spell(n % 1000) if n % 1000 else [])
    out = []
    for w in re.split(r"[^a-z0-9]+", t):
        if not w or w in FILLERS: continue
        out += spell(int(w)) if w.isdigit() and len(w) <= 4 else [w]
    return out
def errors(r, h):
    r, h = words(r), words(h); prev = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        row = [i] + [0] * len(h)
        for j in range(1, len(h) + 1):
            row[j] = min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (r[i - 1] != h[j - 1]))
        prev = row
    return prev[-1]
rows = [json.loads(l) for f in sys.argv[1:] for l in open(f) if l.strip()]
rows = [r for r in rows if not r["clipId"].startswith("voice:")]
setname = lambda r: r["clipId"].split("/")[0] if "/" in r["clipId"] else "technical"
# Hex's model on Hex's engine from any phone, by clip set, for phones without that run (the iPhone)
hex_any = {}
for r in rows:
    if r["modelId"] == "parakeet-v2-q8" and r["options"] == "fast cores":
        k = (r["device"], setname(r))
        hex_any.setdefault(k, []).append(r)
hex_any = {k: [r for r in v if r["runId"] == max(x["runId"] for x in v)] for k, v in hex_any.items()}
for device, cset in sorted({(r["device"], setname(r)) for r in rows}):
    d = [r for r in rows if r["device"] == device and setname(r) == cset]
    groups = collections.defaultdict(list)
    for r in d: groups[(r["modelId"], r["options"])].append(r)
    latest = {k: [r for r in v if r["runId"] == max(x["runId"] for x in v)] for k, v in groups.items()}
    hex = latest.get(("parakeet-v2-q8", "fast cores")) or next(
        (v for k, v in hex_any.items() if k[1] == cset), None)
    print(f"\n### {device} · {cset} clips\n")
    print("| Model | Engine · setting | Wait per 10 s | Longest clip | Word errors | Differs from Hex | Load | Memory |")
    print("|---|---|---:|---:|---:|---:|---:|---:|")
    out = []
    for (mid, opt), rs in latest.items():
        audio = sum(r["audioSeconds"] for r in rs); wait = sum(r["waitMs"] for r in rs) / 1000
        long = max(rs, key=lambda r: r["audioSeconds"])
        wer = 100 * sum(errors(r["reference"], r["text"]) for r in rs) / sum(len(words(r["reference"])) for r in rs)
        vs = None
        if hex:
            base = {r["clipId"]: r["text"] for r in hex}
            pairs = [(base[r["clipId"]], r["text"]) for r in rs if r["clipId"] in base]
            vs = 100 * sum(errors(a, b) for a, b in pairs) / max(1, sum(len(words(a)) for a, _ in pairs))
        out.append((wait / audio * 10, f"| {rs[0]['modelTitle']} | {rs[0]['engine']} · {opt} | {wait/audio*10:.2f} s | {long['waitMs']/1000:.2f} s | {wer:.1f}% | {'' if vs is None else f'{vs:.1f}%'} | {rs[0]['loadMs']/1000:.1f} s | {max(r['memoryMb'] for r in rs)} MB |"))
    for _, line in sorted(out): print(line)
