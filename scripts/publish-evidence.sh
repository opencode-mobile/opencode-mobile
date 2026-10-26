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

# Percent-encode everything outside RFC 3986 unreserved characters.
encode() {
  local LC_ALL=C s="$1" out="" c i
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      [A-Za-z0-9._~-]) out+="$c" ;;
      *) out+="$(printf '%%%02X' $(( $(printf '%d' "'$c") & 255 )))" ;;
    esac
  done
  echo "$out"
}
url() { echo "https://github.com/$repo/blob/$branch/$issue/$(encode "$1")?raw=true"; }
img() { if [ -n "$1" ]; then echo "<img src=\"$(url "$1")\" width=\"240\">"; fi; }
html() { local s="${1//&/&amp;}"; s="${s//\"/&quot;}"; echo "${s//</&lt;}"; }
caption() { echo "$1" | tr '-' ' ' | sed 's/|/\\|/g' | awk '{ print toupper(substr($0, 1, 1)) substr($0, 2) }'; }

steps=()
others=()
for f in "${files[@]}"; do
  if [[ "$f" =~ ^(ios|android)-([0-9]+)-(.+)\.[A-Za-z]+$ ]]; then
    steps+=("${BASH_REMATCH[2]}")
  else
    others+=("$f")
  fi
done

# One row per step when each platform has at most one file for it, so
# differently named iOS and Android shots still pair. Otherwise (light and
# dark, several frames) one row per step and slug.
if [ "${#steps[@]}" -gt 0 ]; then
  echo "| Step | iOS | Android |"
  echo "| --- | --- | --- |"
  for step in $(printf '%s\n' "${steps[@]}" | sort -u | sort -n -s); do
    ios=() android=() slugs=() first=""
    for f in "${files[@]}"; do
      [[ "$f" =~ ^(ios|android)-${step}-(.+)\.[A-Za-z]+$ ]] || continue
      slugs+=("${BASH_REMATCH[2]}")
      if [ "${BASH_REMATCH[1]}" = ios ]; then
        ios+=("$f")
        [ -n "$first" ] || first="${BASH_REMATCH[2]}"
      else
        android+=("$f")
      fi
    done
    if [ "${#ios[@]}" -le 1 ] && [ "${#android[@]}" -le 1 ]; then
      echo "| $step $(caption "${first:-${slugs[0]}}") | $(img "${ios[0]-}") | $(img "${android[0]-}") |"
      continue
    fi
    while IFS= read -r slug; do
      ios_file="" android_file=""
      for f in ${ios[@]+"${ios[@]}"}; do
        if [[ "$f" == "ios-$step-$slug".* ]]; then ios_file="$f"; fi
      done
      for f in ${android[@]+"${android[@]}"}; do
        if [[ "$f" == "android-$step-$slug".* ]]; then android_file="$f"; fi
      done
      echo "| $step $(caption "$slug") | $(img "$ios_file") | $(img "$android_file") |"
    done < <(printf '%s\n' "${slugs[@]}" | LC_ALL=C sort -u)
  done
fi
if [ "${#others[@]}" -gt 0 ]; then
  echo
  for f in "${others[@]}"; do
    echo "<img src=\"$(url "$f")\" width=\"240\" alt=\"$(html "$f")\">"
  done
fi
