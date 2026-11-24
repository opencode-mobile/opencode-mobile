// Component group: lists. Compose other groups with Kit.use(id, props).
// Values come from the native components. Where the platforms differ, each renders its own value.
(() => {
  const { esc, I, sel, text, bool, TONES } = Kit;


  /* ---------- Shared helpers ---------- */
  const f = (size, line, weight = 400, extra = "") => `font-size:calc(${size}px * var(--ts,1));line-height:${typeof line === "number" ? `calc(${line}px * var(--ts,1))` : line};font-weight:${weight};${extra}`;
  const mix = (token, pct) => `color-mix(in srgb, var(--${token}) ${pct}%, transparent)`;
  const isA = (c) => c.platform === "android";
  // Android labelMedium is not set in Type.kt, so Compose uses the Material 3 default: 12/16 medium, +0.5 tracking.
  const type = (c) => isA(c)
    ? { body: (w = 400) => f(16, "var(--body-lh)", w), secondary: f(14, 20), caption: f(13, 18), labelS: (w = 400) => f(12, 16, w), labelM: f(12, 16, 500, "letter-spacing:.5px;"), heading: f(16, 22, 500), title: f(20, 28, 500) }
    : { body: (w = 400) => f(16, "var(--body-lh)", w), secondary: f(14, 20), caption: f(13, 18), labelS: (w = 400) => f(13, 18, w), labelM: f(13, 18), heading: f(16, 22, 500), title: f(20, 26, 500) };

  // Light fill, light tint, dark fill, dark tint. All eight native accents (Kit.ACCENTS has six).
  const ACCENTS = { violet: ["#4c3d5c", "#e2d5f5", "#3a2f47", "#d8c8ee"], teal: ["#2d4a42", "#b8f5e0", "#243832", "#a8edd8"], amber: ["#5c3d28", "#ffb86c", "#4a3224", "#ffaa55"], blue: ["#2a3a55", "#a8d4ff", "#1e2d42", "#9ec8ff"], rose: ["#5c2d3d", "#ffb8d4", "#4a2533", "#ffa8cc"], red: ["#5c2d2d", "#ffc8c8", "#4a2424", "#ffb8b8"], green: ["#2d4a3d", "#b8f5d8", "#243832", "#a8edc8"], indigo: ["#2d3d5c", "#b8c8ff", "#24304a", "#a8b8ff"] };
  const accent = (name, c) => { const a = ACCENTS[name] ?? ACCENTS.violet; return c.dark ? [a[2], a[3]] : [a[0], a[1]]; };
  const glyph = (ch) => { const u = String(ch ?? "").charAt(0).toLocaleUpperCase(); return /[\p{L}\p{N}]/u.test(u) ? u : "?"; };
  const TONE_KEYS = ["none", "interactive", "success", "warning", "error", "info", "stale"];
  const toneFg = (t, c, on = true) => !on ? "var(--text-weaker)" : t === "info" ? "var(--text-base)" : t === "stale" ? (isA(c) ? "var(--text-weak)" : "var(--text-weaker)") : `var(${TONES[t] ?? "--interactive"})`;
  const toneBg = (t, c, on = true) => !on ? "var(--surface-raised-base)" : t === "info" ? mix("text-strong", 10) : t === "stale" ? mix("text-strong", isA(c) ? 8 : 6) : `color-mix(in srgb, var(${TONES[t] ?? "--interactive"}) 12%, transparent)`;

  // Tag is owned by another group; the small size is drawn here so List Item does not depend on load order.
  const smallTag = (label, tone, c) => `<span class="c-li-tag" style="${isA(c) ? f(12, 16, 600) : f(13, 18, 600)}background:${tone === "accent" ? mix("interactive", 12) : "var(--surface-raised-base)"};color:var(--${tone === "accent" ? "text-interactive-base" : "text-weak"})">${esc(label)}</span>`;

  // Same lookup as OpenCodeFileIcon: names, dotted suffixes longest first, then the default.
  const fileIcon = (path, kind, expanded, size, dim) => {
    const m = window.DS.fileIconMaps, base = String(path).split("/").filter(Boolean).pop()?.toLowerCase() ?? "";
    let name;
    if (kind === "directory") {
      const hit = [base, `.${base}`, `_${base}`, `__${base}__`].map((x) => m.folderNames[x]).find(Boolean);
      name = hit ? (expanded && !hit.endsWith("Open") ? `${hit}Open` : hit) : expanded ? m.defaults.folderOpen : m.defaults.folder;
    } else {
      name = m.fileNames[base];
      if (!name) { const parts = base.split("."); for (let i = 0; i < parts.length && !name; i++) name = m.fileExtensions[parts.slice(i).join(".")]; }
      name ??= m.defaults.file;
    }
    const file = m.icons[name] ?? m.icons[m.defaults.file];
    return `<img class="c-tr-img" alt="" data-file="${esc(file)}" src="assets/file-types/${esc(file)}" style="width:${size}px;height:${size}px${dim ? `;opacity:${dim}` : ""}">`;
  };
  // The native sync script bakes currentColor to #8F8F8F; an <img> would otherwise draw it black.
  const iconText = {};
  const bakeIcon = (img) => {
    const file = img.dataset.file;
    iconText[file] ??= fetch(`assets/file-types/${file}`).then((r) => (r.ok ? r.text() : "")).catch(() => "");
    iconText[file].then((svg) => { if (svg && img.dataset.file === file) img.src = `data:image/svg+xml,${encodeURIComponent(svg.replace(/currentColor/gi, "#8F8F8F"))}`; });
  };
  const setFolderIcon = (img, open) => {
    const next = open ? img.dataset.file.replace(/(-open)?\.svg$/, "-open.svg") : img.dataset.file.replace(/-open\.svg$/, ".svg");
    if (Object.values(window.DS.fileIconMaps.icons).includes(next)) { img.dataset.file = next; bakeIcon(img); }
  };

  /* ---------- Skeleton previews: the four timeline topics at 336 x 186 ---------- */
  const skel = (() => {
    const v = (n) => `var(--${n})`;
    const bar = (x, y, w, h = 8, col = "border-weak-base") => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${h / 2}" fill="${v(col)}"/>`;
    const box = (x, y, w, h, r, col) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${v(col)}"/>`;
    const dot = (cx, cy, r, col) => `<circle cx="${cx}" cy="${cy}" r="${r}" fill="${v(col)}"/>`;
    const glow = (x, y, w, h, r = 8) => `<rect x="${x - 6}" y="${y - 6}" width="${w + 12}" height="${h + 12}" rx="${r + 6}" fill="${mix("interactive", 12)}"/>`;
    const card = (x, y, w, h, lit, r = 10) => `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${r}" fill="${v("surface-raised-base")}" stroke="${v(lit ? "interactive" : "border-weaker-base")}" stroke-width="${lit ? 2 : 1}"/>`;
    const spot = (x, y, w, h, r = 10) => glow(x, y, w, h, r) + card(x, y, w, h, true, r);
    const frame = `<rect x="48" y="14" width="240" height="210" rx="18" fill="${v("background-base")}" stroke="${v("border-weak-base")}" stroke-width="1.5"/>`;
    const bubble = box(160, 28, 112, 22, 11, "surface-interactive-weak") + bar(172, 36, 84, 6);
    const chev = (pts) => `<polyline points="${pts}" fill="none" stroke="${v("text-weaker")}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>`;
    const diff = (x, y) => bar(x, y, 8, 6, "diff-add") + bar(x + 11, y, 8, 6, "diff-delete");
    const chip = (x, y, w, lit, hasDiff) => (lit ? spot(x, y, w, 18, 6) : card(x, y, w, 18, false, 6)) + box(x + 6, y + 6, 6, 6, 1.5, "border-weak-base") + bar(x + 16, y + 6, w - 22 - (hasDiff ? 20 : 0), 6) + (hasDiff ? bar(x + w - 24, y + 6, 8, 6, "diff-add") + bar(x + w - 14, y + 6, 8, 6, "diff-delete") : "");
    const topics = {
      standard: () => frame + bubble + bar(64, 62, 196, 7) + bar(64, 75, 120, 7) + spot(64, 90, 208, 76) + bar(76, 99, 54, 6, "text-weak") + chev("256,99 260,103 264,99") + `<rect x="64" y="113" width="208" height="1" fill="${v("border-weaker-base")}"/>` + [[121, 92, 0], [137, 70, 1], [153, 104, 0]].map(([y, w, d]) => box(76, y, 8, 8, 2, "border-weak-base") + bar(90, y + 1, 30, 6, "text-weak") + bar(126, y + 1, w, 6) + (d ? diff(244, y + 1) : "")).join("") + bar(64, 180, 150, 7),
      chips: () => frame + bubble + bar(64, 62, 196, 7) + bar(64, 75, 138, 7) + glow(64, 90, 206, 18, 6) + chip(64, 90, 62, 1) + chip(132, 90, 78, 1, 1) + chip(216, 90, 54, 1) + bar(64, 124, 176, 7) + bar(64, 137, 110, 7) + chip(64, 152, 70) + chip(140, 152, 58),
      rail: () => frame + bubble + bar(64, 62, 170, 7) + `<rect x="70" y="82" width="1.5" height="104" fill="${v("border-weak-base")}"/>` + [[88, 40, 70, 0], [106, 52, 56, 0], [124, 34, 84, 1]].map(([y, n, d, x]) => dot(70.75, y + 3, 4, "success") + bar(82, y, n, 6, "text-weak") + bar(88 + n, y, d, 6) + (x ? diff(244, y) : "")).join("") + spot(80, 140, 192, 18, 6) + dot(70.75, 149, 4, "interactive") + `<circle cx="70.75" cy="149" r="7" fill="none" stroke="${mix("interactive", 35)}" stroke-width="2"/>` + bar(88, 146, 34, 6, "text-base") + bar(128, 146, 80, 6) + bar(240, 146, 24, 6, "text-weaker") + dot(70.75, 175, 4, "text-weaker") + bar(82, 172, 60, 6, "border-weaker-base"),
      activity: () => { const id = `c-sk-${++skelId}`; return frame + bubble + bar(64, 62, 196, 7) + bar(64, 75, 120, 7) + glow(64, 90, 208, 80, 12) + `<clipPath id="${id}"><rect x="64" y="90" width="208" height="80" rx="12"/></clipPath><rect x="64" y="90" width="208" height="80" rx="12" fill="${v("surface-raised-base")}"/><rect x="64" y="146" width="208" height="24" fill="${v("border-weaker-base")}" clip-path="url(#${id})"/><rect x="64" y="146" width="208" height="1" fill="${v("border-weak-base")}"/><rect x="64" y="90" width="208" height="80" rx="12" fill="none" stroke="${v("interactive")}" stroke-width="2"/>` + bar(76, 102, 52, 8, "text-base") + bar(236, 103, 26, 6, "text-weaker") + bar(76, 120, 70, 6, "text-weak") + bar(152, 120, 46, 6) + dot(80, 158, 4, "interactive") + bar(90, 155, 30, 6, "text-base") + bar(126, 155, 84, 6) + chev("256,154 260,158 256,162"); },
    };
    return (topic) => `<svg viewBox="0 0 336 186" preserveAspectRatio="xMidYMid meet" aria-hidden="true">${(topics[topic] ?? topics.standard)()}</svg>`;
  })();
  let skelId = 0;

  /* ---------- List Item ---------- */
  const SEGMENTS = {
    diff: [["Modified · ", "secondary"], ["+51", "addition"], [" ", "secondary"], ["−7", "deletion"]],
    branch: [["opencode-mobile", "secondary", "folder"]],
  };
  const DETAIL = { branch: [["feature/723-iphone-duo", "tertiary", "call_split"], [" · opencode-mobile-723", "tertiary"]] };
  const segColor = (tone, on) => !on ? "text-weaker" : ({ secondary: "text-base", tertiary: "text-weak", addition: "diff-add", deletion: "diff-delete", removed: "text-weaker" })[tone];
  const segments = (list, on, style) => `<div class="c-li-line" style="${style}">${list.map(([t, tone, icon]) => `<span style="color:var(--${segColor(tone, on)})${tone === "removed" ? ";text-decoration:line-through" : ""}">${icon ? `<span class="c-li-segic">${I(icon, 12)}</span>` : ""}${esc(t)}</span>`).join("")}</div>`;

  const highlighted = (title, term, on, c) => {
    const i = term ? title.toLowerCase().indexOf(term.toLowerCase()) : -1;
    if (i < 0) return esc(title);
    const android = isA(c), bg = mix("interactive", android ? 24 : on ? 22 : 10), col = android ? "inherit" : on ? "var(--text-strong)" : "var(--text-weaker)";
    return `${esc(title.slice(0, i))}<mark style="background:${bg};color:${col}">${esc(title.slice(i, i + term.length))}</mark>${esc(title.slice(i + term.length))}`;
  };

  const decorator = (p, c, on) => {
    const dim = isA(c) && !on ? "opacity:.7;" : "";
    const kind = p.variant === "compact" && p.decorator === "letter" ? "compactLetter" : p.decorator;
    if (kind === "none") return "";
    if (kind === "icon") return `<span class="c-li-deco" style="${dim}color:var(--${isA(c) && !on ? "icon-base" : "icon-strong-base"})">${I(p.icon || "folder", 24)}</span>`;
    if (kind === "iconCircle") return `<span class="c-li-circle" style="${dim}">${I("widgets", 16)}</span>`;
    const [fill, tint] = accent(p.accent, c), ch = glyph(p.letter || p.title);
    if (kind === "compactLetter") {
      const dot = p.dot !== "none" && TONE_KEYS.includes(p.dot) ? `<i class="c-li-cdot"><b class="${p.pulsing ? "pulse" : ""}" style="background:${toneFg(p.dot, c, on)}"></b></i>` : "";
      return `<span class="c-li-badge" style="${dim}background:${fill};color:${tint};font-size:calc(13px * var(--ts,1))">${esc(ch)}${dot}</span>`;
    }
    const hasDot = p.dot !== "none" && TONE_KEYS.includes(p.dot), wt = kind === "letterWorktree";
    let mark = "";
    if (hasDot && wt) mark = `<i class="c-li-wt" style="background:${toneFg(p.dot, c, on)};color:var(--background-base)">${I("call_split", 10)}</i>`;
    else if (wt) mark = `<i class="c-li-wt" style="background:var(--surface-raised-base);color:var(--icon-strong-base)">${I("call_split", 10)}</i>`;
    else if (hasDot) mark = `<i class="c-li-dot" style="background:${toneFg(p.dot, c, on)}"></i>`;
    return `<span class="c-li-badge" style="${dim}background:${fill};color:${tint};font-size:calc(14px * var(--ts,1))">${esc(ch)}${mark}</span>`;
  };

  const toggleEl = (checked, on) => `<span class="c-li-sw${checked ? " on" : ""}${on ? "" : " off"}" role="switch" aria-checked="${!!checked}"><i></i></span>`;
  const accessoryEl = (p, on) => {
    const kind = p.accessory !== "none" ? p.accessory : p.chevron ? "disclosure" : "none";
    if (kind === "disclosure") return `<span class="c-li-acc" style="color:var(--${on ? "text-base" : "text-weaker"})">${I("chevron_right", 18)}</span>`;
    return kind === "toggle" ? toggleEl(p.toggleOn, on) : "";
  };

  const pathLine = (p, on, c) => {
    const size = isA(c) ? 13 : 13.5, base = f(size, 18), primary = `color:${on ? mix("text-strong", 80) : "var(--text-weaker)"};font-weight:500;`;
    if (!p.pathSecondary) return `<div class="c-li-line" style="${base}${primary}">${esc(p.pathPrimary)}</div>`;
    const second = `color:var(--${on && !p.pathRemoved ? "text-base" : "text-weaker"});${p.pathRemoved ? "text-decoration:line-through;" : ""}`;
    return `<div class="c-li-path" style="${base}" data-primary="${esc(p.pathPrimary)}" data-secondary="${esc(p.pathSecondary)}"><span class="a" style="${primary}">${esc(p.pathPrimary)}</span><span class="s" style="color:var(--${on ? "text-weak" : "text-weaker"})">/</span><span class="b" style="${second}">${esc(p.pathSecondary)}</span></div>`;
  };

  const listItem = (p, c) => {
    const on = !p.disabled, t = type(c), android = isA(c), compact = p.variant === "compact", wrapped = p.textFlow === "wrapped" && !compact;
    const hasStatus = p.status && p.status !== "none", titleColor = `var(--${on ? "text-strong" : "text-weaker"})`;
    const cls = ["c-li", p.style === "card" ? "card" : "plain", p.selected && "sel", on && "press", p.pressed && on && "is-pressed", compact && "compact", wrapped && "wrapped", !android && !on && "dim"].filter(Boolean).join(" ");
    let body;
    if (compact) {
      const second = p.pathPrimary ? pathLine(p, on, c)
        : p.subtitleKind !== "plain" && SEGMENTS[p.subtitleKind] && !android ? segments(SEGMENTS[p.subtitleKind], on, f(13.5, 18))
        : p.subtitle && p.subtitleKind !== "off" ? `<div class="c-li-line" style="${f(android ? 13 : 13.5, 18)}color:var(--${on ? "text-base" : "text-weaker"})">${esc(p.subtitle)}</div>` : `<div class="c-li-line"></div>`;
      body = `<span class="c-li-tx" style="gap:calc(5px * var(--ts,1))">
        <span class="c-li-l"><span class="c-li-ti" style="${f(16, 22, p.emphasized ? 600 : 500)}color:${titleColor}">${highlighted(p.title, p.highlight, on, c)}</span>${p.caption ? `<span class="c-li-cap" style="${f(13, 18, 400, "font-variant-numeric:tabular-nums;")}color:var(--${on ? "text-weak" : "text-weaker"})">${esc(p.caption)}</span>` : ""}</span>
        <span class="c-li-l">${second}${hasStatus ? `<span class="c-li-cap" style="${f(android ? 13 : 13.5, 18, 600)}color:${toneFg(p.status, c, on)}">${esc(p.statusText)}</span>` : ""}</span>
      </span>${android ? "" : accessoryEl(p, on)}`;
    } else {
      const lineStyle = android ? t.labelM : t.caption, seg = SEGMENTS[p.subtitleKind], det = DETAIL[p.subtitleKind];
      const sub = seg ? segments(seg, on, lineStyle) : p.subtitle && p.subtitleKind !== "off" ? `<div class="c-li-su" style="${lineStyle}color:var(--${on ? "text-base" : "text-weaker"})">${esc(p.subtitle)}</div>` : "";
      const meta = p.caption || hasStatus ? `<span class="c-li-meta">${p.caption ? `<span style="${lineStyle}padding-right:2px;color:var(--${on ? "text-base" : "text-weaker"})">${esc(p.caption)}</span>` : ""}${hasStatus ? `<span class="c-li-pill" style="${t.labelS(600)}padding:${android ? "3px 8px" : "calc(4px * var(--ts,1)) calc(8px * var(--ts,1))"};color:${toneFg(p.status, c, on)};background:${toneBg(p.status, c, on)}">${esc(p.statusText)}</span>` : ""}</span>` : "";
      body = `<span class="c-li-tx" style="gap:calc(2px * var(--ts,1))">
        <span class="c-li-tl"><span class="c-li-ti" style="${t.body(600)}color:${titleColor}">${highlighted(p.title, p.highlight, on, c)}</span>${p.titleTag ? smallTag(p.titleTag, "neutral", c) : ""}${p.isNew ? smallTag("New", "accent", c) : ""}</span>
        ${sub}${det ? segments(det, on, lineStyle) : ""}
      </span>${meta}${accessoryEl(p, on)}`;
    }
    const row = `<div class="${cls}" role="button" tabindex="${on ? 0 : -1}"${on ? "" : ' aria-disabled="true"'}${p.selected ? ' aria-selected="true"' : ""}><i class="c-li-hl"></i>${decorator(p, c, on)}${body}</div>`;
    if (!p.footerSlot) return row;
    return `<div class="c-li-foot ${p.style === "card" ? "card" : ""}">${row}<div class="c-li-div ${android || p.style !== "card" ? "ins" : ""}"></div><div class="c-li-fslot">${chart({ type: "spark", values: LAT.ios, tone: "success", gaps: true }, 40)}</div></div>`;
  };

  /* ---------- Charts ---------- */
  const LAT = { ios: [24, 22, 28, 31, 26, 25, null, null, 40, 33, 29, 27, 25, 23, 26, 24], android: Array.from({ length: 60 }, (_, i) => (i >= 38 && i <= 40 ? null : 24 + 6 * Math.sin(i / 4) + (i % 7))) };
  const BAND = {
    ios: Array.from({ length: 48 }, (_, i) => { if (i >= 18 && i < 22) return [null, null, "unobserved"]; if (i === 30) return [null, null, "error"]; const b = 22 + (i % 7) * 2; return [b, b + 16, i === 31 ? "warning" : "none"]; }),
    android: Array.from({ length: 48 }, (_, i) => { if (i <= 5) return [null, null, "unobserved"]; if (i === 20 || i === 21) return [null, null, "error"]; if (i === 22) return [60, 140, "warning"]; return [22 + (i % 5) * 3, 48 + (i % 9) * 6, "none"]; }),
  };
  const STRIP = {
    ios: Array.from({ length: 96 }, (_, i) => { const m = i % 23; return m === 0 ? "error" : m <= 2 ? "warning" : m >= 10 && m < 14 ? "unobserved" : "success"; }),
    android: Array.from({ length: 96 }, (_, i) => (i < 10 ? "unobserved" : i >= 40 && i <= 42 ? "error" : (i >= 43 && i <= 45) || i === 70 ? "warning" : "success")),
  };
  const BARS = { ios: Array.from({ length: 96 }, (_, i) => (i % 17 === 0 ? (i % 3) + 1 : 0)), android: Array.from({ length: 48 }, (_, i) => (i % 11 === 3 ? (i % 3) + 1 : 0)) };

  const niceCeil = (v) => { if (!(v > 0)) return 0; const m = 10 ** Math.floor(Math.log10(v)); for (const s of [1, 2, 2.5, 5, 10]) if (s * m >= v - 1e-9) return s * m; return 10 * m; };
  const bandMax = (pts) => Math.max(40, niceCeil(Math.max(0, ...pts.map(([m, u]) => u ?? m ?? 0)) * 1.08));
  const chart = (cfg, height) => {
    const label = cfg.type === "band" ? bandMax(cfg.points) : cfg.type === "bar" ? Math.max(0, ...cfg.values) : null;
    const axis = (v) => `<span class="c-ch-ax" data-ax>${esc(cfg.unit ? `${Math.trunc(v)} ${cfg.unit}` : Math.round(v))}</span>`;
    const over = cfg.type === "band" ? `<span class="c-ch-lab top">${axis(label)}</span><span class="c-ch-lab mid">${axis(cfg.android ? Math.ceil(label / 2) : label / 2)}</span>` : cfg.type === "bar" && label > 0 ? `<span class="c-ch-lab top">${axis(label)}</span>` : "";
    return `<div class="c-ch" style="height:${height}px" data-chart="${esc(JSON.stringify(cfg))}"><svg aria-hidden="true"></svg>${over}</div>`;
  };

  let gradId = 0;
  const hatch = (x, w, h, step, stroke, tint) => {
    const id = `c-ch-h${++gradId}`; let lines = "";
    for (let s = x - h; s < x + w; s += step) lines += `<line x1="${s}" y1="${h}" x2="${s + h}" y2="0" stroke="${stroke}" stroke-width="1"/>`;
    return `<clipPath id="${id}"><rect x="${x}" y="0" width="${w}" height="${h}"/></clipPath>${tint ? `<rect x="${x}" y="0" width="${w}" height="${h}" fill="${tint}"/>` : ""}<g clip-path="url(#${id})">${lines}</g>`;
  };
  const runsOf = (arr, has) => { const out = []; let run = []; arr.forEach((v, i) => { if (has(v)) run.push(i); else if (run.length) { out.push(run); run = []; } }); if (run.length) out.push(run); return out; };
  const pts = (list) => list.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join(" ");

  const drawSpark = (cfg, w, h, android) => {
    const vals = cfg.values, n = vals.length, col = `var(--${cfg.tone})`, present = vals.filter((v) => v != null);
    if (!n) return "";
    const x = (i) => (n > 1 ? (i * w) / (n - 1) : w / 2);
    let out = "";
    if (cfg.gaps) {
      const cw = android ? w / n : n > 1 ? w / (n - 1) : w;
      vals.forEach((v, i) => { if (v != null) return; const l = Math.max(0, x(i) - cw / 2), r = Math.min(w, x(i) + cw / 2); out += `<rect x="${l}" y="0" width="${Math.max(r - l, 1)}" height="${h}" fill="${mix("error", 14)}"/>`; });
    }
    if (!present.length) return out;
    const low = Math.min(...present), high = Math.max(...present), spread = high - low;
    let y;
    if (android) { const inset = 3.5, pad = Math.max(spread * 0.15, 1), mn = Math.max(low - pad, 0), mx = high + pad; y = (v) => inset + (1 - (v - mn) / (mx - mn)) * (h - inset * 2); }
    else { const inset = 3, lo = spread > 0 ? Math.max(0, low - spread * 0.25) : 0, up = spread > 0 ? high + spread * 0.15 : Math.max(high * 1.25, 1); y = (v) => inset + Math.max(1, h - inset * 2) * (1 - (v - lo) / Math.max(up - lo, 1e-9)); }
    const id = `c-ch-g${++gradId}`;
    out += `<defs><linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="${h}"><stop offset="0" style="stop-color:${col};stop-opacity:${android ? 0.22 : 0.28}"/><stop offset="1" style="stop-color:${col};stop-opacity:0"/></linearGradient></defs>`;
    for (const run of runsOf(vals, (v) => v != null)) {
      if (run.length === 1) { out += `<circle cx="${x(run[0])}" cy="${y(vals[run[0]])}" r="1.5" fill="${col}"/>`; continue; }
      const line = run.map((i) => [x(i), y(vals[i])]);
      out += `<polygon points="${pts([...line, [x(run.at(-1)), h], [x(run[0]), h]])}" fill="url(#${id})"/><polyline points="${pts(line)}" fill="none" stroke="${col}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`;
    }
    if (vals.at(-1) != null) out += `<circle cx="${x(n - 1)}" cy="${y(vals.at(-1))}" r="${android ? 2.5 : 3}" fill="${col}"/>`;
    return out;
  };

  const drawBand = (cfg, w, h, android) => {
    const P = cfg.points, n = P.length, max = bandMax(P), col = w / n, grid = "var(--border-weak-base)", ia = "var(--interactive)";
    const x = (i) => (i + 0.5) * col, y = (v) => h * (1 - Math.min(Math.max(v / max, 0), 1));
    let out = "";
    P.forEach(([, , bg], i) => {
      const l = i * col;
      if (bg === "warning") out += `<rect x="${l}" y="0" width="${col}" height="${h}" fill="${mix("warning", 12)}"/>`;
      if (bg === "error") out += `<rect x="${l}" y="0" width="${col}" height="${h}" fill="${mix("error", 16)}"/>`;
      if (bg === "unobserved") out += android ? hatch(l, col, h, 4, mix("text-weaker", 20)) : hatch(l, col, h, 5, mix("text-strong", 10), mix("text-strong", 5));
    });
    const dash = android ? "4 4" : "3 3";
    out += `<line x1="0" y1="${h / 2}" x2="${w}" y2="${h / 2}" stroke="${grid}" stroke-dasharray="${dash}"/><line x1="0" y1="${h - 0.5}" x2="${w}" y2="${h - 0.5}" stroke="${grid}"/>`;
    for (const run of runsOf(P, (p) => p[0] != null)) {
      const up = (i) => Math.max(P[i][1] ?? P[i][0], android ? -Infinity : P[i][0]);
      if (run.length === 1) {
        const i = run[0];
        if (android) { out += `<circle cx="${x(i)}" cy="${y(P[i][0])}" r="1.5" fill="${ia}"/>`; continue; }
        const l = x(i) - col * 0.35, r = x(i) + col * 0.35;
        out += `<rect x="${l}" y="${y(up(i))}" width="${r - l}" height="${Math.max(0, y(P[i][0]) - y(up(i)))}" fill="${mix("interactive", 18)}"/><line x1="${l}" x2="${r}" y1="${y(P[i][0])}" y2="${y(P[i][0])}" stroke="${ia}" stroke-width="1.5"/>`;
        continue;
      }
      const top = run.map((i) => [x(i), y(up(i))]), mid = run.map((i) => [x(i), y(P[i][0])]);
      out += `<polygon points="${pts([...top, ...[...mid].reverse()])}" fill="${mix("interactive", 18)}"/><polyline points="${pts(mid)}" fill="none" stroke="${ia}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`;
    }
    return out;
  };

  const drawStrip = (cfg, w, h, android) => {
    const s = cfg.segments, n = s.length, gap = n > 70 ? 0.5 : 1, bw = Math.max(0.5, (w - gap * (n - 1)) / n), r = Math.min(android ? 1.5 : 2, bw / 2);
    const fill = (k) => (k === "unobserved" ? (android ? "var(--border-weaker-base)" : mix("text-strong", 10)) : `var(--${k})`);
    return s.map((k, i) => `<rect x="${i * (bw + gap)}" y="0" width="${bw}" height="${h}" rx="${r}" fill="${fill(k)}"/>`).join("");
  };

  const drawBar = (cfg, w, h, android) => {
    const v = cfg.values, n = v.length, max = Math.max(0, ...v), slot = w / n, bw = android ? slot * 0.7 : Math.max(0.5, slot * 0.7), r = Math.min(1.5, bw / 2), usable = android ? h - 14 : Math.max(1, h - 15);
    let out = "";
    if (max > 0) v.forEach((x, i) => { if (x <= 0) return; const bh = android ? (x / max) * usable : Math.max(1.5, usable * (x / max)); out += `<rect x="${i * slot + (slot - bw) / 2}" y="${(android ? h : h - 1) - bh}" width="${bw}" height="${bh}" rx="${r}" fill="var(--${cfg.tone})"/>`; });
    return out + `<line x1="0" y1="${h - 0.5}" x2="${w}" y2="${h - 0.5}" stroke="var(--border-weak-base)"/>`;
  };
  const DRAW = { spark: drawSpark, band: drawBand, strip: drawStrip, bar: drawBar };

  const paintChart = (el, android) => {
    const cfg = JSON.parse(el.dataset.chart), w = el.clientWidth, h = el.clientHeight, svg = el.querySelector("svg");
    if (!w || !h) return;
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    svg.innerHTML = DRAW[cfg.type](cfg, w, h, android);
  };

  const swatch = (k, c) => {
    const android = isA(c), size = android ? 8 : 10;
    if (!android && k === "interactive") return `<span class="c-ch-sw" style="width:10px;height:10px;display:grid;align-items:center"><i style="display:block;height:2px;border-radius:1px;background:var(--interactive)"></i></span>`;
    if (!android && k === "unobserved") return `<span class="c-ch-sw hatch" style="width:10px;height:10px;border-radius:2px"></span>`;
    const bg = k === "interactiveWeak" ? mix("interactive", android ? 30 : 18) : k === "unobserved" ? "var(--border-weaker-base)" : `var(--${k})`;
    return `<span class="c-ch-sw" style="width:${size}px;height:${size}px;border-radius:2px;background:${bg}"></span>`;
  };

  const chartCard = ({ title, summary, axis, legend = [], content }, c) => {
    const android = isA(c), t = type(c), axisColor = android ? "text-weaker" : "text-weak", axisStyle = android ? t.labelS() : f(11, 14, 400, "font-variant-numeric:tabular-nums;");
    return `<div class="c-ch-card" style="padding:${android ? "12px 14px" : "14px"}">
      <div class="c-ch-head" style="align-items:${android ? "center" : "baseline"}"><span class="c-ch-title" style="${android ? f(14, 20, 600) : t.body()}">${esc(title)}</span>${summary ? `<span class="c-ch-sum" style="${android ? t.labelS() : f(13, 18, 400, "font-variant-numeric:tabular-nums;")}">${esc(summary)}</span>` : ""}</div>
      <div>${content}</div>
      ${axis ? `<div class="c-ch-axis ${android ? "eq" : ""}" style="${axisStyle}color:var(--${axisColor})">${axis.map((x) => `<span>${esc(x)}</span>`).join("")}</div>` : ""}
      ${legend.length ? `<div class="c-ch-legend" style="gap:${android ? 4 : 6}px 12px">${legend.map(([l, k]) => `<span class="c-ch-li" style="${android ? t.labelS() : f(11, 14)}">${swatch(k, c)}${esc(l)}</span>`).join("")}</div>` : ""}
    </div>`;
  };

  const statTile = ({ label, value, detail }, c) => {
    const android = isA(c), t = type(c);
    return `<div class="c-ch-card c-ch-stat" style="padding:${android ? "12px 14px" : "14px"};gap:${android ? 2 : 4}px"><span class="c-ch-one" style="${t.labelS()}color:var(--text-weak)">${esc(label)}</span><span class="c-ch-one" style="${t.title}font-variant-numeric:tabular-nums;color:var(--text-strong)">${esc(value)}</span>${detail ? `<span class="c-ch-two" style="${t.labelS()}color:var(--text-base)">${esc(detail)}</span>` : ""}</div>`;
  };

  const proportion = ({ label, count, fraction, tone }, c) => {
    const android = isA(c), fr = Math.min(Math.max(Number.isFinite(fraction) ? fraction : 0, 0), 1);
    const fill = fr > 0 ? (android ? `${fr * 100}%` : `max(4px, ${fr * 100}%)`) : "0";
    return `<div class="c-ch-prop ${android ? "plat-a" : "plat-i"}" style="${f(13, 18)}"><span class="l">${esc(label)}</span><span class="t" style="background:var(--${android ? "border-weaker-base" : "surface-raised-base"})"><i style="width:${fill};background:var(--${tone})"></i></span><span class="n">${esc(count)}</span></div>`;
  };

  const LEG = { latency: [["Median", "interactive"], ["p95", "interactiveWeak"], ["Offline", "error"], ["Not measured", "unobserved"]], conn: [["Live", "success"], ["Unsteady", "warning"], ["Down", "error"], ["Not measured", "unobserved"]] };
  const stripEl = (segs) => chart({ type: "strip", segments: segs }, 14);
  const chartsRender = (p, c) => {
    const P = isA(c) ? "android" : "ios", android = P === "android";
    if (p.chart === "sparkline") {
      const vals = p.data === "rising" ? [80, 95, 120, 140] : p.data === "short" ? [12, 14, 13, 18, 16] : LAT[P];
      return chart({ type: "spark", values: vals, tone: p.tone, gaps: p.gaps }, Number(p.height));
    }
    if (p.chart === "band") return chartCard({ title: "Latency", summary: android ? "median 24 ms · p95 61 ms" : "median 24 ms · p95 41 ms", axis: android ? ["24 h ago", "now"] : ["09:00", "21:00", "now"], legend: LEG.latency, content: chart({ type: "band", points: BAND[P], unit: "ms", android }, 120) }, c);
    if (p.chart === "strip") return chartCard({ title: "Connection", summary: android ? "97.4% live" : "98.7% live", axis: android ? ["24 h ago", "12 h", "now"] : null, legend: LEG.conn, content: android ? stripEl(STRIP.android) : `<div class="c-ch-col">${stripEl(STRIP.ios)}${stripEl(STRIP.ios.slice(0, 24))}</div>` }, c);
    if (p.chart === "bar") return chartCard({ title: "Reconnects", summary: android ? "4 reconnects" : "7 reconnects", content: chart({ type: "bar", values: BARS[P], tone: p.tone === "interactive" ? "warning" : p.tone }, 56) }, c);
    if (p.chart === "stat") return android
      ? `<div class="c-ch-tiles" style="gap:8px">${statTile({ label: "Uptime", value: "99.97%", detail: "of 6 h 12 m measured" }, c)}${statTile({ label: "Reconnects", value: "0", detail: "no drops" }, c)}</div>`
      : `<div class="c-ch-col" style="gap:10px"><div class="c-ch-tiles" style="gap:10px">${statTile({ label: "Uptime", value: "99.82%", detail: "of 6 h 12 m measured" }, c)}${statTile({ label: "Reconnects", value: "2", detail: "back in 4.2 s median" }, c)}</div><div class="c-ch-tiles" style="gap:10px">${statTile({ label: "Error rate", value: "0.4%" }, c)}${statTile({ label: "Between drops", value: "—", detail: "no drops" }, c)}</div></div>`;
    if (p.chart === "proportion") {
      const rows = android ? [["Heartbeat stall", "6", 0.6, "warning"], ["Server error", "3", 0.3, "error"], ["Other", "1", 0.1, "info"]] : [["Network", "6", 0.6, "warning"], ["Server error", "3", 0.3, "info"], ["Authentication", "1", 0.1, "error"]];
      const list = `<div class="c-ch-col" style="gap:${android ? 8 : 10}px">${rows.map(([label, count, fraction, tone]) => proportion({ label, count, fraction, tone }, c)).join("")}</div>`;
      return android ? chartCard({ title: "Errors", content: list }, c) : list;
    }
    return listItem({ ...Kit.components["list-item"].defaults, title: "Workstation", subtitle: "192.168.1.2:4096", caption: "", status: android ? "success" : "interactive", statusText: "Active", style: "card", decorator: "letter", letter: "W", accent: android ? "teal" : "blue", footerSlot: true }, c);
  };

  /* ---------- Picker Card (Android) and Run Session In Card (iOS) ---------- */
  const PICK_ROWS = {
    picker: {
      local: [["Project", "opencode-mobile", { letter: "O", accent: "teal" }], ["Worktree", "Local", { icon: "terminal" }]],
      worktree: [["Project", "opencode-mobile", { letter: "O", accent: "teal" }], ["Worktree", "New worktree", { icon: "add", hi: true }], ["Starts from", "main", { icon: "call_split" }, { mono: true }]],
      notgit: [["Project", "opencode-mobile", { letter: "O", accent: "teal" }], ["Worktree", "None · not a Git repo", { icon: "folder" }, { off: true }]],
    },
    run: {
      local: [["Project", "opencode-mobile", { letter: "O", accent: "violet" }], ["Worktree", "New worktree", { icon: "add", hi: true }], ["Starts from", "main", { icon: "git_branch" }, { mono: true }]],
      worktree: [["Project", "opencode-mobile", { letter: "O", accent: "violet" }], ["Worktree", "New worktree", { icon: "add", hi: true }], ["Starts from", "main", { icon: "git_branch" }, { mono: true }]],
      notgit: [["Project", "notes", { letter: "N", accent: "teal" }], ["Worktree", "None · not a Git repo", { icon: "folder" }, { off: true }]],
    },
  };
  const pickerCard = (p, c, android) => {
    const compact = !!p.compact, rows = PICK_ROWS[android ? "picker" : "run"][p.rows] ?? [], size = compact ? 34 : 40, tileR = compact ? 10 : 12;
    const iconSize = android ? (compact ? 18 : 20) : compact ? 17 : 20, chevron = android ? 18 : 16, vpad = android ? (compact ? 8 : 12) : compact ? 7 : 12;
    const rowsHtml = rows.map(([label, value, tile, o = {}], i) => {
      const on = !o.off;
      let tileEl;
      if (tile.letter) {
        const [fill, tint] = accent(tile.accent, c), g = android ? 16 : compact ? 15 : 17;
        tileEl = `<span class="c-pk-tile" style="width:${size}px;height:${size}px;border-radius:${tileR}px;background:${fill};color:${tint};font-size:${g}px;font-weight:700;${!android && !on ? "opacity:.5;" : ""}">${esc(glyph(tile.letter))}</span>`;
      } else {
        const tint = !android && !on ? "text-weaker" : tile.hi ? "text-interactive-base" : android ? "icon-strong-base" : "icon-base", bg = tile.hi && (android || on) ? "surface-interactive-weak" : "surface-raised-base";
        tileEl = `<span class="c-pk-tile" style="width:${size}px;height:${size}px;border-radius:${tileR}px;background:var(--${bg});color:var(--${tint})">${I(tile.icon, iconSize)}</span>`;
      }
      const valueStyle = android ? f(14, 20, 600, o.mono ? "font-family:var(--mono);" : "") : f(15, 20, 600, o.mono ? "font-family:var(--mono);" : "");
      const divider = i < rows.length - 1 ? `<div class="c-pk-div" style="margin-left:${14 + size + 12}px"></div>` : "";
      return `<div class="c-pk-row ${on ? "press" : ""}" role="button" tabindex="${on ? 0 : -1}"${on ? "" : ' aria-disabled="true"'} style="padding:${vpad}px 14px;${android && !on ? "opacity:.55;" : ""}">${tileEl}<span class="c-pk-tx"${android ? "" : ' style="gap:1px"'}><span style="${android ? f(12, 16) : f(13, 18)}color:var(--text-weak)">${esc(label)}</span><span class="c-pk-v" style="${valueStyle}color:var(--${!android && !on ? "text-weaker" : "text-strong"})">${esc(value)}</span></span>${on ? `<span style="color:var(--${android ? "icon-base" : "icon-weak-base"})">${I("chevron_right", chevron)}</span>` : ""}</div>${divider}`;
    }).join("");
    const capStyle = android ? f(12, 16, 500, "letter-spacing:.8px;") : f(13, 18, 600, "letter-spacing:.5px;padding:0 4px;align-self:flex-start;");
    const noteStyle = android ? `${f(12, 16, 400, p.footMono ? "font-family:var(--mono);" : "")}color:var(--text-weak);padding:0 8px;max-width:100%;` : `${f(12, 16, 400, "font-family:var(--mono);")}color:var(--text-weaker);padding:0 4px;align-self:flex-start;max-width:100%;`;
    return `<div class="c-pk ${android ? "plat-a" : "plat-i"}" style="gap:${!android && compact ? 6 : 8}px">${p.caption && !compact ? `<div style="${capStyle}color:var(--text-weak);text-transform:uppercase">${esc(p.caption)}</div>` : ""}<div class="c-pk-card" style="border-radius:${android ? 16 : 18}px">${rowsHtml}</div>${p.footnote ? `<div class="c-pk-note" style="${noteStyle}">${esc(p.footnote)}</div>` : ""}</div>`;
  };

  /* ---------- Tree Row ---------- */
  const treeRow = (p, c) => {
    const android = isA(c), folder = p.kind !== "file", open = folder && p.kind === "expanded", dimmed = !!p.dimmed, selected = p.state === "selected", loading = p.state === "loading", sub = p.subtitle || null, depth = Math.max(0, Number(p.depth) || 0);
    const dim = dimmed ? (android ? 0.5 : 0.55) : 1, t = type(c);
    const chev = folder ? `<span class="c-tr-chev${open ? " open" : ""}" style="color:var(--${android ? "icon-base" : "icon-weak-base"})">${I("chevron_right", 16)}</span>` : "";
    const slot = `<span class="c-tr-slot" style="width:${android ? 20 : 16}px">${chev}</span>`;
    let icon;
    if (p.icon === "fileType") icon = fileIcon(p.path || p.title, folder ? "directory" : "file", open, 18, dimmed ? dim : 0);
    else { const col = android ? (selected ? "icon-strong-base" : "icon-base") : folder ? "icon-base" : "icon-weak-base"; icon = `<span style="color:var(--${col});opacity:${dim};display:inline-flex">${I(folder ? "folder" : "description", 18)}</span>`; }
    const tag = p.tag ? (android
      ? `<span class="c-tr-tag" style="${f(12, 16, 700)}padding:5px 10px;background:var(--surface-raised-base);color:var(--text-base)">${esc(p.tag)}</span>`
      : `<span class="c-tr-tag" style="${f(13, 18)}padding:1px 6px;background:var(--surface-raised-base);color:var(--text-weak)">${esc(p.tag)}</span>`) : "";
    const spin = loading ? `<span class="c-tr-spin ${android ? "plat-a" : "plat-i"}"></span>` : "";
    const inner = `${slot}${icon}<span class="c-tr-tx" style="opacity:${dim};${android ? "padding:6px 0;" : "gap:1px;"}"><span class="c-tr-ti ${android ? "" : "mid"}" style="${t.secondary}color:var(--text-strong)">${esc(p.title)}</span>${sub ? `<span class="c-tr-su ${android ? "" : "head"}" style="${t.caption}color:var(--${android ? "text-weaker" : "text-weak"})"><bdi>${esc(sub)}</bdi></span>` : ""}</span>${tag}${spin}`;
    const attrs = `role="button" tabindex="0"${folder ? ` aria-expanded="${open}"` : ""}${selected ? ' aria-selected="true"' : ""} data-tree-path="${esc(p.path || p.title)}"`;
    return android
      ? `<div class="c-tr-out"><div class="c-tr plat-a press${selected ? " sel" : ""}" ${attrs} style="min-height:${sub ? 48 : 36}px;padding:0 8px 0 ${16 * depth}px">${inner}</div></div>`
      : `<div class="c-tr plat-i${selected ? " sel" : ""}" ${attrs} style="min-height:40px;padding:0 12px 0 ${12 + 16 * depth}px">${inner}</div>`;
  };
  const TREE_DATA = { ios: { root: "Sources", kids: ["Sources/App.swift", "Sources/FilesView.swift"], ignored: "build", leaf: "README.md", sel: "Sources/App.swift" }, android: { root: "src", kids: ["src/main.kt", "src/Util.kt"], ignored: null, leaf: null, sel: "src/main.kt" } };
  const treeDemo = (c) => {
    const d = TREE_DATA[isA(c) ? "android" : "ios"], base = Kit.components["tree-row"].defaults, r = (o) => treeRow({ ...base, icon: "fileType", subtitle: "", tag: "", state: "normal", dimmed: false, depth: 0, ...o }, c);
    let html = `<div data-tree-node="${esc(d.root)}">${r({ title: d.root, path: d.root, kind: "expanded" })}</div><div data-tree-kids="${esc(d.root)}">${d.kids.map((k) => r({ title: k.split("/").pop(), path: k, kind: "file", depth: 1, state: k === d.sel ? "selected" : "normal" })).join("")}</div>`;
    if (d.ignored) html += `<div data-tree-node="${esc(d.ignored)}">${r({ title: d.ignored, path: d.ignored, kind: "collapsed", dimmed: true, tag: "ignored" })}</div><div data-tree-kids="${esc(d.ignored)}" hidden><div class="c-tr-note" style="${f(13, 18)}padding-left:${12 + 16 + 16 + 6}px">Empty folder</div></div>`;
    if (d.leaf) html += r({ title: d.leaf, path: d.leaf, kind: "file" });
    return `<div class="c-tr-demo">${html}</div>`;
  };

  /* ---------- Reorderable List ---------- */
  const RO_ITEMS = [["all", "All projects", "4 projects"], ["opencode-mobile", "opencode-mobile", "~/Projects/opencode-mobile", "violet"], ["playground", "playground", "~/Projects/playground", "teal"], ["website", "website", "~/Projects/website", "amber"], ["docs", "docs", "~/Projects/docs", "blue"]];
  const handleEl = (c, movable) => isA(c)
    ? `<span class="c-ro-handle plat-a" data-handle style="opacity:${movable ? 1 : 0.4}"><svg viewBox="0 0 20 20" aria-hidden="true">${[6, 10, 14].map((y) => `<line x1="3" x2="17" y1="${y}" y2="${y}" stroke="var(--icon-base)" stroke-width="1.5" stroke-linecap="round"/>`).join("")}</svg></span>`
    : movable ? `<span class="c-ro-handle plat-i" data-handle style="color:var(--icon-base)">${I("drag_handle", 18)}</span>` : "";
  const reorderable = (p, c) => {
    const android = isA(c), base = Kit.components["list-item"].defaults, movableCount = RO_ITEMS.length - 1, canReorder = p.reorder && movableCount >= 2;
    const rows = RO_ITEMS.map(([id, title, path, acc], i) => {
      const isAll = id === "all", movable = canReorder && !isAll, canReveal = p.contextAction && !isAll;
      const st = i === 2 ? p.state : "rest", item = listItem({ ...base, title, subtitle: path, caption: "", status: "none", decorator: isAll ? "iconCircle" : "letter", accent: acc ?? "violet", letter: title }, c).replace('tabindex="0"', 'tabindex="-1"').replace(" press", "");
      const strip = canReveal ? `<div class="c-ro-strip" style="background:var(--error);color:${android ? "#fff" : "var(--surface-base)"}">Remove</div>` : "";
      return `<div class="c-ro-row${st !== "rest" ? ` is-${st}` : ""}" data-id="${esc(id)}" data-title="${esc(title)}" data-movable="${movable ? 1 : 0}" data-reveal="${canReveal ? 1 : 0}">${strip}<div class="c-ro-fg">${p.handles && (movable || android) ? handleEl(c, movable) : ""}<div class="c-ro-item">${item}</div></div></div>`;
    }).join("");
    return `<div class="c-ro-wrap"><div class="c-ro-log" style="${isA(c) ? f(12, 16, 500, "letter-spacing:.5px;") : f(13, 18)}">Long-press a project row, then drag.</div><div class="c-ro" data-lp="${android ? 400 : 420}" data-slop="${android ? 8 : 14}" data-max="${android ? 8 : 18}">${rows}</div></div>`;
  };

  /* ---------- Behaviors ---------- */
  const once = (el, key) => { if (el.dataset[key]) return false; el.dataset[key] = "1"; return true; };
  const measure = (() => { const cv = document.createElement("canvas").getContext("2d"); return (s, el) => { const st = getComputedStyle(el); cv.font = `${st.fontWeight} ${st.fontSize} ${st.fontFamily}`; return cv.measureText(s).width; }; })();
  const middleCut = (s, kept) => { if (kept >= s.length) return s; const head = Math.ceil(kept / 2), tail = kept - head; return `${s.slice(0, head)}…${tail ? s.slice(-tail) : ""}`; };
  const fitPath = (el) => {
    const a = el.querySelector(".a"), s = el.querySelector(".s"), b = el.querySelector(".b"), P = el.dataset.primary, S = el.dataset.secondary;
    const total = el.clientWidth; if (!total) return;
    const avail = Math.max(0, total - measure("/", s) - 10), pw = measure(P, a), sw = measure(S, b);
    const pGet = pw + sw <= avail ? pw : pw <= avail * 0.4 ? pw : sw <= avail * 0.6 ? avail - sw : avail * 0.4, sGet = Math.max(0, avail - pGet);
    a.style.maxWidth = `${Math.ceil(pGet)}px`; a.textContent = P;
    let lo = 5, hi = S.length - 1, best = sw <= sGet ? S : middleCut(S, 5);
    if (sw > sGet) while (lo <= hi) { const k = (lo + hi) >> 1, cand = middleCut(S, k); if (measure(cand, b) <= sGet) { best = cand; lo = k + 1; } else hi = k - 1; }
    b.textContent = best;
  };
  const fitMiddle = (el) => {
    const full = el.dataset.full ?? (el.dataset.full = el.textContent); el.textContent = full;
    const w = el.clientWidth; if (!w || measure(full, el) <= w) return;
    let lo = 1, hi = full.length - 1, best = middleCut(full, 1);
    while (lo <= hi) { const k = (lo + hi) >> 1, cand = middleCut(full, k); if (measure(cand, el) <= w) { best = cand; lo = k + 1; } else hi = k - 1; }
    el.textContent = best;
  };

  const mountReorder = (root) => {
    if (!once(root, "bound")) return;
    const log = root.parentElement.querySelector(".c-ro-log"), say = (t) => { log.textContent = t; };
    const LP = +root.dataset.lp, SLOP = +root.dataset.slop, MAX = +root.dataset.max, REVEAL = 88, OPEN = 44;
    const rows = () => [...root.querySelectorAll(".c-ro-row")];
    const fgOf = (r) => r.querySelector(".c-ro-fg");
    const setReveal = (r, x, animate = true) => { const fg = fgOf(r), st = r.querySelector(".c-ro-strip"); fg.style.transition = animate ? "transform .3s cubic-bezier(.2,.85,.3,1)" : "none"; fg.style.transform = x ? `translateX(${-x}px)` : ""; if (st) { st.style.transition = fg.style.transition; st.style.transform = `translateX(${REVEAL - x}px)`; } r.classList.toggle("is-open", x >= REVEAL); };
    const closeOpen = () => { const o = root.querySelector(".c-ro-row.is-open"); if (o) setReveal(o, 0); return !!o; };
    root.querySelectorAll(".c-ro-row").forEach((r) => { const st = r.querySelector(".c-ro-strip"); if (st) st.style.transform = `translateX(${r.classList.contains("is-swiped") ? 0 : REVEAL}px)`; if (r.classList.contains("is-swiped")) { fgOf(r).style.transform = `translateX(-${REVEAL}px)`; r.classList.add("is-open"); } });
    let g = null;
    const targetIndex = (from, dy, list, h) => {
      let t = from, passed = 0;
      if (dy > 0) for (let i = from + 1; i < list.length && list[i].dataset.movable === "1"; i++) { if (dy < passed + h[i] / 2) break; t = i; passed += h[i]; }
      else for (let i = from - 1; i >= 0 && list[i].dataset.movable === "1"; i--) { if (-dy < passed + h[i] / 2) break; t = i; passed += h[i]; }
      return t;
    };
    const clampDy = (from, dy, list, h) => { let up = 0, down = 0; for (let i = from - 1; i >= 0 && list[i].dataset.movable === "1"; i--) up += h[i]; for (let i = from + 1; i < list.length && list[i].dataset.movable === "1"; i++) down += h[i]; return Math.min(Math.max(dy, -up), down); };
    const lift = () => { clearTimeout(g.timer); g.phase = "lift"; g.list = rows(); g.h = g.list.map((x) => x.offsetHeight); g.from = g.list.indexOf(g.row); g.to = g.from; g.row.classList.remove("is-pressed"); g.row.classList.add("is-lifted"); g.list.forEach((x) => { if (x !== g.row) x.style.transition = "transform .32s cubic-bezier(.2,.9,.3,1)"; }); };
    const layout = (dy) => {
      const d = clampDy(g.from, dy, g.list, g.h), to = targetIndex(g.from, d, g.list, g.h), hh = g.h[g.from]; g.to = to; g.d = d;
      g.row.style.transform = `translateY(${d}px) scale(1.03)`;
      g.list.forEach((x, i) => { if (x === g.row) return; const s = i > g.from && i <= to ? -hh : i < g.from && i >= to ? hh : 0; x.style.transform = s ? `translateY(${s}px)` : ""; });
    };
    const drop = () => {
      const { list, row, from, to } = g;
      row.classList.remove("is-lifted"); row.style.transition = "transform .3s cubic-bezier(.2,.85,.3,1)";
      const offset = list.slice(Math.min(from, to), Math.max(from, to)).reduce((sum, _, k) => sum + g.h[Math.min(from, to) + k + (to > from ? 1 : 0)], 0) * (to > from ? 1 : -1);
      row.style.transform = `translateY(${to === from ? 0 : offset}px)`;
      setTimeout(() => {
        list.forEach((x) => { x.style.transition = "none"; x.style.transform = ""; });
        if (to !== from) { root.insertBefore(row, to > from ? list[to].nextSibling : list[to]); say(`Moved row ${from} to ${to}.`); }
        void root.offsetHeight; list.forEach((x) => { x.style.transition = ""; });
      }, 300);
    };
    root.addEventListener("pointerdown", (e) => {
      const row = e.target.closest(".c-ro-row"); if (!row || e.button) return;
      if (e.target.closest(".c-ro-strip")) { const name = row.dataset.title; setReveal(row, 0); say(`Context action on ${name}.`); return; }
      const open = root.querySelector(".c-ro-row.is-open");
      if (open && open !== row) { setReveal(open, 0); g = null; return; }
      const fromHandle = !!e.target.closest("[data-handle]") && row.dataset.movable === "1";
      g = { row, id: e.pointerId, x: e.clientX, y: e.clientY, phase: "press", wasOpen: open === row, base: open === row ? REVEAL : 0 };
      root.setPointerCapture(e.pointerId);
      row.classList.add("is-pressed");
      if (fromHandle) lift();
      else if (row.dataset.movable === "1" && !g.wasOpen) g.timer = setTimeout(() => { if (g?.phase === "press") lift(); }, LP);
    });
    root.addEventListener("pointermove", (e) => {
      if (!g || e.pointerId !== g.id) return;
      const dx = e.clientX - g.x, dy = e.clientY - g.y;
      if (g.phase === "press") {
        if (g.row.dataset.reveal === "1" && Math.abs(dx) > SLOP && Math.abs(dx) > Math.abs(dy)) { clearTimeout(g.timer); g.phase = "swipe"; g.row.classList.remove("is-pressed"); }
        else if (Math.abs(dy) > MAX) { clearTimeout(g.timer); g.row.classList.remove("is-pressed"); g.phase = "cancel"; }
      }
      if (g.phase === "swipe") { g.reveal = Math.min(Math.max(g.base - dx, 0), REVEAL); setReveal(g.row, g.reveal, false); }
      if (g.phase === "lift") layout(dy);
    });
    const end = (e) => {
      if (!g || e.pointerId !== g.id) return;
      clearTimeout(g.timer); g.row.classList.remove("is-pressed");
      if (g.phase === "press") { if (g.wasOpen) setReveal(g.row, 0); else say(`Selected ${g.row.dataset.title}.`); }
      if (g.phase === "swipe") setReveal(g.row, (g.reveal ?? 0) >= OPEN ? REVEAL : 0);
      if (g.phase === "lift") { if (e.type === "pointercancel") { g.to = g.from; } drop(); }
      g = null;
    };
    root.addEventListener("pointerup", end); root.addEventListener("pointercancel", end);
    void closeOpen;
  };

  const mountAll = (stage, ctx) => {
    const android = ctx?.platform === "android";
    stage.querySelectorAll(".c-tr-img").forEach((img) => { if (once(img, "baked")) bakeIcon(img); });
    stage.querySelectorAll(".c-li-path").forEach(fitPath);
    stage.querySelectorAll(".c-pk.plat-i .c-pk-v, .c-tr-ti.mid").forEach(fitMiddle);
    stage.querySelectorAll("[data-chart]").forEach((el) => {
      paintChart(el, android);
      if (once(el, "obs")) new ResizeObserver(() => paintChart(el, android)).observe(el);
    });
    stage.querySelectorAll(".c-li-sw:not(.off)").forEach((el) => { if (once(el, "bound")) el.addEventListener("click", (e) => { e.stopPropagation(); el.classList.toggle("on"); el.setAttribute("aria-checked", el.classList.contains("on")); }); });
    stage.querySelectorAll("[data-sh-clear]").forEach((el) => { if (!once(el, "bound")) return; let left = 3; el.addEventListener("click", () => { left = Math.max(left - 1, 0); const title = el.closest(".c-sh").querySelector(".c-sh-ti"); title.textContent = `${left} waiting`; }); });
    stage.querySelectorAll("[data-opt-group]").forEach((group) => { if (!once(group, "bound")) return; group.addEventListener("click", (e) => { const card = e.target.closest(".c-opt:not(.off)"); if (!card) return; group.querySelectorAll(".c-opt").forEach((x) => { const on = x === card; x.classList.toggle("sel", on); x.setAttribute("aria-selected", on); }); }); });
    stage.querySelectorAll("[data-sel-group]").forEach((group) => { if (!once(group, "bound")) return; group.addEventListener("click", (e) => { const card = e.target.closest(".c-sc"); if (!card) return; group.querySelectorAll(".c-sc").forEach((x) => { const on = x === card; x.classList.toggle("sel", on); x.setAttribute("aria-selected", on); }); }); });
    stage.querySelectorAll(".c-tr-demo").forEach((demo) => {
      if (!once(demo, "bound")) return;
      demo.addEventListener("click", (e) => {
        const row = e.target.closest("[data-tree-path]"); if (!row) return;
        const node = row.closest("[data-tree-node]");
        if (node) {
          const kids = [...demo.querySelectorAll("[data-tree-kids]")].find((k) => k.dataset.treeKids === node.dataset.treeNode), open = kids.hidden;
          kids.hidden = !open; row.setAttribute("aria-expanded", open); row.querySelector(".c-tr-chev")?.classList.toggle("open", open);
          const img = row.querySelector(".c-tr-img");
          if (img) setFolderIcon(img, open);
        } else { demo.querySelectorAll("[data-tree-path]").forEach((x) => { const on = x === row; x.classList.toggle("sel", on); if (on) x.setAttribute("aria-selected", "true"); else x.removeAttribute("aria-selected"); }); }
      });
    });
    stage.querySelectorAll(".c-ro").forEach(mountReorder);
  };

  /* ---------- Small components ---------- */
  const sectionHeader = (p, c) => {
    const android = isA(c), label = p.variant === "label", pad = p.inset === "flush" ? 0 : 16;
    const titleStyle = label ? `${android ? f(12, 16, 600) : f(13, 18, 600)}letter-spacing:.5px;` : f(13, 18);
    const value = label ? p.title.toLocaleUpperCase() : p.title;
    const trail = p.trailing === "count" ? `<span style="${f(13, 18, 400, "font-variant-numeric:tabular-nums;")}color:var(--text-weak)">${esc(p.count)}</span>` : p.trailing === "action" ? `<button class="c-sh-act" data-sh-clear style="${f(13, 18, 500)}">${esc(p.action)}</button>` : "";
    return `<div class="c-sh" style="padding:16px ${pad}px 4px;align-items:${android ? "center" : "baseline"}"><span class="c-sh-ti ${!label && !android ? "c-tr-ti mid" : ""}" style="${titleStyle}">${esc(value)}</span>${trail}</div>`;
  };

  const keyValue = (p, c) => {
    const android = isA(c), t = type(c);
    if (p.style === "stacked") return `<div class="c-kv st" style="gap:6px"><span class="${android ? "c-kv-one" : ""}" style="${t.caption}color:var(--text-base)">${esc(p.label)}</span><span class="c-kv-two" style="${android ? t.secondary : t.body()}color:var(--text-strong)">${esc(p.value)}</span></div>`;
    return `<div class="c-kv in" style="align-items:${android ? "flex-start" : "baseline"};gap:${android ? 8 : 20}px"><span style="${t.caption}color:var(--text-base)">${esc(p.label)}</span><span style="${t.caption}color:var(--text-strong);text-align:right">${esc(p.value)}</span></div>`;
  };

  const featureItem = (p, c) => {
    const android = isA(c), t = type(c), ok = p.tone === "success";
    return `<div class="c-fi" style="padding:12px ${android ? 16 : 0}px"><span class="c-fi-tile" style="background:${ok ? mix("success", 14) : "var(--surface-raised-base)"};color:var(--${ok ? "success" : "icon-strong-base"})">${I(p.icon, 20)}</span><span class="c-fi-tx"><span style="${t.body(600)}color:var(--text-strong)">${esc(p.title)}</span>${p.body ? `<span style="${t.secondary}color:var(--${android ? "text-weak" : "text-base"})">${esc(p.body)}</span>` : ""}</span></div>`;
  };

  const optionCard = (p, c) => {
    const android = isA(c), t = type(c), on = !p.disabled;
    const titleStyle = android ? `${t.body(600)}display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;` : t.heading;
    const tag = p.tag ? smallTag(p.tag, p.tagTone === "accent" ? "accent" : "neutral", c) : "";
    const preview = p.preview !== "none" ? `<span class="c-opt-prev">${skel(p.preview)}</span>` : "";
    return `<div class="c-opt${p.selected ? " sel" : ""}${on ? (android ? " press" : "") : " off"}" role="radio" tabindex="${on ? 0 : -1}" aria-checked="${!!p.selected}"${on ? "" : ' aria-disabled="true"'}><span class="c-opt-radio"><i></i></span><span class="c-opt-tx"><span class="c-opt-tl"><span style="${titleStyle}color:var(--text-strong)">${esc(p.title)}</span>${tag}</span>${p.subtitle ? `<span class="c-opt-su" style="${t.caption}color:var(--text-weak)">${esc(p.subtitle)}</span>` : ""}</span>${preview}</div>`;
  };
  const OPTION_SET = [["Activity Card", "Running steps on one live card that folds into a summary", "Recommended", "accent", "activity"], ["Standard", "Tool cards and groups between the agent's messages", "Current", "neutral", "standard"], ["Step Rail", "One line per step on a rail, live while the agent works", "", "neutral", "rail"]];

  const selectableCard = (p, c) => {
    const t = type(c);
    return `<div class="c-sc${p.selected ? " sel" : ""}${isA(c) ? " press" : ""}" role="button" tabindex="0" aria-selected="${!!p.selected}"><span class="c-sc-h"><span class="c-sc-tx"><span style="${t.heading}color:var(--text-strong)">${esc(p.title)}</span><span style="${t.caption}color:var(--text-base)">${esc(p.blurb)}</span></span><span class="c-sc-check">${I("check", 18)}</span></span><span class="c-sc-prev" style="${t.caption}">${esc(p.previewText)}</span></div>`;
  };

  const listGroup = (p, c) => {
    const n = Number(p.rows);
    if (!n) return `<div class="c-lg-none" style="${f(11, 14)}">Renders nothing</div>`;
    const base = Kit.components["list-item"].defaults, files = (isA(c) ? ["Sources/App.kt", "README.md", "Tests/AppTest.kt"] : ["Sources/App.swift", "README.md", "Tests/AppTests.swift"]).slice(0, n);
    return `<div class="c-lg">${files.map((t, i) => `${listItem({ ...base, title: t, subtitle: "", caption: "", status: "none", decorator: "none", accessory: "disclosure" }, c)}${i < files.length - 1 ? '<div class="c-lg-div"></div>' : ""}`).join("")}</div>`;
  };

  /* ---------- Registry ---------- */
  const LI = (o) => ({ subtitle: "", caption: "", status: "none", decorator: "none", ...o });
  const CP = (o) => ({ variant: "compact", subtitle: "", decorator: "compactLetter", status: "none", ...o });
  const compactRows = [
    ["Running, pulsing dot", CP({ title: "Keep split pane in step with the sidebar", pathPrimary: "opencode-mobile", pathSecondary: "803-compact-home-rows", status: "interactive", statusText: "Running", caption: "now", letter: "O", accent: "red", dot: "interactive", pulsing: true })],
    ["Unread, emphasized", CP({ title: "Bump Android to 1.2.0 and iOS to 1.1.0", emphasized: true, pathPrimary: "opencode-mobile", status: "success", statusText: "Done", caption: "3m ago", letter: "O", accent: "red", dot: "success" })],
    ["Long path, 40/60 split", CP({ title: "Retry the flaky upload", pathPrimary: "api-client-for-the-enterprise-billing-platform", pathSecondary: "ope-1042-show-worktree-and-project-on-home-rows", status: "warning", statusText: "Retrying · 2", caption: "12m ago", letter: "A", accent: "amber", dot: "warning" })],
    ["Failed", CP({ title: "Checking open PRs", emphasized: true, pathPrimary: "opencode-mobile", pathSecondary: "skill-image-duplicate-prompt", status: "error", statusText: "Failed", caption: "17h ago", letter: "O", accent: "red", dot: "error" })],
    ["Removed worktree", CP({ title: "Clean up the old branch", pathPrimary: "opencode-mobile", pathSecondary: "806-removed-worktree", pathRemoved: true, caption: "1d ago", letter: "O", accent: "red" })],
    ["Idle, no status", CP({ title: "Brewfile cleanup and remove casks that are no longer used", pathPrimary: "dotfiles", caption: "4d ago", letter: "D", accent: "teal" })],
    ["Selected", CP({ title: "Server search for session titles", selected: true, pathPrimary: "opencode-mobile", pathSecondary: "929-search", caption: "now", letter: "O", accent: "red", highlight: "search" })],
    ["Disabled", CP({ title: "Unavailable session", disabled: true, pathPrimary: "opencode-mobile", pathSecondary: "929-search", status: "success", statusText: "Done", caption: "now", letter: "O", accent: "red", dot: "success" })],
  ];
  const toneRows = ["interactive", "success", "warning", "error", "info", "stale"].map((tone) => [tone[0].toUpperCase() + tone.slice(1), LI({ title: "Material Design Icons", subtitle: "~/Projects/opencode-mobile", caption: "14m ago", status: tone, statusText: tone === "stale" ? "Running" : "Working · 14m", decorator: "letter", accent: "violet", dot: tone })]);

  Kit.define([
    {
      id: "list-item", doc: "list-item", align: "stretch",
      params: [text("title", "opencode-mobile"), text("subtitle", "Fix composer outline fade"), text("caption", "2m"), sel("variant", ["standard", "compact"]), sel("style", ["plain", "card"]), sel("decorator", ["letter", "letterWorktree", "compactLetter", "icon", "iconCircle", "none"]), sel("accent", Object.keys(ACCENTS)), sel("dot", TONE_KEYS, "none", "statusDot"), bool("pulsing"), sel("status", TONE_KEYS, "success"), text("statusText", "Ready"), sel("subtitleKind", ["plain", "diff", "branch", "off"], "plain", "subtitle kind"), sel("accessory", ["none", "disclosure", "toggle"]), bool("toggleOn", true), bool("chevron"), sel("textFlow", ["singleLine", "wrapped"]), text("titleTag", ""), bool("isNew"), text("highlight", ""), text("pathPrimary", ""), text("pathSecondary", ""), bool("pathRemoved"), bool("emphasized"), bool("footerSlot", false, "footer"), bool("selected"), bool("pressed"), bool("disabled")],
      states: [["Plain", {}], ["Card", { style: "card" }], ["Selected", { selected: true }], ["Pressed", { pressed: true }], ["Disabled", { disabled: true }], ["Status dot", { dot: "interactive", status: "none" }], ["Worktree", { decorator: "letterWorktree", dot: "interactive" }], ["Icon + chevron", { decorator: "icon", status: "none", caption: "", accessory: "disclosure" }], ["Icon circle", { decorator: "iconCircle", title: "All projects", subtitle: "8 projects", status: "none", caption: "" }], ["Toggle", { decorator: "none", status: "none", caption: "", style: "card", accessory: "toggle" }], ["Compact", compactRows[0][1]], ["Compact emphasized", compactRows[1][1]], ["Wrapped", { title: "Keep the current layout and move the settings into a separate screen", subtitle: "Every word of a long description stays readable, and the selection mark stays beside the first line of the title however tall the row grows.", textFlow: "wrapped", style: "card", decorator: "icon", icon: "radio_button_unchecked", status: "none", caption: "" }], ["Footer", { title: "Workstation", subtitle: "192.168.1.2:4096", caption: "", status: "interactive", statusText: "Active", style: "card", accent: "blue", letter: "W", footerSlot: true }]],
      sheet: [
        { title: "Style and state", kind: "stack", base: LI({ title: "Material Design Icons integration plan", subtitle: "http://127.0.0.1:4096/", caption: "sidebar-v2-concepts", status: "interactive", statusText: "Working · 14m", decorator: "letter" }), items: [["Plain", {}], ["Plain, selected (inset 8/2, radius 12)", { selected: true }], ["Plain, pressed", { pressed: true }], ["Plain, disabled", { disabled: true }]] },
        { title: "Card", kind: "stack", inset: true, base: LI({ title: "Material Design Icons integration plan", subtitle: "http://127.0.0.1:4096/", caption: "sidebar-v2-concepts", status: "interactive", statusText: "Working · 14m", decorator: "letter", style: "card" }), items: [["Card", {}], ["Card, selected", { selected: true }], ["Card, disabled", { disabled: true }]] },
        { title: "Start decorators", kind: "stack", base: LI({ subtitle: "~/Projects/opencode-mobile" }), items: [["None", { title: "No decorator" }], ["Letter", { title: "Letter badge", decorator: "letter" }], ["Letter + status dot", { title: "Status dot", decorator: "letter", accent: "teal", dot: "success" }], ["Letter + worktree", { title: "Worktree mark", decorator: "letterWorktree", accent: "amber" }], ["Letter + worktree + status", { title: "Merged status badge", decorator: "letterWorktree", accent: "blue", dot: "interactive" }], ["Icon", { title: "Folder", decorator: "icon" }], ["Icon circle", { title: "All projects", subtitle: "8 projects", decorator: "iconCircle" }]] },
        { title: "Letter accents", kind: "flow", base: LI({ title: "", subtitleKind: "off" }), items: [] },
        { title: "Status tones", kind: "stack", items: toneRows },
        { title: "Subtitle and detail", kind: "stack", base: LI({ title: "contact.html" }), items: [["Plain", { subtitle: "http://127.0.0.1:4096/" }], ["Diff segments", { subtitleKind: "diff" }], ["Folder + branch detail", { title: "Code samples request", subtitleKind: "branch", status: "success", statusText: "Done", caption: "1h ago", decorator: "letter", accent: "red", letter: "O" }], ["Title highlight", { title: "Search sessions on Home", highlight: "search", subtitleKind: "branch", status: "info", statusText: "Idle", caption: "2h ago", decorator: "letter", accent: "red", letter: "O" }]] },
        { title: "Compact", kind: "stack", items: compactRows },
        { title: "Title tag and New", kind: "stack", base: LI({ decorator: "icon" }), items: [["Title tag", { title: "opencode-mobile", subtitle: "On Home · 5 folders", titleTag: "git" }], ["Long title keeps its tag", { title: "opencode-mobile-357-split-view-chat-lifecycle-cache-android", subtitle: "Empty", titleTag: "git" }], ["New", { title: "What's new", icon: "star", isNew: true, accessory: "disclosure" }]] },
        { title: "Wrapped text", kind: "stack", inset: true, items: [["Wrapped", LI({ title: "Keep the current layout and move the settings into a separate screen", subtitle: "Every word of a long description stays readable, and the selection mark stays beside the first line of the title however tall the row grows.", style: "card", textFlow: "wrapped", decorator: "icon", icon: "radio_button_unchecked" })]] },
        { title: "Trailing", kind: "stack", inset: true, base: LI({ style: "card" }), items: [["Disclosure", { title: "Disclosure", subtitle: "Enabled", accessory: "disclosure" }], ["Disclosure, disabled", { title: "Disclosure", subtitle: "Disabled", accessory: "disclosure", disabled: true }], ["Toggle", { title: "Toggle", subtitle: "Enabled", accessory: "toggle" }], ["Toggle, disabled", { title: "Toggle", subtitle: "Disabled", accessory: "toggle", toggleOn: false, disabled: true }]] },
        { title: "Footer", kind: "stack", inset: true, items: [["Card with footer", LI({ title: "Workstation", subtitle: "192.168.1.2:4096", status: "interactive", statusText: "Active", style: "card", decorator: "letter", accent: "blue", letter: "W", footerSlot: true })]] },
      ],
      tokens: [["listItem.cornerRadius", 8], ["selection inset", "8 horizontal, 2 vertical"], ["selection radius", 12], ["padding", "16 horizontal, 12 vertical"], ["letter badge", "32 (tokens.md says 36)"], ["letter glyph", "14 bold rounded"], ["letter status dot", "10, ring 2 background-base"], ["worktree mark", "14 with 10 icon"], ["compact height", "iOS 66 fixed · Android 66 minimum"], ["compact badge", "32, glyph 13"], ["compact dot", "8, ring 2.5, pulse 1 → 0.3 over 2.4 s"], ["compact path", "iOS 13.5 · Android 13"], ["subtitle", "iOS 13 regular · Android 12 medium +0.5"], ["status pill", "iOS 13 semibold, 8/4 · Android 12 semibold, 8/3"], ["icon decorator", 24], ["icon circle", "32, glyph 16, text-strong 10%"], ["disclosure", 18], ["press scale", 0.965], ["context pop peak", 1.035], ["disabled", "iOS row 0.7 · Android decorator 0.7"]],
      source: { ios: "Components/List/OpenCodeListItem.swift", android: "ui/components/list/OpenCodeListItem.kt" },
      render: listItem,
    },
    {
      id: "list-group", doc: "list-group", align: "stretch", pad: true,
      params: [sel("rows", ["3", "2", "1", "0"])],
      states: [["Three rows", {}], ["Two rows", { rows: "2" }], ["One row", { rows: "1" }], ["Empty", { rows: "0" }]],
      sheet: [{ title: "Row count", kind: "stack", inset: true, items: [["Three rows", { rows: "3" }], ["Two rows", { rows: "2" }], ["One row, no divider", { rows: "1" }], ["Empty, no surface", { rows: "0" }]] }],
      tokens: [["listGroup.cornerRadius", 12], ["surface", "surface-base"], ["border", "1 border-weak-base"], ["divider", "1 border-weaker-base"]],
      source: { ios: "Components/List/OpenCodeListGroup.swift", android: "ui/components/list/OpenCodeListGroup.kt" },
      render: listGroup,
    },
    {
      id: "section-header", align: "stretch",
      params: [text("title", "Server"), sel("variant", ["label", "plain"]), sel("trailing", ["none", "count", "action"]), text("count", "3"), text("action", "Clear all"), sel("inset", ["standard", "flush"])],
      states: [["Label", {}], ["Plain with count", { title: "Sources/App/Features/SessionChat", variant: "plain", trailing: "count" }], ["Plain with action", { title: "3 waiting", variant: "plain", trailing: "action" }], ["Flush", { title: "Context breakdown", inset: "flush" }]],
      sheet: [
        { title: "Variant × trailing", kind: "stack", items: [["Label", { title: "Server" }], ["Label + count", { title: "Recent", trailing: "count" }], ["Label + action", { title: "Recent", trailing: "action" }], ["Plain", { title: "Sources/App/Features/SessionChat", variant: "plain" }], ["Plain + count", { title: "Sources/App/Features/SessionChat", variant: "plain", trailing: "count" }], ["Plain + action", { title: "3 waiting", variant: "plain", trailing: "action" }]] },
        { title: "Inset", kind: "stack", items: [["Standard (16)", { title: "Context breakdown" }], ["Flush (0)", { title: "Context breakdown", inset: "flush" }]] },
        { title: "Long path", kind: "stack", items: [["iOS cuts the middle, Android the end", { title: "Sources/App/Features/SessionChat/Timeline/Components/Rows/Generated", variant: "plain", trailing: "count" }]] },
      ],
      tokens: [["inset", "16 standard, 0 flush"], ["padding", "16 top, 4 bottom"], ["trailing spacing", 8], ["label tracking", 0.5], ["label", "iOS 13 semibold · Android 12 semibold"], ["plain", "13 regular, text-base"], ["count", "13, text-weak"], ["action", "13 medium, interactive"]],
      spec: "## Intent\nCaption-level title above a group of rows or a region, with an optional trailing count or action.\n\n## Public API\n- `variant`: `label` (uppercase, semibold, 0.5 tracking) or `plain` (kept as written, one line).\n- `trailing`: none, `count(Int)`, or `action(label, handler)`.\n- `inset`: `standard` (16) or `flush` (0).\n\n## Behavior\nThe title takes the remaining width and truncates. iOS cuts a plain title in the middle; Android cuts it at the end. With a count, screen readers hear the title and the item count as one element.",
      source: { ios: "Components/List/OpenCodeSectionHeader.swift", android: "ui/components/list/OpenCodeSectionHeader.kt" },
      render: sectionHeader,
    },
    {
      id: "key-value-row", align: "stretch", pad: true,
      params: [text("label", "Outcome"), text("value", "Highlighted"), sel("style", ["inline", "stacked"])],
      states: [["Inline", {}], ["Inline, short", { label: "Backend", value: "native" }], ["Stacked", { label: "Tokens", value: "12.4k", style: "stacked" }], ["Stacked, cost", { label: "Cost", value: "$0.02", style: "stacked" }]],
      sheet: [
        { title: "Inline", kind: "stack", inset: true, items: [["Outcome", { label: "Outcome", value: "Highlighted" }], ["Backend", { label: "Backend", value: "native" }], ["Long value wraps", { label: "Directory", value: "/Users/example/project/packages/mobile/src/features/session" }]] },
        { title: "Stacked", kind: "stack", inset: true, base: { style: "stacked" }, items: [["Tokens", { label: "Tokens", value: "12.4k" }], ["Cost", { label: "Cost", value: "$0.02" }], ["Two-line limit", { label: "Directory", value: "/Users/example/project/packages/mobile/src/features/session/timeline/components/rows" }]] },
      ],
      tokens: [["label", "13, text-base"], ["inline value", "13, text-strong"], ["stacked value", "iOS 16 · Android 14, text-strong"], ["stacked spacing", 6], ["inline minimum gap", "iOS 20 · Android 8"]],
      spec: "## Intent\nLabel and value pair for detail lines and stat cells.\n\n## Public API\n- `label`, `value` (verbatim data).\n- `style`: `inline` (one line) or `stacked` (label above value).\n\n## Behavior\nInline values align to the trailing edge and wrap. Stacked values show up to two lines; iOS shrinks them to 85% first and Android ends them with an ellipsis. Screen readers hear the pair as one element.",
      source: { ios: "Components/List/OpenCodeKeyValueRow.swift", android: "ui/components/list/OpenCodeKeyValueRow.kt" },
      render: keyValue,
    },
    {
      id: "charts", align: "stretch", pad: true,
      params: [sel("chart", ["band", "strip", "bar", "sparkline", "stat", "proportion", "footer"]), sel("tone", ["success", "warning", "error", "interactive"]), bool("gaps", true, "showsGaps"), sel("data", ["latency", "rising", "short"]), sel("height", ["40", "56"])],
      states: [["Band chart", { chart: "band" }], ["Status strip", { chart: "strip" }], ["Bar chart", { chart: "bar", tone: "warning" }], ["Sparkline", { chart: "sparkline" }], ["Sparkline, gaps hidden", { chart: "sparkline", tone: "warning", gaps: false }], ["Stat tiles", { chart: "stat" }], ["Proportion rows", { chart: "proportion" }], ["List Item footer", { chart: "footer" }]],
      sheet: [
        { title: "Sparkline", kind: "stack", inset: true, base: { chart: "sparkline" }, items: [["Success, with gaps", { tone: "success" }], ["Warning, gaps hidden", { tone: "warning", gaps: false }], ["Error, rising", { tone: "error", data: "rising" }], ["Interactive", { tone: "interactive", data: "short" }]] },
        { title: "Chart Card", kind: "stack", inset: true, items: [["Band chart", { chart: "band" }], ["Status strip", { chart: "strip" }], ["Bar chart", { chart: "bar", tone: "warning" }]] },
        { title: "Stat Tile", kind: "stack", inset: true, items: [["Label, value, detail", { chart: "stat" }]] },
        { title: "Proportion Row", kind: "stack", inset: true, items: [["Warning, error, info", { chart: "proportion" }]] },
        { title: "List Item footer", kind: "stack", inset: true, items: [["Sparkline in a card footer", { chart: "footer" }]] },
      ],
      tokens: [["line width", 1.5], ["sparkline fill", "iOS 28% · Android 22%"], ["sparkline last dot", "iOS 6 · Android 5"], ["gap tint", "error 14%"], ["band fill", "interactive 18%"], ["band warning / error", "12% / 16%"], ["midline dash", "iOS 3,3 · Android 4,4"], ["status strip height", 14], ["strip gap", "0.5 over 70 bars, else 1"], ["strip radius", "iOS 2 · Android 1.5"], ["bar width", "70% of slot"], ["card radius", "12, border-weak-base"], ["card padding", "iOS 14 · Android 14/12"], ["legend swatch", "iOS 10 · Android 8"], ["proportion track", "6 high"]],
      spec: "## Intent\nSmall read-only charts for connection health: sparkline, band chart, status strip, bar chart, chart card, stat tile, and proportion row.\n\n## Public API\n- **Sparkline:** `values` (nil breaks the line), `tone`, `showsGaps`.\n- **Band Chart:** points with `median`, `upper`, and a column `background` (none, warning, error, unobserved), plus `valueLabel`.\n- **Status Strip:** segments of success, warning, error, or unobserved.\n- **Bar Chart:** `values`, `tone`, `valueLabel`. A zero draws no bar.\n- **Chart Card:** `title`, `summary`, `axisLabels`, `legend`, and chart content.\n- **Stat Tile:** `label`, `value`, optional `detail`.\n- **Proportion Row:** `label`, `count`, `fraction`, `tone` (warning, error, info).\n\n## Behavior\nThe band axis runs from zero to a rounded maximum of at least 40. Missing values break the line. Charts have no touch interaction.",
      source: { ios: "Components/Chart/OpenCodeChartCard.swift", android: "ui/components/chart/OpenCodeChartCard.kt" },
      render: chartsRender,
    },
    {
      id: "feature-item", align: "stretch",
      params: [text("title", "Start a session as a draft"), text("body", "Write first, then pick the project and worktree. The session is created when you send."), sel("icon", ["edit", "star", "check"]), sel("tone", ["neutral", "success"])],
      states: [["Neutral", {}], ["Title only", { title: "Title only", body: "", icon: "star" }], ["Success", { title: "Steadier reconnects", body: "Session Chat picks up where it left off after the network drops.", icon: "check", tone: "success" }]],
      sheet: [{ title: "Tone and content", kind: "stack", items: [["Neutral", {}], ["Title only", { title: "Title only", body: "", icon: "star" }], ["Success", { title: "Steadier reconnects", body: "Session Chat picks up where it left off after the network drops.", icon: "check", tone: "success" }]] }],
      tokens: [["tile", "40, radius 12"], ["icon", 20], ["spacing", 14], ["vertical padding", 12], ["horizontal padding", "iOS 0 · Android 16"], ["title", "16 semibold"], ["body", "14, iOS text-base · Android text-weak"], ["success tile", "success 14%"]],
      spec: "## Intent\nRead-only row describing one feature or change, used by release notes.\n\n## Public API\n- `icon`, `title`, `body` (empty hides it).\n- `tone`: `neutral` (something added or improved) or `success` (something fixed).\n\n## Behavior\nThe row is static. The title and body wrap, and the icon tile stays at the top. Screen readers hear the title and body as one element and skip the icon.",
      source: { ios: "Components/List/OpenCodeFeatureItem.swift", android: "ui/components/list/OpenCodeFeatureItem.kt" },
      render: featureItem,
    },
    {
      id: "option-card", align: "stretch", pad: true,
      params: [text("title", "Activity Card"), text("subtitle", "Running steps on one live card that folds into a summary"), text("tag", "Recommended"), sel("tagTone", ["accent", "neutral"]), sel("preview", ["activity", "standard", "rail", "chips", "none"]), bool("selected", true), bool("disabled"), bool("group", false, "interactive group")],
      states: [["Selected", {}], ["Unselected", { selected: false, title: "Standard", subtitle: "Tool cards and groups between the agent's messages", tag: "Current", tagTone: "neutral", preview: "standard" }], ["Long title", { title: "Activity Card with a longer title", subtitle: "Running steps on one live card that folds into a summary when the turn ends", selected: false }], ["No preview", { title: "No preview", subtitle: "Text-only option", tag: "", preview: "none", selected: false }], ["Disabled", { title: "Chips", subtitle: "Tools as chips under the agent's narration", tag: "", preview: "chips", selected: false, disabled: true }], ["Selected, disabled", { title: "Selected and disabled", subtitle: "", tag: "", preview: "none", disabled: true }]],
      sheet: [
        { title: "Single choice (tap to select)", kind: "stack", inset: true, items: [["Group", { group: true }]] },
        { title: "Long title: the tag moves under it", kind: "stack", inset: true, items: [["Long title", { title: "Activity Card with a longer title", subtitle: "Running steps on one live card that folds into a summary when the turn ends", selected: false }]] },
        { title: "Without preview", kind: "stack", inset: true, base: { preview: "none", tag: "" }, items: [["Title only, selected", { title: "Title only", subtitle: "" }], ["Subtitle and tag", { title: "With subtitle and tag", subtitle: "A short description", tag: "Beta", tagTone: "neutral", selected: false }]] },
        { title: "Disabled", kind: "stack", inset: true, base: { disabled: true, tag: "" }, items: [["Disabled", { title: "Chips", subtitle: "Tools as chips under the agent's narration", preview: "chips", selected: false }], ["Selected and disabled", { title: "Selected and disabled", subtitle: "", preview: "none" }]] },
      ],
      tokens: [["cornerRadius", 14], ["minHeight", 72], ["padding", 12], ["gap", 12], ["radio", "20, ring 2, dot 10"], ["preview", "92 × 56, radius 10"], ["border", "1 border-weak-base, selected 2 interactive"], ["selected fill", "surface-interactive-weak"], ["title", "iOS 16 medium · Android 16 semibold, 2 lines"], ["subtitle", "13, text-weak, 3 lines"], ["disabled", 0.5]],
      spec: "## Intent\nOne choice in a single-choice list whose options each carry a short description and a preview.\n\n## Public API\n- `title`, `subtitle`, `tag`, `tagTone`, `preview` (none or an illustration), `selected`, `enabled`, `action`.\n\n## Behavior\nThe tag sits on the title line when both fit and moves under the title otherwise. The subtitle shows up to three lines. Stack cards 8 apart. Screen readers hear title, subtitle, then tag.",
      source: { ios: "Components/List/OpenCodeOptionCard.swift", android: "ui/components/list/OpenCodeOptionCard.kt" },
      render: (p, c) => (p.group ? `<div class="c-opt-group" data-opt-group>${OPTION_SET.map(([title, subtitle, tag, tagTone, preview], i) => optionCard({ ...p, group: false, title, subtitle, tag, tagTone, preview, selected: i === 0, disabled: false }, c)).join("")}</div>` : optionCard(p, c)),
    },
    {
      id: "selectable-card", align: "stretch", pad: true,
      params: [text("title", "Porcelain"), text("blurb", "Warm paper and ink."), text("previewText", "Preview"), bool("selected"), bool("group", false, "interactive group")],
      states: [["Unselected", {}], ["Selected", { selected: true }]],
      sheet: [
        { title: "State", kind: "stack", inset: true, items: [["Unselected", {}], ["Selected", { selected: true }]] },
        { title: "Tap to select", kind: "stack", inset: true, items: [["Group", { group: true }]] },
      ],
      tokens: [["cornerRadius", 12], ["padding", 12], ["content spacing", 10], ["header spacing", 8], ["surface", "surface-raised-base"], ["selected border", "2 interactive"], ["check", "18 interactive"], ["title", "16 medium"], ["blurb", "13 text-base"]],
      spec: "## Intent\nRaised card with a title, blurb, selected check, and a preview slot. Used by the theme pickers.\n\n## Public API\n- `title`, `blurb`, `selected`, `action`, and `preview` content.\n\n## Behavior\nSelecting adds a 2 interactive border and a trailing check. The card has no disabled state on either platform.",
      source: { ios: "Components/List/OpenCodeSelectableCard.swift", android: "ui/components/list/OpenCodeSelectableCard.kt" },
      render: (p, c) => (p.group ? `<div class="c-sc-group" data-sel-group>${[["Porcelain", "Warm paper and ink.", true], ["Linen", "Soft woven neutrals.", false]].map(([title, blurb, selected]) => selectableCard({ ...p, title, blurb, selected }, c)).join("")}</div>` : selectableCard(p, c)),
    },
    {
      id: "tree-row", align: "stretch",
      params: [text("title", "ContentView.swift"), text("subtitle", ""), text("path", ""), sel("kind", ["file", "collapsed", "expanded"]), sel("icon", ["generic", "fileType"]), sel("depth", ["0", "1", "2", "3"]), sel("state", ["normal", "selected", "loading"]), bool("dimmed"), text("tag", ""), bool("demo", false, "interactive tree")],
      states: [["Normal", {}], ["Selected", { state: "selected" }], ["Loading", { title: "Sources", kind: "expanded", state: "loading" }], ["Dimmed + tag", { title: "node_modules", kind: "collapsed", dimmed: true, tag: "ignored" }], ["With subtitle", { title: "FilesView.swift", subtitle: "ios/OpenCode Mobile/UI/Files" }], ["File-type icon", { title: "package.json", icon: "fileType" }], ["Depth 2", { depth: "2" }]],
      sheet: [
        { title: "Interactive, file-type icons", kind: "stack", items: [["Tap folders and files", { demo: true }]] },
        { title: "States, generic icons", kind: "stack", items: [["Normal", { title: "Normal" }], ["Selected", { title: "Selected", state: "selected" }], ["Loading", { title: "Loading", kind: "expanded", state: "loading" }], ["Dimmed + tag", { title: "node_modules", kind: "collapsed", dimmed: true, tag: "ignored" }], ["Subtitle", { title: "FilesView.swift", subtitle: "ios/OpenCode Mobile/UI/Files" }]] },
        { title: "Depth", kind: "stack", base: { kind: "collapsed", title: "Sources" }, items: [["0", { depth: "0" }], ["1", { depth: "1" }], ["2", { depth: "2" }], ["3", { depth: "3" }]] },
      ],
      tokens: [["indent per depth", 16], ["min height", "iOS 40 · Android 36, 48 with subtitle"], ["leading inset", "iOS 12 · Android 8 outside the row"], ["disclosure slot", "iOS 16 · Android 20"], ["chevron", "16, rotates 90°"], ["icon", 18], ["spacing", 6], ["selected radius", "iOS 6 · Android 8"], ["dimmed", "iOS 0.55 · Android 0.5"], ["title", "14, text-strong"], ["subtitle", "13, iOS text-weak · Android text-weaker"]],
      spec: "## Intent\nOne row of a file tree: depth indent, disclosure, leading icon, title, optional subtitle, and a small trailing tag.\n\n## Public API\n- `title`, `subtitle`, `depth`, `tag`.\n- Kind: file, or folder with an expanded state.\n- Icon: a generic catalog glyph or a colour file-type icon.\n- State: normal, selected, loading. iOS has a separate `isDimmed` flag; Android models dimmed as a state.\n\n## Behavior\nFolders rotate the chevron 90° when expanded. Loading adds a small trailing spinner. iOS cuts the title in the middle and the subtitle at the head; Android cuts both at the end.",
      source: { ios: "Components/List/OpenCodeTreeRow.swift", android: "ui/components/list/OpenCodeTreeRow.kt" },
      render: (p, c) => (p.demo ? treeDemo(c) : treeRow(p, c)),
    },
    {
      id: "reorderable-list", align: "stretch",
      params: [bool("reorder", true, "Reorder"), bool("contextAction", true, "Context action"), bool("handles", false, "Drag handles"), sel("state", ["rest", "pressed", "lifted", "swiped"], "rest", "row 2 state")],
      states: [["Long-press to lift", {}], ["Drag handles", { handles: true }], ["Pressed row", { state: "pressed" }], ["Lifted row", { state: "lifted" }], ["Swiped open", { state: "swiped" }], ["Reorder off", { reorder: false }], ["Context action off", { contextAction: false }]],
      sheet: [
        { title: "Try it: long-press and drag, or swipe left", kind: "stack", items: [["Interactive", {}]] },
        { title: "Drag handles", kind: "stack", items: [["Handles drag at once", { handles: true }]] },
        { title: "Row states", kind: "stack", items: [["Pressed", { state: "pressed" }], ["Lifted", { state: "lifted" }], ["Swiped open", { state: "swiped" }]] },
      ],
      tokens: [["row radius", 8], ["pressed scale", 0.965], ["lifted scale", 1.03], ["lift shadow", "iOS black 18%, radius 14, y 6 · Android elevation 8"], ["long press", "iOS 0.42 s · Android system timeout"], ["swipe reveal", 88], ["swipe open threshold", 44], ["swipe activation", "iOS 14 · Android touch slop"], ["handle", "iOS 18 in 28 + 8 inset · Android 20 in 40"], ["Remove label", "iOS surface-base · Android white"]],
      spec: "## Intent\nStacked rows that reorder with long-press and drag, and expose one secondary action behind a horizontal swipe.\n\n## Public API\n- `items`, `canMove`, `onMove`, `onSelect`, `onContextAction`, `supportsContextAction`, `contextActionLabel`.\n- iOS: `activation` (`longPress` or `handle`) and a `trailing` slot.\n- Android: rows place `DragHandle` themselves.\n\n## Behavior\nTap selects. Long-press lifts a movable row, neighbors shift live, and release commits the move. Swiping left reveals an 88-wide action, which stays open past 44. One row stays open at a time. Pinned rows never lift or get crossed. Screen readers get Move up, Move down, and the context action.",
      source: { ios: "Components/List/OpenCodeReorderableList.swift", android: "ui/components/list/OpenCodeReorderableList.kt" },
      render: reorderable,
    },
    {
      id: "picker-card", align: "center", pad: true,
      params: [text("caption", "Run session in"), sel("rows", ["local", "worktree", "notgit"]), text("footnote", "/Users/example/project"), bool("footMono", true, "footnoteMonospace"), bool("compact")],
      states: [["Local", {}], ["New worktree, compact", { rows: "worktree", footnote: "Created when you send", footMono: false, compact: true }], ["Disabled row", { rows: "notgit", footnote: "" }]],
      sheet: [{ title: "Gallery states", kind: "stack", inset: true, items: [["Local", {}], ["New worktree, compact", { rows: "worktree", footnote: "Created when you send", footMono: false, compact: true }], ["New worktree, regular", { rows: "worktree", footnote: "Created when you send", footMono: false }], ["Disabled row, no footnote", { rows: "notgit", footnote: "" }]] }],
      tokens: [["max width", 340], ["card radius", 16], ["tile", "40 radius 12, compact 34 radius 10"], ["row padding", "14 horizontal, 12 vertical, compact 8"], ["tile gap", 12], ["tile icon", "20, compact 18"], ["chevron", "18 icon-base"], ["caption", "12 medium, +0.8, centered"], ["label", "12 text-weak"], ["value", "14 semibold"], ["disabled row", "0.55"]],
      spec: "## Intent\nA short stack of choices that set up what comes next, such as where a new session runs. This is the Android counterpart of iOS Run Session In Card.\n\n## Public API\n- `rows`: label, value, tile (letter or icon, optionally highlighted), `monospaceValue`, `enabled`, `onClick`.\n- `caption`, `footnote`, `footnoteMonospace`, `compact`.\n\n## Behavior\nA row with a handler shows a chevron and opens its picker. Without one it is read-only. Compact tightens the rows and hides the caption while the keyboard is up.",
      source: { android: "ui/components/list/OpenCodePickerCard.kt" },
      render: (p, c) => pickerCard(p, c, true),
    },
    {
      id: "run-session-in-card", align: "center", pad: true,
      params: [text("caption", "Run session in"), sel("rows", ["worktree", "notgit"]), text("footnote", "Created when you send"), bool("compact", false, "Keyboard up (compact)")],
      states: [["New worktree", {}], ["Keyboard up", { compact: true }], ["Not a Git repo", { rows: "notgit", footnote: "/Users/example/notes" }], ["Not a Git repo, keyboard up", { rows: "notgit", footnote: "/Users/example/notes", compact: true }]],
      sheet: [{ title: "Gallery states", kind: "stack", inset: true, items: [["New worktree", {}], ["Keyboard up (compact)", { compact: true }], ["Not a Git repo", { rows: "notgit", footnote: "/Users/example/notes" }], ["Not a Git repo, keyboard up", { rows: "notgit", footnote: "/Users/example/notes", compact: true }]] }],
      tokens: [["max width", 340], ["card radius", 18], ["tile", "40 radius 12, compact 34 radius 10"], ["row padding", "14 horizontal, 12 vertical, compact 7"], ["row spacing", 12], ["tile icon", "20, compact 17"], ["letter", "17 bold rounded, compact 15"], ["chevron", "16 icon-weak-base"], ["caption", "13 semibold, +0.5, leading"], ["label", "13 text-weak"], ["value", "15 semibold"], ["footnote", "12 mono, text-weaker"], ["compact animation", "ease-in-out 0.2 s"]],
      spec: "## Intent\nShows where a New session will run: a caption, one card of tappable rows with inset dividers, and a muted checkout path. This is the iOS counterpart of Android Picker Card.\n\n## Public API\n- `caption`, `entries` (id, row, action), `footnote`, `compact`.\n- Row: tile (letter or icon), `label`, `value`, `monospacedValue`, `highlighted`, `enabled`.\n\n## Behavior\nEnabled rows show a chevron. Disabled rows are greyed and take no tap. Compact hides the caption and tightens the rows while the keyboard is up.",
      source: { ios: "Components/List/RunSessionInCard.swift" },
      render: (p, c) => pickerCard(p, c, false),
      mount: mountAll,
    },
  ]);

  // Letter accents overview: one badge per native accent.
  const li = Kit.components["list-item"], accentSheet = li.sheet.find((s) => s.title === "Letter accents");
  accentSheet.kind = "stack"; accentSheet.base = LI({ subtitle: "~/Projects/opencode-mobile", decorator: "letter" });
  accentSheet.items = Object.keys(ACCENTS).map((a) => [a[0].toUpperCase() + a.slice(1), { title: a[0].toUpperCase() + a.slice(1), accent: a }]);

})();
