#!/usr/bin/env bash
set -euo pipefail

out_dir="${1:?output directory}"
title="${2:-UI snapshots}"
mkdir -p "$out_dir"
report="$out_dir/index.html"

{
  cat <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
<style>
  * { box-sizing: border-box; }
  body {
    margin: 0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: #121212;
    color: #ededed;
    padding: 32px 24px 64px;
    line-height: 1.5;
  }
  .page { max-width: 72rem; margin: 0 auto; }
  h1 { font-size: 1.5rem; font-weight: 600; margin: 0 0 8px; }
  .lede { color: #b3b3b3; margin: 0 0 32px; }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 24px;
  }
  figure { margin: 0; }
  figcaption {
    margin-top: 8px;
    font-size: 0.875rem;
    color: #b3b3b3;
  }
  img { max-width: 100%; height: auto; display: block; border-radius: 12px; background: #1e1e1e; }
</style>
</head>
<body>
<main class="page">
  <h1>${title}</h1>
  <p class="lede">Throwaway snapshot evidence. Isolated component and in-session Session Chat.</p>
  <div class="grid">
EOF

  shopt -s nullglob
  found=0
  for png in "$out_dir"/*.png; do
    found=1
    base="$(basename "$png")"
    stem="${base%.png}"
    printf '    <figure>\n      <img src="%s" alt="%s">\n      <figcaption>%s</figcaption>\n    </figure>\n' \
      "$base" "$stem" "$stem"
  done
  if [[ "$found" -eq 0 ]]; then
    echo '    <p>No PNG files in this directory.</p>'
  fi

  cat <<'EOF'
  </div>
</main>
</body>
</html>
EOF
} > "$report"

echo "$report"
