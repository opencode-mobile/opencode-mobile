// Component group: diffs. Compose other groups with Kit.use(id, props).
(() => {
  const { esc, I, sel, text, bool } = Kit;

  /* The one role-to-token table: CodeRoleTokens (Android), OpenCodeCodeColors (iOS). Null draws plain code text. */
  const ROLE_TOKENS = {
    keyword: "syntax-keyword", tag: "syntax-keyword", string: "syntax-string", number: "syntax-number",
    constant: "syntax-constant", comment: "syntax-comment", type: "syntax-type", function: "syntax-function",
    property: "syntax-property", attribute: "syntax-attribute", inserted: "diff-add", deleted: "diff-delete",
    warning: "syntax-diff-unknown", critical: "syntax-critical",
    variable: null, operator: null, punctuation: null, plain: null,
  };
  const PLAIN_CODE = "var(--syntax-foreground, var(--text-strong))";
  const roleColor = (role) => (ROLE_TOKENS[role] ? `var(--${ROLE_TOKENS[role]})` : PLAIN_CODE);

  /* Android floating pill fill: lifts raised toward white only when that moves it further from the page. */
  const rgb = (hex) => {
    const h = String(hex ?? "").replace("#", "");
    return h.length < 6 ? null : [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  };
  const luminance = (c) => {
    const [r, g, b] = c.map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const androidPillFill = (ctx, enabled = true) => {
    const raised = rgb(ctx.tokens?.["surface-raised-base"]), page = rgb(ctx.tokens?.["background-base"]);
    const lift = ctx.dark ? 0.1 : 1;
    let lifts = true;
    if (raised && page) {
      const lifted = raised.map((v) => v + (1 - v) * lift);
      lifts = Math.abs(luminance(lifted) - luminance(page)) >= Math.abs(luminance(raised) - luminance(page));
    }
    const base = lifts ? `color-mix(in oklab, var(--surface-raised-base), white ${lift * 100}%)` : "var(--surface-raised-base)";
    return enabled ? base : `color-mix(in srgb, ${base} 70%, transparent)`;
  };

  /* Diff Stat */
  const count = (v) => {
    if (v === null || v === undefined || v === "") return null;
    const n = parseInt(v, 10);
    return Number.isNaN(n) ? null : Math.max(0, n);
  };
  const statRun = (add, del, enabled, gap) => {
    const parts = [];
    if (add !== null) parts.push(`<span class="${enabled ? "c-ds-add" : "c-ds-off"}">+${add}</span>`);
    if (del !== null) parts.push(`<span class="${enabled ? "c-ds-del" : "c-ds-off"}">−${del}</span>`);
    return parts.join(gap);
  };
  const diffStat = (p) => {
    const add = count(p.additions), del = count(p.deletions), enabled = !p.disabled;
    if (add === null && del === null) return `<span class="c-ds c-ds-${p.style}"></span>`;
    if (p.separator === "split") return `<span class="c-ds c-ds-split c-ds-${p.style}">${statRun(add, del, enabled, "")}</span>`;
    return `<span class="c-ds c-ds-${p.style}">${statRun(add, del, enabled, p.separator === "wide" ? "  " : " ")}</span>`;
  };

  /* Review Changes Pill */
  const reviewPill = (p, ctx) => {
    const files = Math.max(1, parseInt(p.files, 10) || 1), add = Math.max(0, parseInt(p.additions, 10) || 0), del = Math.max(0, parseInt(p.deletions, 10) || 0);
    const enabled = !p.disabled, fileLabel = `${files} ${files === 1 ? "file" : "files"}`;
    const counts = statRun(add > 0 ? add : null, del > 0 ? del : null, enabled, "  ");
    const spoken = ["Review changes", fileLabel, add > 0 ? `${add} ${add === 1 ? "addition" : "additions"}` : null, del > 0 ? `${del} ${del === 1 ? "deletion" : "deletions"}` : null].filter(Boolean).join(", ");
    const fill = ctx.platform === "android" ? ` style="--c-rp-fill:${androidPillFill(ctx, enabled)}"` : "";
    return `<button class="c-rp float press ${enabled ? "" : "is-off"} ${p.pressed && enabled ? "is-pressed" : ""}"${fill} aria-label="${esc(spoken)}" ${enabled ? "" : "disabled"}><span class="c-rp-t">${esc(fileLabel)}${counts ? `  ${counts}` : ""}</span></button>`;
  };

  /* Hunk Navigation Pill */
  const hunkButton = (key, icon, label, enabled, pressed, ctx) =>
    `<button class="c-hn-b ${ctx.platform === "android" ? "press" : ""} ${pressed && enabled ? "is-pressed" : ""}" data-hn="${key}" aria-label="${label}" ${enabled ? "" : "disabled"}>${I(icon, 20)}</button>`;
  const hunkPill = (p, ctx) => {
    const fill = ctx.platform === "android" ? ` style="--c-hn-fill:${androidPillFill(ctx)}"` : "";
    const pill = `<div class="c-hn"${fill}>${[
      hunkButton("previous", "arrow_upward", "Previous hunk", p.previousEnabled, p.pressed === "previous", ctx),
      hunkButton("context", p.contextExpanded ? "collapse_content" : "expand_content", p.contextExpanded ? "Collapse context" : "Expand context", p.contextEnabled, p.pressed === "context", ctx),
      hunkButton("next", "arrow_downward", "Next hunk", p.nextEnabled, p.pressed === "next", ctx),
    ].join("")}</div>`;
    return p.demo ? `<div class="c-hn-demo">${pill}<div class="c-hn-last">No action</div></div>` : pill;
  };

  /* Unified Diff: same parse and collapse rules as UnifiedDiffParser and UnifiedDiffVisibility. */
  const parseRange = (token, prefix) => {
    if (token[0] !== prefix) return null;
    const values = token.slice(1).split(",");
    if (!/^\d+$/.test(values[0]) || (values.length > 1 && !/^\d+$/.test(values[1]))) return null;
    return { start: +values[0], count: values.length > 1 ? +values[1] : 1 };
  };
  const parseHeader = (line, platform) => {
    if (platform === "android") {
      const m = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$/.exec(line);
      return m ? { old: +m[1], new: +m[3] } : null;
    }
    if (!line.replace(/^ +/, "").startsWith("@@")) return null;
    const t = line.split(" ").filter(Boolean);
    if (t.length < 4 || t[0] !== "@@" || t[3] !== "@@") return null;
    const o = parseRange(t[1], "-"), n = parseRange(t[2], "+");
    return o && n ? { old: o.start, new: n.start } : null;
  };
  const parsePatch = (patch, platform) => {
    let oldLine = null, newLine = null, inside = false;
    const lines = patch.split("\n");
    if (patch.endsWith("\n")) lines.pop();
    return lines.map((line, id) => {
      const header = parseHeader(line, platform);
      if (header) { oldLine = header.old; newLine = header.new; inside = true; return { id, kind: "hunk", o: null, n: null, text: line }; }
      if (inside && line[0] === "+") return { id, kind: "addition", o: null, n: newLine++, text: line.slice(1) };
      if (inside && line[0] === "-") return { id, kind: "deletion", o: oldLine++, n: null, text: line.slice(1) };
      if (line.startsWith("\\ No newline at end of file") || !inside || line[0] !== " ") return { id, kind: "metadata", o: null, n: null, text: line };
      return { id, kind: "context", o: oldLine++, n: newLine++, text: line.slice(1) };
    });
  };
  const collapsedRows = (rows, contextLines = 3) => {
    const isChange = (r) => r.kind === "addition" || r.kind === "deletion";
    const keep = rows.map(() => false);
    let last = -1;
    rows.forEach((r, i) => { if (isChange(r)) last = i; if (last >= 0 && i - last <= contextLines) keep[i] = true; });
    let next = -1;
    for (let i = rows.length - 1; i >= 0; i--) { if (isChange(rows[i])) next = i; if (next >= 0 && next - i <= contextLines) keep[i] = true; }
    return rows.filter((r, i) => r.kind !== "context" || keep[i]);
  };
  const DIFF_ROLE = { addition: "inserted", deletion: "deleted", hunk: "warning", metadata: "warning" };
  const diffSamples = (platform) => {
    const ios = platform !== "android", ext = ios ? "swift" : "kt", decl = ios ? "let" : "val", print = ios ? "print" : "println";
    return {
      Modified: { path: `Sources/${ios ? "ChangesView" : "ChangesScreen"}.${ext}`, status: "Modified", additions: 2, deletions: 2,
        patch: `@@ -1,8 +1,8 @@\n one\n two\n three\n four\n-old value\n+${decl} newValue = buildAnIntentionallyLongLineThatRequiresHorizontalScrollingToReviewSafely()\n six\n seven\n eight\n@@ -20 +20 @@\n-before\n+naïve 🚀\n\\ No newline at end of file` },
      Added: { path: `Sources/NewFile.${ext}`, status: "Added", additions: 2, deletions: 0, patch: `@@ -0,0 +1,2 @@\n+${decl} value = 1\n+${print}(value)` },
      Deleted: { path: `Sources/OldFile.${ext}`, status: "Deleted", additions: 0, deletions: 2, patch: `@@ -1,2 +0,0 @@\n-${decl} value = 1\n-${print}(value)` },
      "Large line numbers": { path: `Sources/LongFile.${ext}`, status: "Modified", additions: 1, deletions: 1, patch: `@@ -1204,3 +1204,3 @@\n ${decl} first = 1\n-${decl} second = 2\n+${decl} second = 3\n ${decl} third = 4` },
    };
  };
  const unifiedDiff = (p, ctx) => {
    if (p.sample === "Unavailable") return `<div class="c-ud-none">A text preview is unavailable for assets/logo.png.</div>`;
    const file = diffSamples(ctx.platform)[p.sample] ?? diffSamples(ctx.platform).Modified;
    const all = parsePatch(file.patch, ctx.platform), rows = p.context === "expanded" ? all : collapsedRows(all);
    const digits = String(Math.max(0, ...rows.map((r) => Math.max(r.o ?? 0, r.n ?? 0)))).length;
    const gutter = 24 + 9 * Math.max(0, digits - 2);
    const selectedId = p.selectedHunk ? all.find((r) => r.kind === "hunk")?.id : undefined;
    const html = rows.map((r) => {
      const fg = DIFF_ROLE[r.kind] ? roleColor(DIFF_ROLE[r.kind]) : "var(--text-strong)";
      const where = [r.o !== null ? `old line ${r.o}` : null, r.n !== null ? `new line ${r.n}` : null].filter(Boolean);
      const label = [r.kind, ...where, r.text].join(", ");
      const marker = r.kind === "addition" ? "+" : r.kind === "deletion" ? "−" : "";
      return `<div class="c-ud-row c-ud-${r.kind} ${r.id === selectedId ? "is-sel" : ""}" style="--c-ud-fg:${fg}" aria-label="${esc(label)}"><span class="c-ud-ln">${r.o ?? ""}</span><span class="c-ud-ln">${r.n ?? ""}</span><span class="c-ud-m">${marker}</span><span class="c-ud-tx">${esc(r.text)}</span></div>`;
    }).join("");
    const summary = `${file.status} file ${file.path}, ${file.additions} additions, ${file.deletions} deletions`;
    return `<div class="c-ud" role="group" aria-label="${esc(summary)}" style="--c-ud-g:${gutter}"><div class="c-ud-scroll"><div class="c-ud-rows">${html}</div></div></div>`;
  };

  /* Code File */
  const SWIFT_LINES = ["import SwiftUI", "", "// Lists one project folder.", "struct FilesView: View {", "    let worktree: String", "    var body: some View {", '        Text("A long line that keeps going so horizontal scrolling and wrapping are easy to compare")', "    }", "}"];
  const SWIFT_SPANS = {
    0: [[0, 6, "keyword"], [7, 14, "type"]], 2: [[0, 29, "comment"]], 3: [[0, 6, "keyword"], [7, 16, "type"]],
    4: [[4, 7, "keyword"], [18, 24, "type"]], 5: [[4, 7, "keyword"], [19, 23, "keyword"]], 6: [[8, 12, "function"], [13, 100, "string"]],
  };
  const KOTLIN_SOURCE = 'package demo\n\n/** Greets everyone in the list. */\nfun greet(names: List<String>) {\n\tfor (name in names) {\n        println("Hello, $name! This line is long enough to need horizontal scrolling on a phone screen.")\n    }\n}';
  const JSON_SOURCE = '{\n  "name": "opencode-mobile",\n  "private": true,\n  "version": 1\n}';
  const tokenSpans = (line, rules) => {
    const spans = [];
    const re = new RegExp(rules.map(([source]) => `(${source})`).join("|"), "g");
    for (let m; (m = re.exec(line)); ) {
      if (!m[0]) { re.lastIndex++; continue; }
      const group = rules.findIndex((_, i) => m[i + 1] !== undefined);
      if (rules[group][1]) spans.push([m.index, m.index + m[0].length, rules[group][1]]);
    }
    return spans;
  };
  const KOTLIN_RULES = [["/\\*\\*.*\\*/", "comment"], ['"(?:[^"\\\\]|\\\\.)*"', "string"], ["\\b(?:package|fun|for|in)\\b", "keyword"], ["\\b(?:List|String)\\b", "type"], ["\\b(?:greet|println)\\b(?=\\()", "function"]];
  const JSON_RULES = [['"[^"]*"(?=:)', "property"], ['"[^"]*"', "string"], ["\\b(?:true|false|null)\\b", "constant"], ["\\b\\d+\\b", "number"]];
  const fileSample = (sample, platform) => {
    if (sample === "json") { const lines = JSON_SOURCE.split("\n"); return { lines, spans: Object.fromEntries(lines.map((l, i) => [i, tokenSpans(l, JSON_RULES)])), height: platform === "android" ? 240 : 260, bordered: true }; }
    if (sample === "plain") {
      if (platform === "android") return { lines: ["Line one", "", "Line three after an empty line"], spans: {}, height: 240, bordered: true };
      return { lines: Array.from({ length: 400 }, (_, i) => `Line ${i + 1} of a long plain text file`), spans: {}, height: 160, bordered: false };
    }
    if (platform === "android") {
      const lines = KOTLIN_SOURCE.replace(/\t/g, "    ").split("\n");
      return { lines, spans: Object.fromEntries(lines.map((l, i) => [i, tokenSpans(l, KOTLIN_RULES)])), height: 240, bordered: true };
    }
    return { lines: SWIFT_LINES, spans: SWIFT_SPANS, height: 260, bordered: true };
  };
  const markRanges = (line, index) => {
    if (index % 2 !== 1) return [];
    const first = line.search(/[^ ]/);
    if (first < 0) return [];
    const end = line.indexOf(" ", first);
    return end > 0 ? [[0, end]] : [];
  };
  const codeLine = (line, spans, marks) => {
    if (!line) return " ";
    const cuts = new Set([0, line.length]);
    for (const [s, e] of [...spans, ...marks]) { cuts.add(Math.min(s, line.length)); cuts.add(Math.min(e, line.length)); }
    const points = [...cuts].sort((a, b) => a - b);
    let out = "";
    for (let i = 0; i < points.length - 1; i++) {
      const a = points[i], b = points[i + 1];
      const span = spans.find(([s, e]) => s <= a && e >= b), marked = marks.some(([s, e]) => s <= a && e >= b);
      const style = `${span ? `color:${roleColor(span[2])};` : ""}${marked ? "background:color-mix(in srgb,var(--warning) 28%,transparent);" : ""}`;
      out += style ? `<span style="${style}">${esc(line.slice(a, b))}</span>` : esc(line.slice(a, b));
    }
    return out;
  };
  const codeFile = (p, ctx) => {
    const s = fileSample(p.sample, ctx.platform);
    const digits = Math.max(2, String(s.lines.length).length);
    const rows = s.lines.map((line, i) => {
      const spans = p.highlighted ? s.spans[i] ?? [] : [], marks = p.marked ? markRanges(line, i) : [];
      return `<div class="c-cf-row" aria-label="Line ${i + 1}, ${esc(line)}"><span class="c-cf-n">${i + 1}</span><span class="c-cf-tx">${codeLine(line, spans, marks)}</span></div>`;
    }).join("");
    return `<div class="c-cf ${p.wrap ? "is-wrap" : ""} ${s.bordered ? "is-box" : ""}" style="--c-cf-d:${digits};--c-cf-h:${s.height}px"><div class="c-cf-scroll"><div class="c-cf-rows">${rows}</div></div></div>`;
  };

  const DIFF_STAT_STYLES = [["Caption", { style: "caption" }], ["Button", { style: "button" }], ["Mono", { style: "mono" }], ["Label", { style: "label" }]];
  const REVIEW_SUMMARIES = [["Mixed", { files: "5", additions: "12", deletions: "3" }], ["Additions only", { files: "5", additions: "12", deletions: "0" }], ["Deletions only", { files: "5", additions: "0", deletions: "3" }], ["Zero lines", { files: "5", additions: "0", deletions: "0" }], ["1 file", { files: "1", additions: "1", deletions: "1" }]];
  const HUNK_BASE = { previousEnabled: true, nextEnabled: true, contextEnabled: true, contextExpanded: false };

  Kit.define([
    {
      id: "unified-diff", doc: "unified-diff", align: "stretch", pad: true,
      params: [sel("sample", ["Modified", "Added", "Deleted", "Large line numbers", "Unavailable"]), sel("context", ["collapsed", "expanded"]), bool("selectedHunk", true, "selected hunk")],
      states: [["Modified", {}], ["Expanded context", { context: "expanded" }], ["No selected hunk", { selectedHunk: false }], ["Added", { sample: "Added" }], ["Deleted", { sample: "Deleted" }], ["Large line numbers", { sample: "Large line numbers" }], ["Unavailable", { sample: "Unavailable" }]],
      sheet: [
        { title: "Context", kind: "stack", inset: true, base: {}, items: [["Collapsed, first hunk selected", {}], ["Expanded", { context: "expanded" }], ["No selected hunk", { selectedHunk: false }]] },
        { title: "File status", kind: "stack", inset: true, base: {}, items: [["Added", { sample: "Added" }], ["Deleted", { sample: "Deleted" }], ["Gutter grows past two digits", { sample: "Large line numbers" }], ["Unavailable, owned by the screen", { sample: "Unavailable" }]] },
      ],
      tokens: [["unifiedDiff.lineMinHeight", 20], ["unifiedDiff.linePaddingHorizontal", 8], ["unifiedDiff.markerWidth", 20], ["unifiedDiff.lineNumberGutterWidth", "24 per column, +9 per digit past 2"], ["unifiedDiff.cornerRadius", 8], ["unifiedDiff.defaultContextLines", 3], ["surface", "surface-base"], ["border", "border-weaker-base"], ["addition text", "diff-add"], ["deletion text", "diff-delete"], ["hunk and metadata text", "syntax-diff-unknown"], ["hunk fill", "surface-raised-base"], ["selected hunk fill", "surface-interactive-weak"], ["addition fill iOS", "surface-success-base"], ["deletion fill iOS", "surface-critical-base"], ["addition fill Android", "diff-add at 18%"], ["deletion fill Android", "diff-delete at 16%"]],
      source: { ios: "Components/Chat/OpenCodeUnifiedDiff.swift", android: "ui/components/chat/OpenCodeUnifiedDiff.kt" },
      divergences: [
        { severity: "high", title: "iOS treats an indented @@ context line as a hunk header" },
        { severity: "medium", title: "Addition and deletion row fills use different tokens" },
        { severity: "medium", title: "Line numbers differ in alignment and color" },
        { severity: "medium", title: "Diff surface ignores the syntax preset's code colors" },
        { severity: "low", title: "Docs list caption type and hunk spacing that the code does not use" },
      ],
      render: unifiedDiff,
    },
    {
      id: "code-file", align: "stretch", pad: true,
      params: [sel("sample", ["source", "json", "plain"]), bool("wrap", false, "wrap lines"), bool("highlighted", true), bool("marked", false, "marked ranges")],
      states: [["Highlighted", {}], ["Wrapped", { wrap: true }], ["Marked ranges", { marked: true }], ["Not highlighted", { highlighted: false }], ["JSON", { sample: "json" }], ["Plain text", { sample: "plain", highlighted: false }]],
      sheet: [
        { title: "Line layout", kind: "stack", inset: true, base: {}, items: [["Unwrapped, scrolls sideways", {}], ["Wrapped", { wrap: true }]] },
        { title: "Content", kind: "stack", inset: true, base: {}, items: [["Marked ranges", { marked: true }], ["Not highlighted", { highlighted: false }], ["JSON", { sample: "json" }], ["Plain text", { sample: "plain", highlighted: false }]] },
      ],
      tokens: [["codeFile.lineMinHeight", 20], ["codeFile.verticalPadding", 8], ["codeFile.minGutterDigits", 2], ["codeFile.markOpacity", 0.28], ["iOS horizontalPadding", 12], ["iOS gutterSpacing", 12], ["Android gutterPadding", 8], ["Android codePadding", 12], ["Android gutter divider", "1, border-weaker-base"], ["line numbers", "text-weaker"], ["plain code", "syntax-foreground, else text-strong"], ["code surface", "syntax-background, else owner's"], ["mark", "warning at 28%"]],
      spec: "## Intent\nRead-only source file with a line-number gutter, optional syntax highlighting, optional marked ranges, and wrap on or off.\n\n## Public API\n- iOS: `lines`, `lineSpans`, `lineMarks`, `wrapsLines`, `maxLineLength`, `topLine`, `onLineAppear`.\n- Android: `lines` (`OpenCodeCodeLine` with spans and marks), `wrapLines`, `contentPadding`, `state`.\n\n## Behavior\n- Rows are lazy, so very long files stay responsive.\n- Code colors come from the shared role table: `OpenCodeCodeColors` on iOS, `CodeRoleTokens` on Android.\n- Unwrapped lines scroll sideways together. Android keeps the gutter fixed, iOS scrolls it with the code.\n- Marks draw on `warning` at 28%.\n- The HTML colors the Kotlin and JSON samples with a small tokenizer in place of the native highlighter, so spans can differ slightly from the Android gallery.",
      source: { ios: "Components/Chat/OpenCodeCodeFile.swift", android: "ui/components/chat/OpenCodeCodeFile.kt" },
      divergences: [
        { severity: "high", title: "Line numbers scroll away on iOS and stay fixed on Android" },
        { severity: "medium", title: "Gutter geometry and divider differ" },
        { severity: "low", title: "Gallery samples and controls differ" },
        { severity: "low", title: "No component doc" },
      ],
      render: codeFile,
    },
    {
      id: "diff-stat", align: "center",
      params: [text("additions", "12"), text("deletions", "3"), sel("style", ["caption", "button", "mono", "label"]), sel("separator", ["space", "wide", "split"]), bool("disabled")],
      states: [["Mixed", {}], ["Additions only", { deletions: "" }], ["Deletions only", { additions: "" }], ["Zeros shown", { additions: "0", deletions: "0", separator: "split" }], ["Disabled", { disabled: true, style: "button" }], ["File row", { additions: "8", deletions: "2", style: "label", separator: "split" }]],
      sheet: [
        { title: "Style × separator", kind: "matrix", base: {}, rows: DIFF_STAT_STYLES, cols: [["Space", { separator: "space" }], ["Wide", { separator: "wide" }], ["Split", { separator: "split" }]] },
        { title: "Style × enabled", kind: "matrix", base: {}, rows: DIFF_STAT_STYLES, cols: [["Enabled", {}], ["Disabled", { disabled: true }]] },
        { title: "Content", kind: "rows", items: [["Mixed", {}], ["Additions only", { deletions: "" }], ["Deletions only", { additions: "" }], ["Zeros shown", { additions: "0", deletions: "0", separator: "split" }], ["File row", { additions: "8", deletions: "2", style: "label", separator: "split" }]] },
      ],
      tokens: [["diffStat.splitSpacing", 12], ["separator.space", "1 space"], ["separator.wide", "2 spaces"], ["addition", "diff-add"], ["deletion", "diff-delete"], ["disabled", "text-base"], ["caption", "13/18 regular"], ["button", "14 medium"], ["mono", "13/18 semibold mono"], ["label iOS", "13/18 regular"], ["label Android", "12/16 medium, +0.5 tracking"]],
      spec: "## Intent\nCompact `+N` and `−M` line counts for tool rows, floating pills, branch pills, and changed-file rows.\n\n## Public API\n- `additions` and `deletions`: optional, nonnegative. A missing side is omitted, and zero is shown when passed.\n- `enabled`: disabled counts use `text-base`.\n- `style`: caption, button, mono, label.\n- `separator`: space, wide, split.\n\n## Behavior\nSpace and wide draw one text run. Split draws two labels 12 apart. The deletion mark is a true minus sign (U+2212).",
      source: { ios: "Components/Feedback/OpenCodeDiffStat.swift", android: "ui/components/feedback/OpenCodeDiffStat.kt" },
      divergences: [
        { severity: "medium", title: "Label style has different size, weight, and tracking" },
        { severity: "low", title: "With no counts, Android draws nothing and iOS keeps an empty view" },
        { severity: "low", title: "No component doc" },
      ],
      render: diffStat,
    },
    {
      id: "hunk-navigation-pill", doc: "hunk-navigation-pill", align: "center", dock: true,
      params: [bool("previousEnabled", true, "previous enabled"), bool("nextEnabled", true, "next enabled"), bool("contextEnabled", true, "context enabled"), bool("contextExpanded", false, "context expanded"), sel("pressed", ["none", "previous", "context", "next"]), bool("demo", false, "show last action")],
      states: [["Middle", {}], ["First", { previousEnabled: false }], ["Last", { nextEnabled: false }], ["Expanded", { contextExpanded: true }], ["No context", { contextEnabled: false }], ["Previous pressed", { pressed: "previous" }], ["Context pressed", { pressed: "context" }], ["Next pressed", { pressed: "next" }]],
      sheet: [
        { title: "Position", kind: "rows", items: [["First", { ...HUNK_BASE, previousEnabled: false }], ["Middle", HUNK_BASE], ["Last", { ...HUNK_BASE, nextEnabled: false }]] },
        { title: "Context", kind: "rows", items: [["Collapsed", HUNK_BASE], ["Expanded", { ...HUNK_BASE, contextExpanded: true }], ["No context", { ...HUNK_BASE, contextEnabled: false }]] },
        { title: "Pressed", kind: "rows", items: [["Previous", { ...HUNK_BASE, pressed: "previous" }], ["Context", { ...HUNK_BASE, pressed: "context" }], ["Next", { ...HUNK_BASE, pressed: "next" }]] },
        { title: "Try it", kind: "rows", items: [["Interactive", { ...HUNK_BASE, demo: true }]] },
      ],
      tokens: [["hunkNavigationPill.width", 132], ["hunkNavigationPill.minHeight", 44], ["hunkNavigationPill.iconButtonSize", 44], ["hunkNavigationPill.iconSize", 20], ["hunkNavigationPill.shadowElevation", 4], ["iOS fill", "surface-raised-base"], ["iOS border", "border-weaker-base"], ["Android fill", "lifted surface-raised-base"], ["Android border", "text-strong at 10%"], ["pressed", "surface-raised-base-hover"], ["icon", "icon-strong-base"], ["disabled icon", "icon-weak-base"], ["iOS press", "120 ms ease-out"]],
      source: { ios: "Components/Chrome/OpenCodeHunkNavigationPill.swift", android: "ui/components/chrome/OpenCodeHunkNavigationPill.kt" },
      divergences: [
        { severity: "medium", title: "Android fill and border differ from iOS and the doc" },
        { severity: "medium", title: "Pressed state is almost invisible in dark themes on iOS" },
        { severity: "low", title: "Pressed highlight is a full segment on iOS and a circle on Android" },
      ],
      render: hunkPill,
      mount: (stage) => {
        stage.querySelectorAll(".c-hn-demo").forEach((demo) => {
          if (demo.dataset.bound) return;
          demo.dataset.bound = "1";
          const last = demo.querySelector(".c-hn-last");
          demo.querySelectorAll(".c-hn-b").forEach((b) => b.addEventListener("click", () => {
            if (b.disabled) return;
            if (b.dataset.hn === "context") {
              const expanded = b.getAttribute("aria-label") === "Expand context";
              b.setAttribute("aria-label", expanded ? "Collapse context" : "Expand context");
              b.querySelector(".i").outerHTML = I(expanded ? "collapse_content" : "expand_content", 20);
              last.textContent = expanded ? "Expand context" : "Collapse context";
            } else last.textContent = b.dataset.hn === "previous" ? "Previous hunk" : "Next hunk";
          }));
        });
      },
    },
    {
      id: "review-changes-pill", doc: "review-changes-pill", align: "center",
      params: [text("files", "5"), text("additions", "12"), text("deletions", "3"), bool("disabled"), bool("pressed")],
      states: [...REVIEW_SUMMARIES, ["Pressed", { pressed: true }], ["Disabled", { disabled: true }], ["Disabled, zero lines", { disabled: true, additions: "0", deletions: "0" }]],
      sheet: [
        { title: "Enabled", kind: "rows", items: REVIEW_SUMMARIES },
        { title: "Disabled", kind: "rows", items: REVIEW_SUMMARIES.map(([label, v]) => [label, { ...v, disabled: true }]) },
        { title: "Pressed", kind: "rows", items: [["Mixed", { pressed: true }]] },
      ],
      tokens: [["reviewChangesPill.minHeight", 36], ["reviewChangesPill.paddingHorizontal", 14], ["reviewChangesPill.paddingVertical", "8 on iOS, 0 on Android"], ["reviewChangesPill.shadowElevation", 4], ["file label", "text-strong"], ["additions", "diff-add"], ["deletions", "diff-delete"], ["disabled text", "text-base"], ["iOS fill", "surface-raised-base at 92%, disabled 70%"], ["iOS disabled opacity", 0.72], ["Android fill", "lifted surface-raised-base, disabled 70%"], ["Android border", "text-strong at 10%"], ["iOS press", "scale 0.98, opacity 0.92, 160 ms"]],
      source: { ios: "Components/Chat/OpenCodeReviewChangesPill.swift", android: "ui/components/chat/OpenCodeReviewChangesPill.kt" },
      divergences: [
        { severity: "medium", title: "Android fill and border differ from the documented stand-in" },
        { severity: "medium", title: "Disabled pill dims all content on iOS and only the fill on Android" },
        { severity: "low", title: "Android omits the documented 8 vertical padding" },
      ],
      render: reviewPill,
    },
  ]);
})();
