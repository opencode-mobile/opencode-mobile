#!/usr/bin/env python3
"""Score iOS MessageContentPreparer displayPublish pacing from simulator logs.

Exit 0 (GREEN) when late-stream publishes stay responsive.
Exit 1 (RED) when p90 sinceLastMs for chars>=15k exceeds the threshold.

Usage:
  python3 scripts/analyze-ios-stream-pacing.py /path/to/stream.log
  xcrun simctl spawn <UDID> log show --predicate 'subsystem == "dev.opencode.mobile" AND category == "MessageContentPreparer"' --style compact --last 5m --info --debug \\
    | python3 scripts/analyze-ios-stream-pacing.py -
"""

from __future__ import annotations

import re
import statistics
import sys
from pathlib import Path

PUBLISH_RE = re.compile(
    r"displayPublish reason=(\S+) streaming=(\S+) chars=(\d+) sinceLastMs=(-?\d+)"
)
APPLY_RE = re.compile(r"\[DEBUG-ios-stream\] timelineApply ms=(\d+).*streamingChars=(\d+)")

# Late-stream publish gap must stay near the 160ms max-batch target.
LATE_CHAR_THRESHOLD = 15_000
P90_MS_THRESHOLD = 240


def load_text(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    return Path(path).read_text(errors="ignore")


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2

    text = load_text(sys.argv[1])
    publishes = []
    for line in text.splitlines():
        match = PUBLISH_RE.search(line)
        if match:
            publishes.append(
                (
                    match.group(1),
                    match.group(2) == "true",
                    int(match.group(3)),
                    int(match.group(4)),
                )
            )

    streaming = [row for row in publishes if row[1] and row[3] >= 0]
    print(f"publishes={len(publishes)} streaming_with_sinceLast={len(streaming)}")
    if not streaming:
        print("VERDICT RED reason=no-streaming-publishes")
        return 1

    buckets: dict[str, list[int]] = {}
    for _, _, chars, ms in streaming:
        if chars < 5_000:
            key = "<5k"
        elif chars < 15_000:
            key = "<15k"
        elif chars < 25_000:
            key = "<25k"
        else:
            key = ">=25k"
        buckets.setdefault(key, []).append(ms)

    for key, values in buckets.items():
        ordered = sorted(values)
        p50 = ordered[len(ordered) // 2]
        p90 = ordered[min(len(ordered) - 1, int(len(ordered) * 0.9))]
        print(
            f"{key}: n={len(values)} p50={p50} p90={p90} max={max(values)} "
            f"mean={statistics.mean(values):.0f}"
        )

    late = [ms for _, _, chars, ms in streaming if chars >= LATE_CHAR_THRESHOLD]
    if not late:
        print(f"VERDICT RED reason=no-publishes-chars>={LATE_CHAR_THRESHOLD}")
        return 1

    ordered_late = sorted(late)
    p90_late = ordered_late[min(len(ordered_late) - 1, int(len(ordered_late) * 0.9))]
    applies = []
    for line in text.splitlines():
        match = APPLY_RE.search(line)
        if match:
            applies.append((int(match.group(1)), int(match.group(2))))
    if applies:
        late_applies = [ms for ms, chars in applies if chars >= LATE_CHAR_THRESHOLD]
        if late_applies:
            print(
                f"timelineApply late n={len(late_applies)} "
                f"p50={sorted(late_applies)[len(late_applies)//2]} "
                f"max={max(late_applies)}"
            )

    verdict = "GREEN" if p90_late <= P90_MS_THRESHOLD else "RED"
    print(
        f"VERDICT {verdict} p90_late={p90_late}ms "
        f"threshold={P90_MS_THRESHOLD}ms chars>={LATE_CHAR_THRESHOLD}"
    )
    return 0 if verdict == "GREEN" else 1


if __name__ == "__main__":
    raise SystemExit(main())
