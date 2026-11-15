#!/usr/bin/env python3
"""Decide whether a check workflow's job needs to run for this event.

Check workflows can't filter with `on.<event>.paths`: a merge queue ignores it,
and a workflow that never starts leaves a required check pending forever. A job
skipped by `if:` counts as passing, so each check workflow runs this first and
gates its job on the result.

Environment:
  EVENT        github.event_name
  PATTERNS     newline-separated globs, the same syntax and order rules as
               `on.<event>.paths`: a leading `!` excludes, and the last pattern
               that matches a file wins.
  MERGE_BASE   github.event.merge_group.base_sha (merge_group only)
  PUSH_BEFORE  github.event.before (push only)

Writes `relevant=true|false` to $GITHUB_OUTPUT. Anything it can't work out
(manual run, missing base, git error) counts as relevant, so the checks run.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

ZERO_SHA = "0" * 40


def glob_to_regex(glob: str) -> re.Pattern:
    out = []
    i = 0
    while i < len(glob):
        if glob.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif glob.startswith("**", i):
            out.append(".*")
            i += 2
        elif glob[i] == "*":
            out.append("[^/]*")
            i += 1
        elif glob[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(glob[i]))
            i += 1
    return re.compile("".join(out) + r"\Z")


def parse_patterns(text: str) -> list[tuple[bool, re.Pattern]]:
    patterns = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        include = not line.startswith("!")
        patterns.append((include, glob_to_regex(line.lstrip("!"))))
    return patterns


def matches(path: str, patterns: list[tuple[bool, re.Pattern]]) -> bool:
    result = False
    for include, regex in patterns:
        if regex.match(path):
            result = include
    return result


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def ensure_commit(sha: str) -> None:
    try:
        git("cat-file", "-e", f"{sha}^{{commit}}")
    except subprocess.CalledProcessError:
        git("fetch", "--no-tags", "--depth=1", "origin", sha)


def base_for(event: str) -> str | None:
    if event == "pull_request":
        # actions/checkout checks out the pull request's merge commit; its
        # first parent is the base it was merged onto.
        return "HEAD^1"
    if event == "merge_group":
        return os.environ.get("MERGE_BASE") or None
    if event == "push":
        before = os.environ.get("PUSH_BEFORE", "")
        return before if before and before != ZERO_SHA else None
    return None


def write_output(relevant: bool) -> None:
    line = f"relevant={'true' if relevant else 'false'}\n"
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(line)
    print(line, end="")


def main() -> int:
    event = os.environ.get("EVENT", "")
    patterns = parse_patterns(os.environ.get("PATTERNS", ""))
    base = base_for(event)
    if base is None:
        print(f"{event or 'unknown event'}: no base to compare, running the checks")
        write_output(True)
        return 0
    try:
        if base != "HEAD^1":
            ensure_commit(base)
        # --no-renames lists a rename's old and new path, as `paths` does.
        changed = git("diff", "--name-only", "--no-renames", base, "HEAD").splitlines()
    except subprocess.CalledProcessError as error:
        print(f"git failed ({error.stderr.strip()}), running the checks")
        write_output(True)
        return 0
    hits = [path for path in changed if matches(path, patterns)]
    print(f"{len(changed)} changed file(s), {len(hits)} relevant")
    for path in hits[:50]:
        print(f"  {path}")
    write_output(bool(hits))
    return 0


if __name__ == "__main__":
    sys.exit(main())
