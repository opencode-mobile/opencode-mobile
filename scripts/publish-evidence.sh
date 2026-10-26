#!/usr/bin/env bash
# Publish PR evidence screenshots to the orphan branch evidence/<issue> and
# print a markdown table for a PR or issue comment. Files land at
# <issue>/<file> on that branch; an existing branch gets one more commit. The
# caller's checkout, index and branches are untouched (temporary index only).
#
# Name files <platform>-<NN>-<slug>.png with platform ios or android, e.g.
# ios-03-answer-sheet.png and android-03-answer-sheet.png: matching NN pairs
# them into one table row captioned from the slug. Other files are listed
# below the table. PNG and JPEG are scaled to at most 900 px tall with sips
# (macOS); without sips they are published at their original size.
#
# usage: scripts/publish-evidence.sh <issue-number> <dir>
# env:   EVIDENCE_REMOTE (default origin)
#        EVIDENCE_REPO   (default opencode-mobile/opencode-mobile)
set -euo pipefail

issue="${1:?usage: $0 <issue-number> <dir>}"
dir="${2:?usage: $0 <issue-number> <dir>}"
remote="${EVIDENCE_REMOTE:-origin}"
repo="${EVIDENCE_REPO:-opencode-mobile/opencode-mobile}"
max_height=900
branch="evidence/$issue"

[[ "$issue" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || { echo "Invalid issue: $issue" >&2; exit 1; }
[ -d "$dir" ] || { echo "Not a directory: $dir" >&2; exit 1; }
dir="$(cd "$dir" && pwd)"

files=()
while IFS= read -r f; do files+=("$f"); done < <(
  find "$dir" -maxdepth 1 -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.gif' \) \
    -exec basename {} \; | LC_ALL=C sort
)
[ "${#files[@]}" -gt 0 ] || { echo "No .png, .jpg or .gif files in $dir" >&2; exit 1; }

cd "$(dirname "${BASH_SOURCE[0]}")/.."
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
export GIT_INDEX_FILE="$tmp/index"

parent=()
if git ls-remote --exit-code --heads "$remote" "$branch" >/dev/null; then
  git fetch -q --no-write-fetch-head "$remote" "+refs/heads/$branch:refs/remotes/$remote/$branch"
  parent=(-p "refs/remotes/$remote/$branch")
  git read-tree "refs/remotes/$remote/$branch"
else
  git read-tree --empty
fi

command -v sips >/dev/null || echo "sips not found; publishing at original size." >&2
for f in "${files[@]}"; do
  out="$tmp/$f"
  cp "$dir/$f" "$out"
  case "$(echo "$f" | tr '[:upper:]' '[:lower:]')" in
    *.png | *.jpg | *.jpeg)
      if command -v sips >/dev/null; then
        height="$(sips -g pixelHeight "$out" | awk '/pixelHeight/ { print $2 }')"
        if [ "${height:-0}" -gt "$max_height" ]; then
          sips --resampleHeight "$max_height" "$out" >/dev/null
        fi
      fi
      ;;
  esac
  size_kb=$(( $(wc -c < "$out") / 1024 ))
  [ "$size_kb" -le 500 ] || echo "Warning: $f is ${size_kb} KB; every clone downloads it." >&2
  git update-index --add --cacheinfo "100644,$(git hash-object -w "$out"),$issue/$f"
done

tree="$(git write-tree)"
if [ "${#parent[@]}" -gt 0 ] && [ "$tree" = "$(git rev-parse "refs/remotes/$remote/$branch^{tree}")" ]; then
  echo "$branch already has these files; nothing pushed." >&2
else
  commit="$(git commit-tree "$tree" ${parent[@]+"${parent[@]}"} -m "#$issue PR evidence")"
  git push -q "$remote" "$commit:refs/heads/$branch"
  git update-ref "refs/remotes/$remote/$branch" "$commit"
  echo "Pushed ${#files[@]} file(s) to $branch." >&2
fi

url() { echo "https://github.com/$repo/blob/$branch/$issue/$1?raw=true"; }
img() { if [ -n "$1" ]; then echo "<img src=\"$(url "$1")\" width=\"240\">"; fi; }

steps=()
others=()
for f in "${files[@]}"; do
  if [[ "$f" =~ ^(ios|android)-([0-9]+)-(.+)\.[A-Za-z]+$ ]]; then
    step="${BASH_REMATCH[2]}"
    steps+=("$step")
  else
    others+=("$f")
  fi
done

if [ "${#steps[@]}" -gt 0 ]; then
  echo "| Step | iOS | Android |"
  echo "| --- | --- | --- |"
  for step in $(printf '%s\n' "${steps[@]}" | sort -n -u); do
    ios="" android="" caption=""
    for f in "${files[@]}"; do
      [[ "$f" =~ ^(ios|android)-${step}-(.+)\.[A-Za-z]+$ ]] || continue
      [ -n "$caption" ] || caption="$(echo "${BASH_REMATCH[2]}" | tr '-' ' ' | awk '{ print toupper(substr($0, 1, 1)) substr($0, 2) }')"
      if [ "${BASH_REMATCH[1]}" = ios ]; then ios="$f"; else android="$f"; fi
    done
    echo "| $step $caption | $(img "$ios") | $(img "$android") |"
  done
fi
if [ "${#others[@]}" -gt 0 ]; then
  echo
  for f in "${others[@]}"; do
    echo "<img src=\"$(url "$f")\" width=\"240\" alt=\"$f\">"
  done
fi
