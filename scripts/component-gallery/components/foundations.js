// Component group: foundations. Compose other groups with Kit.use(id, props).
(() => {
  const { esc, I, sel, text, bool } = Kit;

  /* ---------- Colors ---------- */

  // data.js is resolved with Android's truncating mix. iOS rounds, so these are the iOS values
  // wherever the two differ. Generated from shared/theme-presets with both resolvers' arithmetic.
  const IOS_TOKEN_DELTA = {"porcelain":{"light":{"surface-raised-base-hover":"#E7E7E7","icon-weak-base":"#D0D0D0","button-secondary-hover":"#E7E7E7","markdown-h1":"#171717","markdown-h3":"#171717","markdown-link-underline":"#171717","markdown-code-text":"#171717","markdown-image":"#A3A3A3","markdown-strong":"#171717"},"dark":{"surface-raised-base-hover":"#1E1E1E","button-secondary-hover":"#1E1E1E","markdown-h1":"#EEEEEE","markdown-h3":"#EEEEEE","markdown-link-underline":"#EEEEEE","markdown-code-text":"#EEEEEE","markdown-image":"#6E6E6E","markdown-hr":"#191919","markdown-strong":"#EEEEEE"}},"linen":{"light":{"surface-raised-base-hover":"#E5E1DA","icon-weak-base":"#D1CDC4","button-secondary-hover":"#E5E1DA","markdown-h1":"#292318","markdown-h3":"#292318","markdown-link-underline":"#292318","markdown-code-text":"#292318","markdown-quote-text":"#868177","markdown-quote-bar":"#DED9D1","markdown-image":"#A8A49A","markdown-hr":"#E8E4DB","markdown-strong":"#292318","markdown-em":"#868177"},"dark":{"surface-raised-base-hover":"#1E1C18","icon-weak-base":"#434039","button-secondary-base":"#100E0B","button-secondary-hover":"#1E1C18","markdown-h1":"#F3E9DA","markdown-h3":"#F3E9DA","markdown-link-underline":"#F3E9DA","markdown-code-text":"#F3E9DA","markdown-quote-text":"#948D82","markdown-quote-bar":"#1F1D18","markdown-image":"#706B62","markdown-hr":"#191612","markdown-strong":"#F3E9DA","markdown-em":"#948D82"}},"signal":{"light":{"markdown-quote-text":"#737373","markdown-em":"#737373"},"dark":{"surface-raised-base-hover":"#0D0D0D","button-secondary-hover":"#0D0D0D","markdown-quote-text":"#949494","markdown-hr":"#080808","markdown-em":"#949494"}},"hotline":{"light":{"surface-raised-base-hover":"#E4E3E4","icon-weak-base":"#CECCCE","button-secondary-hover":"#E4E3E4","markdown-h1":"#1B1619","markdown-h3":"#1B1619","markdown-link-underline":"#1B1619","markdown-code-text":"#1B1619","markdown-quote-text":"#7E7A7D","markdown-quote-bar":"#DBD9DB","markdown-image":"#A3A0A2","markdown-hr":"#E6E4E6","markdown-strong":"#1B1619","markdown-em":"#7E7A7D"},"dark":{"surface-raised-base-hover":"#201E20","icon-weak-base":"#454245","button-secondary-hover":"#201E20","markdown-h1":"#F4EFF3","markdown-h3":"#F4EFF3","markdown-link-underline":"#F4EFF3","markdown-code-text":"#F4EFF3","markdown-quote-text":"#969295","markdown-hr":"#1B191B","markdown-strong":"#F4EFF3","markdown-em":"#969295"}},"phosphor":{"light":{"surface-raised-base-hover":"#E2E3E1","icon-weak-base":"#CBCCCA","button-secondary-hover":"#E2E3E1","markdown-h1":"#141814","markdown-h3":"#141814","markdown-link-underline":"#141814","markdown-code-text":"#141814","markdown-quote-text":"#787B78","markdown-quote-bar":"#D8DAD7","markdown-image":"#9EA09E","markdown-hr":"#E3E5E2","markdown-strong":"#141814","markdown-em":"#787B78"},"dark":{"surface-raised-base-hover":"#1C1E1C","icon-weak-base":"#404240","button-secondary-hover":"#1C1E1C","markdown-h1":"#E9EEE9","markdown-h3":"#E9EEE9","markdown-link-underline":"#E9EEE9","markdown-code-text":"#E9EEE9","markdown-quote-bar":"#1D1F1D","markdown-image":"#6B6E6B","markdown-hr":"#161916","markdown-strong":"#E9EEE9"}}};

  const COLOR_GROUPS = [
    ["Background and surface", "surface", /^(background|surface)-/],
    ["Text", "text", /^text-/],
    ["Border", "border", /^border-/],
    ["Icon", "icon", /^icon-/],
    ["Button", "button", /^button-/],
    ["Status", "status", /^(interactive|success|warning|error|info)$/],
    ["Diff", "diff", /^diff-/],
    ["Syntax", "syntax", /^syntax-/],
    ["Markdown", "markdown", /^markdown-/],
  ];
  // Tokens each native gallery lists. Everything else is resolved but absent from both galleries.
  const NATIVE_GALLERY_TOKENS = new Set(["background-base", "surface-base", "surface-base-hover", "surface-raised-base", "surface-raised-base-hover", "surface-interactive-weak", "surface-success-base", "surface-critical-base", "text-strong", "text-base", "text-weak", "text-weaker", "text-interactive-base", "border-weak-base", "border-weaker-base", "icon-base", "icon-weak-base", "icon-strong-base", "button-primary-base", "button-secondary-base", "interactive", "success", "warning", "error", "info", "diff-add", "diff-delete", "syntax-keyword", "syntax-string", "syntax-number", "syntax-constant", "syntax-comment", "syntax-type", "syntax-function", "syntax-property", "syntax-attribute", "syntax-variable", "syntax-operator", "syntax-punctuation", "syntax-critical", "syntax-diff-delete", "syntax-diff-unknown"]);

  const activeTheme = (ctx) => {
    const themes = window.DS.themes;
    return themes.find((t) => ["light", "dark"].some((m) => Object.keys(t[m]).every((k) => t[m][k] === ctx.tokens[k]))) ?? themes[0];
  };
  const tokenValue = (theme, mode, name, platform) =>
    (platform === "ios" ? IOS_TOKEN_DELTA[theme.id]?.[mode]?.[name] : undefined) ?? theme[mode][name];

  const colorSwatch = (label, hex, unset) => `<div class="c-color-swatch">
    <i${hex ? ` style="background:${esc(hex)}"` : ""}></i>
    <span class="c-color-mode">${label}</span>
    <span class="c-color-hex">${esc(hex ?? unset)}</span>
  </div>`;

  const colorRow = (theme, name, platform) => {
    const light = tokenValue(theme, "light", name, platform), dark = tokenValue(theme, "dark", name, platform);
    const differs = platform === "ios" && (IOS_TOKEN_DELTA[theme.id]?.light?.[name] || IOS_TOKEN_DELTA[theme.id]?.dark?.[name]);
    return `<div class="c-color-row"${differs ? ' data-rounded="1"' : ""}>
      <div class="c-color-name">${esc(name)}${NATIVE_GALLERY_TOKENS.has(name) || /^syntax-(background|foreground)$/.test(name) ? "" : '<small>not in native gallery</small>'}</div>
      <div class="c-color-pair">${colorSwatch("Light", light, "Not set, surface keeps the theme")}${colorSwatch("Dark", dark, "Not set, surface keeps the theme")}</div>
    </div>`;
  };

  const renderColors = (p, ctx) => {
    const theme = activeTheme(ctx), names = Object.keys(theme.light);
    const title = ctx.platform === "ios" ? `Theme tokens (${theme.name}, code: Match theme)` : `Theme tokens (${theme.name})`;
    const sections = COLOR_GROUPS.filter(([, key]) => p.group === "all" || p.group === key).map(([label, key, re]) => {
      const list = names.filter((n) => re.test(n));
      // iOS lists the two code-surface tokens even when unset; Android drops them.
      if (key === "syntax" && ctx.platform === "ios") list.unshift("syntax-background", "syntax-foreground");
      return `<h6 class="c-color-group">${label}<span>${list.length}</span></h6>${list.map((n) => colorRow(theme, n, ctx.platform)).join("")}`;
    }).join("");
    return `<div class="c-color"><div class="c-color-title">${esc(title)}</div>${sections}</div>`;
  };

  /* ---------- Typography ---------- */

  const TYPE_ROLES = [
    { role: "display", sample: "Build anything", ios: [28, 500], android: ["displaySmall", 28, 34, 500, -0.16] },
    { role: "title", sample: "Session Review", ios: [20, 500], android: ["titleLarge", 20, 28, 500, -0.16] },
    { role: "heading", sample: "Permission Request", ios: [16, 500], android: ["titleMedium", 16, 22, 500, 0] },
    { role: "body", sample: "Read session messages and send prompts from mobile.", ios: [16, 400], android: ["bodyLarge", 16, 26, 400, 0] },
    { role: "bodySecondary", sample: "Foreground updates refresh while the app is open.", ios: [14, 400], android: ["bodyMedium", 14, 20, 400, 0] },
    { role: "caption", sample: "Last updated just now", ios: [13, 400], android: ["bodySmall", 13, 18, 400, 0] },
    { role: "code", sample: "POST /session/{id}/message", code: true, ios: [14, 400], android: ["CodeTextStyle", 14, 20, 400, 0] },
    { role: "button", sample: "Approve", ios: [14, 500], android: ["labelLarge", 14, 18, 500, 0] },
  ];
  const TEXT_SIZES = [["Smallest", 0.78], ["Extra small", 0.85], ["Small", 0.92], ["Default", 1], ["Large", 1.12], ["Extra large", 1.26]];
  const weightName = (w) => (w === 500 ? "medium" : "regular");

  const typeStyle = (role, platform) => {
    const family = role.code ? "var(--mono)" : "var(--font)";
    if (platform === "ios") {
      const [size, weight] = role.ios;
      return `font-family:${family};font-size:calc(${size}px * var(--ts, 1));font-weight:${weight};line-height:normal`;
    }
    const [, size, line, weight, tracking] = role.android;
    return `font-family:${family};font-size:calc(${size}px * var(--ts, 1));line-height:calc(${line}px * var(--ts, 1));font-weight:${weight};letter-spacing:calc(${tracking}px * var(--ts, 1))`;
  };

  const typeCard = (role, p, platform) => {
    const [style, size, line, weight, tracking] = role.android;
    return `<div class="c-type-card">
      <div class="c-type-name">type.${role.role}</div>
      <div class="c-type-sample${role.code ? " is-code" : ""}" style="${typeStyle(role, platform)}">${esc(p.sample || role.sample)}</div>
      <dl class="c-type-spec">
        <dt>iOS</dt><dd>${role.ios[0]}pt ${weightName(role.ios[1])}${role.code ? " monospaced" : ""}, system line height</dd>
        <dt>Android</dt><dd>${style} ${size}/${line}sp ${weightName(weight)}${tracking ? `, ${tracking}sp tracking` : ""}</dd>
      </dl>
    </div>`;
  };

  const renderTypography = (p, ctx) => `<div class="c-type">
    <div class="c-type-title">Type Roles</div>
    ${TYPE_ROLES.map((r) => typeCard(r, p, ctx.platform)).join("")}
    ${ctx.platform === "android" ? `<div class="c-type-card">
      <div class="c-type-name">labelSmall <small>no type role</small></div>
      <div class="c-type-sample" style="font-size:calc(12px * var(--ts, 1));line-height:calc(16px * var(--ts, 1))">${esc(p.sample || "Last updated just now")}</div>
      <dl class="c-type-spec"><dt>Android</dt><dd>labelSmall 12/16sp regular</dd></dl>
    </div>` : ""}
    <div class="c-type-title">Text sizes</div>
    <div class="c-type-card c-type-scale">${TEXT_SIZES.map(([n, f]) => `<div><span>${n}</span><code>${f.toFixed(2)}×</code><b style="font-size:calc(${16 * f}px * var(--ts, 1))">Session</b></div>`).join("")}</div>
  </div>`;

  /* ---------- Icons ---------- */

  const renderIcons = (p, ctx) => {
    const q = (p.filter ?? "").trim().toLowerCase();
    const names = Object.keys(window.DS.icons).filter((n) => n.includes(q) || window.DS.icons[n].lucide.includes(q));
    const cells = names.map((n) => `<div class="c-icons-cell" title="lucide: ${esc(window.DS.icons[n].lucide)}"><span class="c-icons-glyph">${I(n, 24)}</span><span class="c-icons-name">${esc(n)}</span></div>`).join("");
    return `<div class="c-icons" data-platform-view="${ctx.platform}">
      <p class="c-icons-intro">Lucide catalog from icons/manifest.yaml</p>
      ${names.length ? `<div class="c-icons-grid">${cells}</div>` : `<p class="c-icons-empty">No icons match “${esc(q)}”.</p>`}
    </div>`;
  };

  /* ---------- File Icon ---------- */

  const FILE_SAMPLES = ["Package.swift", "App.swift", "build.gradle", "Main.kt", "package.json", "index.ts", "App.tsx", "foo.test.ts", "README.md", "Dockerfile", ".gitignore", "notes.zzz"];
  const FOLDER_SAMPLES = ["src", "tests", ".github", "docs", "node_modules", "assets"];

  const basename = (path) => path.replace(/\\/g, "/").split("/").filter(Boolean).pop() ?? "";
  const dottedSuffixes = (name) => {
    const out = new Set([name]);
    for (let i = 0; i < name.length; i++) if (name[i] === "." && i + 1 < name.length) out.add(name.slice(i + 1));
    return [...out].sort((a, b) => b.length - a.length);
  };
  const openVariant = (icon) => {
    if (!icon.startsWith("Folder")) return icon;
    if (icon.endsWith("_light")) return icon.replace("_light", "Open_light");
    return icon.endsWith("Open") ? icon : icon + "Open";
  };
  /** Port of OpenCodeFileIcon.iconName on both platforms. */
  const fileIconName = (path, kind, expanded) => {
    const M = window.DS.fileIconMaps, base = basename(path).toLowerCase();
    if (kind === "directory") {
      for (const c of [base, `.${base}`, `_${base}`, `__${base}__`]) {
        if (M.folderNames[c]) return expanded ? openVariant(M.folderNames[c]) : M.folderNames[c];
      }
      return expanded ? M.defaults.folderOpen : M.defaults.folder;
    }
    if (M.fileNames[base]) return M.fileNames[base];
    for (const s of dottedSuffixes(base)) if (M.fileExtensions[s]) return M.fileExtensions[s];
    return M.defaults.file;
  };
  const fileIcon = (path, { kind = "file", expanded = false, mono = false, size = 24 } = {}) => {
    const M = window.DS.fileIconMaps, name = fileIconName(path, kind, expanded);
    const url = `assets/file-types/${M.icons[name] ?? M.icons[M.defaults.file]}`;
    return mono
      ? `<span class="c-fi-glyph is-mono" style="--fi:${size}px;-webkit-mask-image:url('${url}');mask-image:url('${url}')"></span>`
      : `<img class="c-fi-glyph" style="--fi:${size}px" src="${url}" alt="">`;
  };

  const fileCell = (label, icons) => `<div class="c-fi-cell"><div class="c-fi-icons">${icons}</div><span class="c-fi-label">${esc(label)}</span></div>`;
  const fiBadge = (label, selected) => Kit.components["choice-badge"]
    ? Kit.use("choice-badge", { label, selected })
    : `<span>${esc(label)}</span>`;

  const renderFileIcon = (p) => {
    if (p.mode === "lookup") {
      const kind = p.kind, name = fileIconName(p.path, kind, p.expanded);
      return `<div class="c-fi c-fi-lookup">
        <div class="c-fi-preview">${fileIcon(p.path, { kind, expanded: p.expanded, mono: p.mono, size: +p.size })}</div>
        <dl class="c-type-spec"><dt>Path</dt><dd>${esc(p.path)}</dd><dt>Basename</dt><dd>${esc(basename(p.path).toLowerCase())}</dd><dt>Icon</dt><dd>${esc(name)}</dd><dt>Asset</dt><dd>${esc(window.DS.fileIconMaps.icons[name])}</dd></dl>
      </div>`;
    }
    const mono = !!p.mono;
    return `<div class="c-fi" data-fi-gallery>
      <p class="c-fi-intro">Picked from the file name or extension, like opencode's FileIcon.</p>
      <div class="c-fi-toggle"><span data-fi-mono="0">${fiBadge("Colour", !mono)}</span><span data-fi-mono="1">${fiBadge("Mono", mono)}</span></div>
      <div class="c-fi-section"><div class="c-fi-title">Files</div><div class="c-fi-grid">${FILE_SAMPLES.map((f) => fileCell(f, fileIcon(f, { mono }))).join("")}</div></div>
      <div class="c-fi-section"><div class="c-fi-title">Folders, collapsed and expanded</div><div class="c-fi-grid">${FOLDER_SAMPLES.map((f) => fileCell(f, fileIcon(f, { kind: "directory", mono }) + fileIcon(f, { kind: "directory", expanded: true, mono }))).join("")}</div></div>
    </div>`;
  };

  const mountFileIcon = (stage) => {
    stage.querySelectorAll("[data-fi-gallery]").forEach((root) => {
      if (root.dataset.bound) return;
      root.dataset.bound = "1";
      root.addEventListener("click", (e) => {
        const hit = e.target.closest("[data-fi-mono]");
        if (!hit || !root.contains(hit)) return;
        root.outerHTML = renderFileIcon({ mode: "gallery", mono: hit.dataset.fiMono === "1" });
        mountFileIcon(stage);
      });
    });
  };

  /* ---------- Skeleton Illustration ---------- */

  const SK_TOPICS = ["unofficial", "connectForm", "sessionsLive", "chipTimelineGlance", "chipTimelineLongRuns", "chipTimelineDetails", "timelineStandard", "timelineChips", "timelineStepRail", "timelineActivityCard"];
  const v = (t) => `var(--${t})`;
  const rr = (x, y, w, h, r, style) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" ry="${r}" style="${style}"/>`;
  const fillS = (t, a) => `fill:${v(t)}${a != null ? `;fill-opacity:${a}` : ""}`;
  const strokeS = (t, w, a) => `fill:none;stroke:${v(t)};stroke-width:${w}${a != null ? `;stroke-opacity:${a}` : ""}`;
  const circle = (cx, cy, r, style) => `<circle cx="${cx}" cy="${cy}" r="${r}" style="${style}"/>`;
  const skText = (s, cx, cy, size, color) => `<text x="${cx}" y="${cy}" text-anchor="middle" dominant-baseline="central" style="fill:${v(color)};font:700 ${size}px var(--font)">${esc(s)}</text>`;

  const bar = (x, y, w, h = 8, c = "border-weak-base", a) => rr(x, y, w, h, h / 2, fillS(c, a));
  const frame = () => rr(48, 14, 240, 210, 18, fillS("background-base")) + rr(48, 14, 240, 210, 18, strokeS("border-weak-base", 1.5));
  const card = (x, y, w, h, lit, r = 10, f = "surface-raised-base") => rr(x, y, w, h, r, fillS(f)) + rr(x, y, w, h, r, lit ? strokeS("interactive", 2) : strokeS("border-weaker-base", 1));
  const glow = (x, y, w, h, r = 8) => rr(x - 6, y - 6, w + 12, h + 12, r + 6, fillS("interactive", 0.12));
  const spot = (x, y, w, h, r = 10, f) => glow(x, y, w, h, r) + card(x, y, w, h, true, r, f);
  const avatar = (cx, cy, live, platform) => {
    if (!live) return circle(cx, cy, 12, fillS("border-weak-base"));
    const dx = cx + 9, dy = cy + 9;
    // iOS strokes a 2pt ring centered on the r=4 dot; Android stacks r=6 under r=4.
    const dot = platform === "ios"
      ? circle(dx, dy, 4, fillS("success")) + circle(dx, dy, 4, strokeS("surface-raised-base", 2))
      : circle(dx, dy, 6, fillS("surface-raised-base")) + circle(dx, dy, 4, fillS("success"));
    return circle(cx, cy, 12, fillS("surface-interactive-weak")) + dot;
  };
  const chip = (x, y, w, lit = false, diff = false) => (lit ? spot(x, y, w, 18, 6) : card(x, y, w, 18, false, 6))
    + rr(x + 6, y + 6, 6, 6, 1.5, fillS("border-weak-base"))
    + bar(x + 16, y + 6, w - 22 - (diff ? 20 : 0), 6)
    + (diff ? bar(x + w - 24, y + 6, 8, 6, "diff-add") + bar(x + w - 14, y + 6, 8, 6, "diff-delete") : "");
  const userBubble = () => rr(160, 28, 112, 22, 11, fillS("surface-interactive-weak")) + bar(172, 36, 84, 6);
  const chevron = (pts) => `<polyline points="${pts}" style="fill:none;stroke:${v("text-weaker")};stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round"/>`;
  const diffBars = (y) => bar(244, y, 8, 6, "diff-add") + bar(255, y, 8, 6, "diff-delete");

  const chipGlance = () => frame() + userBubble() + bar(64, 62, 196, 7) + bar(64, 75, 138, 7) + glow(64, 90, 206, 18, 6)
    + chip(64, 90, 62, true) + chip(132, 90, 78, true, true) + chip(216, 90, 54, true)
    + bar(64, 124, 176, 7) + bar(64, 137, 110, 7) + chip(64, 152, 70) + chip(140, 152, 58);

  const SK_DRAW = {
    unofficial: () => frame() + bar(64, 32, 90, 10) + bar(64, 48, 60, 6) + spot(64, 64, 118, 26, 13, "surface-interactive-weak")
      + skText("UNOFFICIAL", 123, 77, 11, "text-interactive-base")
      + card(64, 104, 208, 60, false) + bar(76, 116, 120, 7) + bar(76, 130, 160, 7) + bar(76, 144, 90, 7),
    connectForm: () => frame() + bar(64, 32, 110, 10) + card(64, 54, 208, 32, false)
      + `<text x="76" y="70" dominant-baseline="central" style="fill:${v("text-weak")};font:500 11px var(--font)">https://</text>`
      + bar(118, 68, 90, 8) + card(64, 94, 208, 32, false)
      + rr(73, 108.5, 10, 7, 1.5, fillS("text-weak"))
      + `<path d="M75 108.5a3 3 0 0 1 6 0" style="${strokeS("text-weak", 1.5)}"/>`
      + [100, 112, 124, 136].map((x) => circle(x, 110, 3.5, fillS("text-weaker"))).join("")
      + glow(64, 138, 208, 34) + rr(64, 138, 208, 34, 8, fillS("button-primary-base")) + skText("Connect", 168, 155, 11, "icon-invert-base"),
    sessionsLive: (pf) => frame() + bar(64, 30, 70, 10) + circle(70, 50, 3, fillS("success")) + bar(78, 47, 22, 5)
      + avatar(80, 80, false, pf) + bar(100, 72, 100, 7) + bar(100, 85, 70, 5)
      + spot(60, 104, 216, 40) + avatar(80, 124, true, pf) + bar(100, 116, 110, 7, "text-base") + bar(100, 129, 80, 5)
      + rr(222, 115, 44, 18, 9, fillS("surface-interactive-weak")) + skText("Running", 244, 124, 10, "text-interactive-base")
      + avatar(80, 168, false, pf) + bar(100, 160, 90, 7) + bar(100, 173, 60, 5),
    chipTimelineGlance: chipGlance,
    chipTimelineLongRuns: () => frame()
      + [62, 48, 70, 54, 66, 44, 58, 50].map((w, i) => { const y = 30 + i * 18; return rr(64, y, 8, 8, 2, fillS("border-weaker-base")) + bar(78, y + 1, w, 6, "border-weaker-base"); }).join("")
      + `<path d="M156 92H172M166 86L172 92L166 98" style="fill:none;stroke:${v("text-weaker")};stroke-width:2;stroke-linecap:round;stroke-linejoin:round"/>`
      + bar(184, 62, 86, 7) + bar(184, 75, 60, 7) + glow(184, 90, 86, 40, 6)
      + chip(184, 90, 46, true) + chip(234, 90, 36, true) + chip(184, 112, 64, true, true),
    chipTimelineDetails: () => frame() + bar(64, 30, 186, 7) + bar(64, 43, 124, 7) + chip(64, 58, 62) + chip(132, 58, 84, true, true)
      + card(64, 92, 208, 86, false) + bar(76, 102, 96, 7)
      + [[150], [128, "diff-delete"], [160, "diff-add"], [112, "diff-add"], [140]].map(([w, c], i) => {
        const y = 118 + i * 11;
        return (c ? rr(76, y, 3, 6, 1, fillS(c)) : "") + (c ? bar(84, y, w, 6, c, 0.45) : bar(84, y, w, 6));
      }).join(""),
    timelineStandard: () => frame() + userBubble() + bar(64, 62, 196, 7) + bar(64, 75, 120, 7) + spot(64, 90, 208, 76)
      + bar(76, 99, 54, 6, "text-weak") + chevron("256,99 260,103 264,99")
      + `<rect x="64" y="113" width="208" height="1" style="${fillS("border-weaker-base")}"/>`
      + [[121, 92, false], [137, 70, true], [153, 104, false]].map(([y, w, d]) => rr(76, y, 8, 8, 2, fillS("border-weak-base")) + bar(90, y + 1, 30, 6, "text-weak") + bar(126, y + 1, w, 6) + (d ? diffBars(y + 1) : "")).join("")
      + bar(64, 180, 150, 7),
    timelineChips: chipGlance,
    timelineStepRail: () => {
      const rail = 70.75;
      return frame() + userBubble() + bar(64, 62, 170, 7)
        + `<rect x="70" y="82" width="1.5" height="104" style="${fillS("border-weak-base")}"/>`
        + [[88, 40, 70, false], [106, 52, 56, false], [124, 34, 84, true]].map(([y, n, d, diff]) => circle(rail, y + 3, 4, fillS("success")) + bar(82, y, n, 6, "text-weak") + bar(88 + n, y, d, 6) + (diff ? diffBars(y) : "")).join("")
        + spot(80, 140, 192, 18, 6) + circle(rail, 149, 4, fillS("interactive")) + circle(rail, 149, 7, strokeS("interactive", 2, 0.35))
        + bar(88, 146, 34, 6, "text-base") + bar(128, 146, 80, 6) + bar(240, 146, 24, 6, "text-weaker")
        + circle(rail, 175, 4, fillS("text-weaker")) + bar(82, 172, 60, 6, "border-weaker-base");
    },
    timelineActivityCard: (pf) => {
      const id = `c-sk-clip-${Math.random().toString(36).slice(2, 9)}`;
      const divider = `<rect x="64" y="146" width="208" height="1" style="${fillS("border-weak-base")}"/>`;
      const border = rr(64, 90, 208, 80, 12, strokeS("interactive", 2));
      return frame() + userBubble() + bar(64, 62, 196, 7) + bar(64, 75, 120, 7) + glow(64, 90, 208, 80, 12)
        + `<clipPath id="${id}"><rect x="64" y="90" width="208" height="80" rx="12" ry="12"/></clipPath>`
        + `<g clip-path="url(#${id})"><rect x="64" y="90" width="208" height="80" style="${fillS("surface-raised-base")}"/><rect x="64" y="146" width="208" height="24" style="${fillS("border-weaker-base")}"/></g>`
        // iOS draws the divider under the lit border; Android draws it over the border.
        + (pf === "ios" ? divider + border : border)
        + bar(76, 102, 52, 8, "text-base") + bar(236, 103, 26, 6, "text-weaker") + bar(76, 120, 70, 6, "text-weak") + bar(152, 120, 46, 6)
        + (pf === "ios" ? "" : divider)
        + circle(80, 158, 4, fillS("interactive")) + bar(90, 155, 30, 6, "text-base") + bar(126, 155, 84, 6) + chevron("256,154 260,158 256,162");
    },
  };

  const skeleton = (topic, platform) => `<div class="c-sk-box"><svg class="c-sk-svg" viewBox="0 0 336 186" preserveAspectRatio="xMidYMid meet" aria-hidden="true">${SK_DRAW[topic](platform)}</svg></div>`;
  const skLabel = (topic, platform) => (platform === "android" ? topic[0].toUpperCase() + topic.slice(1) : topic);
  const renderSkeleton = (p, ctx) => {
    const list = p.topic === "all" ? SK_TOPICS : [p.topic];
    return `<div class="c-sk">${list.map((t) => `<div class="c-sk-item"><span class="c-sk-label">${skLabel(t, ctx.platform)}</span>${skeleton(t, ctx.platform)}</div>`).join("")}</div>`;
  };

  /* ---------- Registry ---------- */

  Kit.define([
    {
      id: "colors", layout: "scroll",
      params: [sel("group", ["all", ...COLOR_GROUPS.map(([, k]) => k)])],
      tokens: [["Source", "shared/theme-presets + ThemeResolver"], ["row.padding", 12], ["row.cornerRadius", 12], ["swatch.height", 44], ["swatch.cornerRadius", 8], ["swatch.border", "1 border-weaker-base"], ["iOS mix", "rounds each channel"], ["Android mix", "truncates each channel"]],
      spec: "## Intent\nShows every resolved color token for the active theme, in light and dark, grouped by role.\n\n## Public API\nTokens are resolved from a preset's palette seeds and overrides by `ThemeResolver` on each platform. A code color preset can overlay `syntax-*` tokens.\n\n## Behavior\nThe native galleries list 42 theme tokens plus the two optional code-surface tokens. This page shows all 62 resolved tokens and marks those the native galleries leave out. iOS frames show iOS-rounded values. The code preset shown is Match theme, so `syntax-background` and `syntax-foreground` are unset: iOS lists them as not set and Android hides them.",
      source: { ios: "ComponentGallery/Foundation/ColorGalleryView.swift", android: "ui/componentgallery/foundation/ColorGalleryScreen.kt" },
      divergences: [
        { severity: "medium", title: "iOS rounds mixed colors and Android truncates, so 100 of 350 derived values differ by one RGB step" },
        { severity: "low", title: "iOS lists unset syntax-background and syntax-foreground; Android hides them" },
        { severity: "low", title: "Both native galleries omit 20 resolved tokens" },
        { severity: "low", title: "Android gallery title omits the active code preset" },
      ],
      render: renderColors,
    },
    {
      id: "icons", layout: "scroll",
      params: [text("filter", "", "filter")],
      tokens: [["Catalog", "icons/manifest.yaml"], ["Library", "Lucide"], ["Count", Object.keys(window.DS.icons).length], ["glyph", 24], ["strokeWidth", 1.5], ["cell.minWidth", 80], ["cell.minHeight", 72], ["grid.columnGap", 12], ["grid.rowGap", 16], ["tint", "icon-strong-base"]],
      spec: "## Intent\nThe shared Lucide icon catalog, generated for both platforms from `icons/manifest.yaml`.\n\n## Public API\niOS: `OpenCodeIconImage(icon:size:)`. Android: `OpenCodeIcons.{Name}` with `Icon`.\n\n## Behavior\nIcons are outline strokes at 1.5 in a 24 viewBox and tint with `icon-strong-base` in product chrome. Size iOS icons with `size:` or a frame, never `.font`. The filter is a prototype aid.",
      source: { ios: "Icons/OpenCodeIcon.swift", android: "ui/icons/OpenCodeIcons.kt" },
      divergences: [{ severity: "medium", title: "Android gallery lists 40 of the 68 catalog icons" }],
      render: renderIcons,
    },
    {
      id: "typography", layout: "scroll",
      params: [text("sample", "", "sample")],
      tokens: [["Primary intent", "IBM Plex Sans (deferred)"], ["Today", "platform fallback fonts"], ["card.padding", 12], ["card.cornerRadius", 12], ["code.padding", 10], ["code.cornerRadius", 8], ["Text size steps", "0.78, 0.85, 0.92, 1, 1.12, 1.26"]],
      spec: "## Intent\nEight semantic type roles shared by both apps, drawn with platform fonts until IBM Plex ships.\n\n## Public API\niOS: `OpenCodeTypography` and `.openCodeFont(_:)`. Android: Material `Typography` plus `CodeTextStyle`.\n\n## Behavior\nAndroid roles set fixed line heights, and display and title add -0.16sp tracking. iOS roles use the system line height. Appearance text sizes multiply the phone's text size inside a text region.",
      source: { ios: "Theme/OpenCodeTypography.swift", android: "theme/Type.kt" },
      divergences: [
        { severity: "high", title: "Outside text regions Android follows the phone font scale and iOS stays fixed" },
        { severity: "medium", title: "iOS caps Dynamic Type at accessibility1; Android has no cap" },
        { severity: "medium", title: "Android sets line heights and tracking that iOS roles do not" },
        { severity: "low", title: "Android labelSmall (12sp) is used without a type role" },
      ],
      render: renderTypography,
    },
    {
      id: "file-icon", layout: "scroll",
      params: [sel("mode", ["gallery", "lookup"]), text("path", "src/app.test.ts", "path"), sel("kind", ["file", "directory"]), bool("expanded"), bool("mono"), sel("size", ["16", "18", "24"], "24")],
      tokens: [["Default size (Android)", 24], ["Default size (iOS)", "fills proposed size"], ["Tree row", 18], ["Composer suggestion", 16], ["cell.minWidth", 96], ["grid.columnGap", 12], ["grid.rowGap", 16], ["mono tint", "icon-strong-base"]],
      spec: "## Intent\nA file or folder icon picked from a path, ported from opencode's `FileIcon`.\n\n## Public API\n`path`, `kind` (file or directory), `expanded`, `mono`, `size`.\n\n## Behavior\nThe basename is lowercased. Files try the exact file name, then dotted suffixes longest first, then `Document`. Folders try `name`, `.name`, `_name`, and `__name__`, then the default folder. Expanded folders use the open variant. Mono draws the icon's shape in the current foreground color.",
      source: { ios: "Icons/OpenCodeFileIcon.swift", android: "ui/icons/OpenCodeFileIcon.kt" },
      divergences: [{ severity: "low", title: "Without a size, iOS fills the proposed space and Android draws 24dp" }],
      render: renderFileIcon, mount: mountFileIcon,
    },
    {
      id: "skeleton-illustration", layout: "scroll",
      params: [sel("topic", ["all", ...SK_TOPICS])],
      tokens: [["design", "336 × 186"], ["gallery.height", 176], ["gallery.cornerRadius", 20], ["gallery.border", "1 border-weaker-base"], ["frame", "48,14 240×210 r18"], ["card.cornerRadius", 10], ["glow", "interactive 12%, outset 6"], ["label", "11 bold, fixed size"]],
      spec: "## Intent\nDecorative wireframes that explain a feature or Timeline Style in onboarding and pickers.\n\n## Public API\n`topic`, one of ten values.\n\n## Behavior\nEach drawing uses a 336 × 186 design space, scaled to fit and centered. The phone frame runs past the bottom edge and is clipped. Labels stay at a fixed design size and ignore text scaling. The illustration is hidden from accessibility. `timelineChips` reuses the `chipTimelineGlance` drawing.",
      source: { ios: "Components/Illustration/OpenCodeSkeletonIllustration.swift", android: "ui/components/illustration/OpenCodeSkeletonIllustration.kt" },
      divergences: [
        { severity: "low", title: "The live avatar's status dot is smaller on iOS" },
        { severity: "low", title: "Activity card divider crosses the lit border on Android only" },
        { severity: "low", title: "Gallery label gap is 8 on iOS and 20 on Android" },
      ],
      render: renderSkeleton,
    },
  ]);
})();
