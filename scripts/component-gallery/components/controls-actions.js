// Component group: controls-actions. Compose other groups with Kit.use(id, props).
(() => {
  const { esc, I, sel, text, bool, ACCENTS } = Kit;
  const cx = (...a) => a.filter(Boolean).join(" ");
  const isAndroid = (ctx) => (ctx ?? Kit.ctx).platform === "android";
  const iosOnly = () => `<span class="c-na">iOS only</span>`;
  const once = (root, q, fn) => root.querySelectorAll(q).forEach((el) => { if (el.dataset.caBound) return; el.dataset.caBound = "1"; fn(el); });

  /* ---------- Shared popup, standing in for the anchored overflow menu ---------- */
  let openMenu = null;
  const closeMenu = () => { openMenu?.remove(); openMenu = null; };
  document.addEventListener("pointerdown", (e) => { if (openMenu && !openMenu.contains(e.target)) closeMenu(); }, true);
  const popMenu = (anchor, entries) => {
    closeMenu();
    const m = document.createElement("div");
    m.className = "c-pop float";
    m.innerHTML = entries.map((x, i) => `<button class="c-pop-i press" data-i="${i}">${x.icon ? I(x.icon, 18) : `<span class="c-pop-sp"></span>`}<span class="c-pop-t"><b>${esc(x.title)}</b>${x.subtitle ? `<small>${esc(x.subtitle)}</small>` : ""}</span></button>`).join("");
    document.body.append(m);
    const r = anchor.getBoundingClientRect(), w = m.offsetWidth, h = m.offsetHeight;
    m.style.left = Math.max(8, Math.min(innerWidth - w - 8, r.left)) + "px";
    m.style.top = (r.bottom + h + 6 > innerHeight ? Math.max(8, r.top - h - 6) : r.bottom + 6) + "px";
    m.addEventListener("click", (e) => { const b = e.target.closest("[data-i]"); if (!b) return; entries[+b.dataset.i].pick?.(); closeMenu(); });
    openMenu = m;
  };

  /* ---------- Button ---------- */
  const button = (p, ctx = Kit.ctx) => {
    if (p.contrast === "subtle" && isAndroid(ctx)) return iosOnly();
    const v = p.variant, dis = p.disabled ? " disabled" : "";
    if (v === "link") return `<button class="${cx("c-link", isAndroid(ctx) && "press", p.pressed && "is-pressed")}"${dis}>${esc(p.title)}</button>`;
    const square = v === "iconSquare", circle = v === "iconCircle" || v === "iconCircleText";
    const cls = cx("btn press", p.contrast, p.size === "large" && "large", v === "fullWidthText" && "full", square && "square", circle && "circle", p.pressed && "is-pressed", p.focused && "is-focused", p.segment === "leading" && "seg-l", p.segment === "trailing" && "seg-r");
    const inner = square ? I(p.icon, p.segment === "trailing" ? 16 : 18) : v === "iconCircle" ? I(p.icon, 18) : v === "iconCircleText" ? `<span class="c-glyph">${esc(p.glyph ?? "/")}</span>` : `<span class="c-btn-t">${esc(p.title)}</span>`;
    return `<button class="${cls}"${dis}>${inner}</button>`;
  };

  const split = (p, ctx = Kit.ctx) => {
    if (p.contrast === "subtle" && isAndroid(ctx)) return iosOnly();
    const base = { contrast: p.contrast, disabled: p.disabled, size: "medium" };
    return `<span class="c-split" data-split>${button({ ...base, variant: "compactText", title: p.title, segment: "leading", pressed: p.pressed === "label" }, ctx)}${button({ ...base, variant: "iconSquare", icon: "keyboard_arrow_down", segment: "trailing", pressed: p.pressed === "menu" }, ctx)}</span>`;
  };

  /* ---------- Floating pill chrome ---------- */
  const hex = (s) => { const m = /^#([0-9a-f]{6})/i.exec(s ?? ""); return m ? [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16) / 255) : null; };
  const lum = (c) => { const l = c.map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)); return 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]; };
  // OpenCodeFloatingPillStyle.liftedFill: lift toward white only when that separates the pill from the page.
  const pillFill = (ctx = Kit.ctx) => {
    const raised = hex(ctx.tokens?.["surface-raised-base"]), page = hex(ctx.tokens?.["background-base"]);
    if (!raised || !page) return "var(--surface-raised-base)";
    const lift = ctx.dark ? 0.1 : 1, lifted = raised.map((v) => v + (1 - v) * lift);
    return Math.abs(lum(lifted) - lum(page)) >= Math.abs(lum(raised) - lum(page)) ? `color-mix(in oklab, var(--surface-raised-base), white ${lift * 100}%)` : "var(--surface-raised-base)";
  };
  const chromeStyle = (ctx) => `style="--fp-fill:${pillFill(ctx)}"`;

  const actionPill = (p, ctx = Kit.ctx) => {
    if (p.kind === "expandPill") {
      if (isAndroid(ctx)) return iosOnly();
      return `<button class="${cx("c-expand", p.expanded && "open", p.pressed && "is-pressed")}" data-expand aria-expanded="${!!p.expanded}"${p.disabled ? " disabled" : ""}><span>${esc(p.title)}</span>${I("keyboard_arrow_down", 14)}</button>`;
    }
    return `<button class="${cx("apill float c-fp-chrome press", p.pressed && "is-pressed")}" ${chromeStyle(ctx)}${p.disabled ? " disabled" : ""}>${I("arrow_downward", 16)}<span>Scroll to end</span></button>`;
  };

  /* ---------- Action tile ---------- */
  const tileBadge = (p) => {
    if (!p.badge || p.badge === "none") return "";
    const n = p.count === "" || p.count == null ? null : +p.count;
    return n == null ? `<span class="c-tile-dot ${p.badge}"></span>` : `<span class="c-tile-count ${p.badge}">${n > 99 ? "99+" : n}</span>`;
  };
  const tile = (p) => `<button class="${cx("c-tile press", p.pressed && "is-pressed")}"${p.disabled ? " disabled" : ""}><span class="c-tile-b">${I(p.icon, 24)}<span class="c-tile-l">${esc(p.label)}</span></span>${tileBadge(p)}</button>`;
  const actionTile = (p) => (p.grid ? `<div class="c-tile-grid" style="--cols:${p.grid.length}">${p.grid.map((x) => tile({ ...p, grid: null, ...x })).join("")}</div>` : `<div class="c-tile-one">${tile(p)}</div>`);

  /* ---------- Floating pill ---------- */
  const FP = {
    iconLabel: { label: "+128 −14", icon: "call_split" },
    iconOnly: { label: "Scroll to end", icon: "arrow_downward", round: true },
    counter: { label: "1 subagent · 2 shells", counter: true },
    counterWaiting: { label: "general needs input · 2 running", counter: true, waiting: true },
    closable: { label: "Request failed", close: true },
    label: { label: "+3" },
    summary: { label: "Activity", round: true, summary: true },
  };
  const spinner = `<svg class="c-spin" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="6"/></svg>`;
  const markHtml = (m) => {
    if (!m) return "";
    if (m.t === "icon") return I(m.n, 16);
    if (m.t === "spinner") return spinner;
    if (m.t === "pulse") return `<span class="c-fp-dotbox"><span class="c-fp-dot pulse"></span></span>`;
    if (m.t === "glyph") return `<span class="c-fp-glyph">${esc(m.v)}</span>`;
    if (m.t === "check") return I("check", 16);
    return `<span class="c-fp-dotbox"><span class="c-fp-dot"></span></span>`;
  };
  const leading = (l, state, pending) => {
    if (state === "done") return { t: "check" };
    if (l.summary) return { needsAttention: { t: "icon", n: "arrow_upward" }, error: { t: "glyph", v: "!" }, ready: pending ? { t: "glyph", v: "⋯" } : { t: "icon", n: "check" }, live: { t: "spinner" } }[state];
    if (l.icon) return { t: "icon", n: l.icon };
    if (l.counter) { if (state === "live") return { t: "spinner" }; if (state === "needsAttention") return l.waiting ? { t: "pulse" } : { t: "spinner" }; }
    return null;
  };
  const TONE = { live: "t-base", ready: "t-strong", done: "t-strong", needsAttention: "t-warn", error: "t-err" };
  const pillShell = ({ cls, ctx, action, inner, close, badge, ring }) => {
    const body = action ? `<button class="c-fp-b press">${inner}</button>` : `<span class="c-fp-b">${inner}</span>`;
    return `<span class="${cx("c-fp float c-fp-chrome", cls, ring && "ring")}" ${chromeStyle(ctx)} data-fp>${body}${close ? `<button class="c-fp-x" aria-label="Dismiss">${I("close", 16)}</button>` : ""}${badge ?? ""}</span>`;
  };
  const fpBadge = (n, hidden) => (n > 1 ? `<span class="${cx("c-fp-badge", hidden && "fold-only")}">${n}</span>` : "");

  const pill = (p, ctx = Kit.ctx) => {
    const android = isAndroid(ctx), l = p.icon ? { ...FP[p.layout], icon: p.icon } : FP[p.layout];
    if (l.summary && android) return iosOnly();
    const state = p.state, compact = !!p.compact, count = +p.count || 0, label = p.label || l.label;
    const full = leading(l, state, p.pending);
    // iOS draws a stack's summary mark when compact. Android keeps the pill's own mark.
    const compactMark = !android && count > 1 && !l.summary ? leading(FP.summary, state, p.pending) : full ?? { t: "dot" };
    const badge = l.summary || compact || android ? count : 0;
    const tone = TONE[state], ring = state === "needsAttention", action = p.action !== false;
    if (l.round) return pillShell({ cls: cx("round", tone), ctx, action, ring, inner: `<span class="c-fp-m">${markHtml(full)}</span>`, badge: fpBadge(badge) });
    const slot = compact ? compactMark : full ?? compactMark;
    const inner = `<span class="${cx("c-fp-m", !full && "no-full")}">${markHtml(slot)}</span><span class="c-fp-l"><span class="${cx("c-fp-lt", full && "gap")}">${esc(label)}</span></span>`;
    return pillShell({ cls: cx(tone, compact && "is-compact"), ctx, action, ring, inner, close: l.close && p.closeAction !== false, badge: fpBadge(badge) });
  };

  const PHASE = { working: "Working", attentionUp: "Review permission", attentionDot: "Waiting for you", failed: "Request failed", done: "Done" };
  const turn = (p, ctx = Kit.ctx) => {
    const android = isAndroid(ctx), ph = p.phase ?? "working", folds = ph === "working" || ph === "done";
    // iOS forces compact off for attention and failed. Android honours the argument.
    const compact = !!p.compact && (android || folds);
    const label = p.label || PHASE[ph];
    const mark = ph === "done" ? I("check", 16) : ph === "working" ? spinner : ph === "attentionUp" ? I("arrow_upward", 16) : ph === "attentionDot" ? `<span class="c-fp-dotbox"><span class="c-fp-dot pulse"></span></span>` : compact ? spinner : "";
    const tone = { working: "t-base", done: "t-strong", failed: "t-err" }[ph] ?? "t-warn";
    const inner = `<span class="${cx("c-fp-m", ph === "failed" && !compact && "no-full")}">${mark}</span><span class="c-fp-l"><span class="${cx("c-fp-lt", ph !== "failed" && "gap")}">${esc(label)}</span></span>`;
    return pillShell({ cls: cx(tone, compact && "is-compact"), ctx, action: p.action !== false && ph !== "working" && ph !== "done", ring: ph.startsWith("attention"), inner, close: ph === "failed" });
  };

  const turnDemo = (ctx) => {
    const one = (compact) => `<span class="${cx("c-fp float c-fp-chrome t-base c-turn", compact && "is-compact")}" ${chromeStyle(ctx)} data-fp><span class="c-fp-b"><span class="c-fp-m c-turn-m"><span class="c-turn-sp">${spinner}</span><span class="c-turn-ck">${I("check", 16)}</span></span><span class="c-fp-l"><span class="c-fp-lt gap c-turn-ls"><span class="c-turn-a">Working</span><span class="c-turn-b">Done</span></span></span></span></span>`;
    return `<div class="c-demo" data-turn-demo><div class="c-demo-r">${one(false)}${one(true)}</div>${Kit.use("choice-badge", { label: "Finish turn", selected: false }, ctx)}</div>`;
  };
  const foldDemo = (ctx) => `<div class="c-demo" data-fold-demo><div class="c-demo-r">${pill({ layout: "closable", state: "error", count: 3, action: true }, ctx).replace('data-fp>', 'data-fp data-fold-badge="3">')}${pill({ layout: "counter", state: "live", label: "Syncing", action: false }, ctx)}</div>${Kit.use("choice-badge", { label: "Compact", selected: false }, ctx)}</div>`;

  const floatingPill = (p, ctx = Kit.ctx) => {
    if (p.demo === "turn") return turnDemo(ctx);
    if (p.demo === "fold") return foldDemo(ctx);
    return p.layout === "turn" ? turn(p, ctx) : pill(p, ctx);
  };

  /* ---------- Menu chip ---------- */
  const LETTERS = { ...ACCENTS, red: ["#5c2d2d", "#ffc8c8", "#4a2424", "#ffb8b8"], indigo: ["#2d3d5c", "#b8c8ff", "#24304a", "#a8b8ff"] };
  const menuChip = (p, ctx = Kit.ctx) => {
    const a = LETTERS[p.accent] ?? LETTERS.teal, [f, t] = ctx.dark ? [a[2], a[3]] : [a[0], a[1]];
    const size = isAndroid(ctx) ? 16 : 14;
    const lead = p.leading === "icon" ? I(p.icon, size) : p.leading === "letter" ? `<span class="c-chip-letter" style="background:${f};color:${t}">${esc((p.title.trim()[0] ?? "?").toUpperCase())}</span>` : "";
    return `<button class="${cx("c-chip press", p.chip === "toggle" ? "toggle" : "menu", p.selected && "sel", p.pressed && "is-pressed")}" data-chip="${p.chip}"${p.disabled ? " disabled" : ""}>${lead}<span class="c-chip-t">${esc(p.title)}</span>${p.chip === "menu" ? I("keyboard_arrow_down", size) : ""}</button>`;
  };

  /* ---------- Choice badge ---------- */
  const badge = (p) => `<button class="${cx("badge press", p.variant === "glass" && "glass", p.selected && "sel", p.pressed && "is-pressed")}"${p.disabled ? " disabled" : ""}>${esc(p.label)}</button>`;

  /* ---------- Filter tabs ---------- */
  const SCROLL_TABS = [["all", "All", 5], ["friends", "Friends", null], ["unread", "Unread", 45], ["news", "News", 10], ["mobile", "opencode-mobile", 3]];
  const SEG_TABS = [["running", "Running", 2, false], ["done", "Done", 5, true]];
  const filterTabs = (p) => {
    if (p.style === "segmented") {
      const cur = p.segment ?? "running";
      return `<div class="c-seg-wrap"><div class="c-ftseg" data-ftabs>${SEG_TABS.map(([id, t, n, unseen]) => `<button class="${cx("c-ftseg-i press", cur === id && "sel")}" data-tab="${id}"><span class="c-ft-t">${t}</span>${p.counts ? `<span class="c-ftseg-n">${n}</span>` : ""}${unseen && p.unseen ? `<span class="c-unseen"></span>` : ""}</button>`).join("")}</div></div>`;
    }
    return `<div class="ftabs" data-ftabs>${SCROLL_TABS.map(([id, t, n]) => `<button class="${cx("ftab press", p.selected === id && "sel")}" data-tab="${id}"><span class="c-ft-t">${t}</span>${p.counts && n != null ? `<b>${n}</b>` : ""}${p.unseen && id === "news" ? `<span class="c-unseen"></span>` : ""}</button>`).join("")}</div>`;
  };

  /* ---------- Tag ---------- */
  const tag = (p, ctx = Kit.ctx) => ((p.tone === "warning" || p.tone === "success") && isAndroid(ctx) ? iosOnly() : `<span class="c-tag ${p.size} ${p.tone}">${esc(p.title)}</span>`);

  /* ---------- Segmented control ---------- */
  const MODES = [["system", "System", "Follows the phone's light or dark setting."], ["light", "Light", "Always light, whatever the phone is set to."], ["dark", "Dark", "Always dark, whatever the phone is set to."]];
  const segmented = (p) => {
    const i = Math.max(0, MODES.findIndex(([id]) => id === p.selection));
    return `<div class="c-segctl-wrap" data-segctl><div class="c-segctl" style="--n:${MODES.length};--i:${i}"><span class="c-segctl-th"></span>${MODES.map(([id, t], x) => `<button class="${cx("c-segctl-i", x === i && "sel")}" data-i="${x}" data-mode="${id}">${t}</button>`).join("")}</div>${p.note ? `<div class="c-segctl-n">${MODES[i][2]}</div>` : ""}</div>`;
  };

  /* ---------- Divergences: one source for the Parity tab and divergences/controls-actions.md ---------- */
  const iP = (v, f, ...l) => `${v} (${l.map((x) => `\`ios/OpenCode Mobile/OpenCode Mobile/${f}:${x}\``).join(", ")})`;
  const aP = (v, f, ...l) => `${v} (${l.map((x) => `\`android/app/src/main/java/dev/opencode/mobile/${f}:${x}\``).join(", ")})`;
  const BTN_I = "Components/Input/OpenCodeButton.swift", BTN_A = "ui/components/input/OpenCodeButton.kt";
  const TILE_I = "Components/Input/OpenCodeActionTile.swift", TILE_A = "ui/components/input/OpenCodeActionTile.kt";
  const FP_I = "Components/Chrome/OpenCodeFloatingPill.swift", FP_A = "ui/components/chrome/OpenCodeFloatingPill.kt";
  const TURN_I = "Components/Chrome/OpenCodeTurnStatusPill.swift", TURN_A = "ui/components/chrome/OpenCodeTurnStatusPill.kt";
  const CHIP_I = "Components/Input/OpenCodeMenuChip.swift", CHIP_A = "ui/components/input/OpenCodeMenuChip.kt";
  const CB_I = "Components/List/OpenCodeChoiceBadge.swift", CB_A = "ui/components/list/OpenCodeChoiceBadge.kt";
  const FT_I = "Components/List/OpenCodeFilterTabs.swift", FT_A = "ui/components/list/OpenCodeFilterTabs.kt";
  const TAG_I = "Components/Feedback/OpenCodeTag.swift", TAG_A = "ui/components/feedback/OpenCodeTag.kt";
  const SEG_I = "Components/Input/OpenCodeSegmentedControl.swift";
  const ND = "Not documented";
  const D = (severity, title, type, ios, android, docs, prototype, why) => ({ severity, title, type, ios, android, docs, prototype, why });
  const DV = {
    button: [
      D("medium", "Destructive label color differs", "iOS vs Android", iP("`surface-base`, which is dark in dark themes", BTN_I, 367), aP("`onError`, fixed white", BTN_A, 326), "Says only \"contrasting label text\"", "Each platform's real label color", "The same destructive button has dark text on iOS and white text on Android in dark mode."),
      D("medium", "Subtle contrast exists only on iOS", "Platform-only", iP("`.subtle` well for floating chrome", BTN_I, 18), aP("Four contrasts, no Subtle", BTN_A, 84), "`docs/design-system/components/button.md:21` lists four contrasts and omits Subtle", "Subtle on iOS, an \"iOS only\" marker on Android", "Composer and dock controls cannot share one contrast across platforms, and the doc is out of date."),
      D("medium", "Link button type size differs", "iOS vs Android", iP("`body`, 16 pt semibold", BTN_I, 106, 107), aP("`bodyMedium`, 14 sp semibold", BTN_A, 115), ND, "16 on iOS, 14 on Android", "The same inline link reads visibly larger on iOS."),
      D("low", "Icon circle glyph type differs", "iOS vs Android", iP("18 pt semibold", BTN_I, 174), aP("`titleMedium`, 16 sp medium", BTN_A, 279), ND, "Each platform's glyph size and weight", "The composer's `/` glyph has a different size and weight per platform."),
    ],
    "split-button": [
      D("low", "Menu half width differs", "iOS vs Android", iP("32 pt", BTN_I, 39), aP("30 dp", "ui/components/input/OpenCodeSplitButton.kt", 41), ND, "32 on iOS, 30 on Android", "The menu half has a slightly different width and tap area with no recorded reason."),
    ],
    "action-pill": [
      D("medium", "Disabled treatment differs", "iOS vs Android", iP("Whole pill at 0.72 opacity", "Components/Chrome/OpenCodeFloatingPillChrome.swift", 20, 33), aP("Fill at 0.7 alpha and shadow removed, label stays opaque", "ui/components/input/OpenCodeActionPill.kt", 69, 73), ND, "Each platform's disabled recipe", "A disabled pill fades its label on iOS but not on Android."),
      D("medium", "Expand Pill exists only on iOS", "Platform-only", iP("`OpenCodeExpandPill`, shown in the Action Pill gallery", "Components/Input/OpenCodeExpandPill.swift", 6), "No matching component under `android/app/src/main/java`", ND, "Rendered on iOS, an \"iOS only\" marker on Android", "Nested rows such as worktrees have no shared expand control on Android."),
    ],
    "action-tile": [
      D("high", "Android tile has no button role", "iOS vs Android", iP("A SwiftUI `Button`, announced as a button", TILE_I, 48), aP("`clickable` with no `role`", TILE_A, 116), ND, "Not visible; recorded here", "TalkBack does not announce the tile as a button."),
      D("medium", "Disabled icon and label colors differ", "iOS vs Android", iP("`icon-base` and `text-weaker`", TILE_I, 77, 81), aP("`onSurfaceVariant` (`text-base`) for both", TILE_A, 98, 104), ND, "Each platform's disabled colors", "Disabled tiles are noticeably fainter on iOS."),
      D("low", "Count badge type differs", "iOS vs Android", iP("11 pt semibold", TILE_I, 100), aP("`labelSmall`, 12 sp regular", TILE_A, 192), ND, "Each platform's count type", "The count pill reads differently in size and weight."),
    ],
    "floating-pill": [
      D("medium", "Summary layout exists only on iOS", "Platform-only", iP("`.summary(count:pending:)` with its own marks", FP_I, 19), aP("No summary layout, only a `badgeCount` argument", FP_A, 95, 274), ND, "Summary on iOS, an \"iOS only\" marker on Android", "A collapsed stack shows a state mark on iOS that Android cannot draw the same way."),
      D("medium", "Count badge styling differs", "iOS vs Android", iP("`interactive` fill, `text-on-brand-base`, 13 pt, 1.5 pt raised ring", FP_I, 316, 317, 322, 323), aP("`primary` fill, white, 12 sp, no ring", FP_A, 403, 410, 411), ND, "Each platform's badge", "The badge has a different color, size, and edge on each platform."),
      D("low", "Turn pill fold guard is iOS-only", "iOS vs Android", iP("Forces compact off for attention and failed", TURN_I, 130), aP("Honours `compact` in every phase; a compact failure would draw a spinner", TURN_A, 310), ND, "Each platform's rule when compact is forced on", "Android relies on its call site to keep attention and failure readable."),
      D("low", "Close touch target differs", "iOS vs Android", iP("44 pt", FP_I, 397), aP("48 dp", FP_A, 88), ND, "Each platform's hit area", "Both are accessible, but the difference has no recorded reason."),
    ],
    "menu-chip": [
      D("medium", "Padding and icon sizes differ", "iOS vs Android", iP("12 pt both sides, 14 pt icon and chevron", CHIP_I, 4, 6, 9), aP("10 dp start, 8 dp end (12 for toggle), 16 dp icons", CHIP_A, 43, 44, 46, 139), ND, "Each platform's geometry", "The same chip has a different width and icon weight."),
      D("medium", "Title type differs", "iOS vs Android", iP("13 pt semibold", CHIP_I, 10), aP("14 sp semibold", CHIP_A, 161), ND, "Each platform's type", "Chip labels are visibly larger on Android."),
      D("medium", "Unselected fill and border differ", "iOS vs Android", iP("`text-strong` at 0.08, no border; selected border 0.30", CHIP_I, 118, 122), aP("`surface-raised-base` with `border-weak-base`; selected border 0.32", CHIP_A, 57, 127, 132), ND, "Each platform's fill and border", "The resting chip is a borderless wash on iOS and an outlined surface on Android."),
      D("medium", "Disabled opacity exists only on iOS", "iOS vs Android", iP("0.5 opacity plus `text-weaker`", CHIP_I, 79, 113), aP("`text-weaker` only, full-strength fill", CHIP_A, 102), ND, "Each platform's disabled recipe", "A disabled chip looks more enabled on Android."),
      D("low", "Title width cap exists only on Android", "Platform-only", iP("One line, no cap", CHIP_I, 64), aP("240 dp cap", CHIP_A, 48, 166), ND, "Android caps at 240", "A very long project name truncates at different widths."),
    ],
    "choice-badge": [
      D("high", "Android standard badge has no button role", "iOS vs Android", iP("A SwiftUI `Button`", CB_I, 42), aP("`clickable` with no `role`; the glass variant sets one", CB_A, 100, 143), ND, "Not visible; recorded here", "TalkBack does not announce standard badges as buttons."),
      D("medium", "Glass well opacity differs from Android and docs", "Code vs docs", iP("0.06 unselected, 0.12 selected", "Components/Chrome/OpenCodeFloatingChrome.swift", 41, 43), aP("0.08 unselected, 0.14 selected", CB_A, 134), "`docs/design-system/components/choice-badge.md:29` says 0.14 and 0.08", "Each platform's real opacity", "iOS glass badges are fainter than Android and than the documented recipe."),
      D("medium", "Disabled glass opacity exists only on iOS", "iOS vs Android", iP("0.5 opacity", CB_I, 78), aP("Label color change only", CB_A, 131), ND, "Each platform's disabled recipe", "A disabled composer chip looks more enabled on Android."),
      D("low", "Standard minimum height exists only on iOS", "iOS vs Android", iP("32 pt minimum", CB_I, 48), aP("No minimum, height comes from padding and type", CB_A, 110), ND, "Each platform's sizing", "Standard badges can differ by a point or two in height."),
    ],
    "filter-tabs": [
      D("high", "Segmented style has no keyboard focus ring", "Missing state", iP("Segments omit `openCodeKeyboardFocusable`, which scrolling tabs have", FT_I, 99, 140), aP("Segments use `openCodeControl` without `openCodeFocusRing`, which scrolling tabs have", FT_A, 137, 224), ND, "No focus ring on segments", "Hardware keyboard users cannot see which segment has focus."),
      D("medium", "Scrolling tab type differs", "iOS vs Android", iP("`button` 14 pt, count 13 pt semibold", FT_I, 71, 76), aP("`labelMedium`, which `Type.kt` leaves at the Material default of 12 sp, count 12 sp", FT_A, 157, 177), "`docs/design-system/components/filter-tabs.md:28` allows `type.button` or label-medium, without saying which platform uses which", "Each platform's type", "Android tabs are visibly smaller and use a role outside the app's type scale."),
      D("medium", "Segmented count color differs", "iOS vs Android", iP("`text-weaker`", FT_I, 125), aP("`onSurfaceVariant` (`text-base`)", FT_A, 245), ND, "Each platform's color", "Counts are more prominent on Android."),
    ],
    tag: [
      D("medium", "Warning and success tones exist only on iOS", "Platform-only", iP("Four tones, used for goal status", TAG_I, 16, 18), aP("Neutral and Accent only, so goal status is neutral", TAG_A, 26), ND, "Warning and success on iOS, an \"iOS only\" marker on Android", "A paused or met goal has color meaning on iOS that Android does not show."),
      D("medium", "Tag type size differs", "iOS vs Android", iP("`caption`, 13 pt", TAG_I, 56, 64), aP("`labelSmall`, 12 sp", TAG_A, 63, 84), ND, "13 on iOS, 12 on Android", "The same tag is larger on iOS."),
      D("medium", "Regular neutral text color differs", "iOS vs Android", iP("`text-strong`", TAG_I, 58), aP("`onSurfaceVariant` (`text-base`)", TAG_A, 57), ND, "Each platform's color", "Standalone tags have stronger text on iOS."),
    ],
    "segmented-control": [
      D("medium", "Component exists only on iOS", "Platform-only", iP("`OpenCodeSegmentedControl`, used by Appearance", SEG_I, 16), "No matching component; the closest is Filter Tabs `Segmented`", ND, "Rendered for iOS only", "The same exclusive choice has no shared control on Android."),
      D("low", "Keyboard activation skips the selected guard", "Missing state", iP("Tap returns early when selected, keyboard calls `onSelect` anyway", SEG_I, 41, 68), "Not applicable", ND, "Tap behavior", "Keyboard users can re-fire a selection that a tap would ignore."),
      D("low", "Selected shadow is hardcoded", "Hardcoded value", iP("Black at 0.08, radius 2, y 1", SEG_I, 57), "Not applicable", ND, "The same shadow", "The shadow bypasses tokens and does not adapt to themes."),
    ],
  };
  const div = (id) => DV[id].map(({ severity, title }) => ({ severity, title }));
  window.__controlsActionsDivergences = DV;

  /* ---------- Registry ---------- */
  const CONTRAST = ["high", "low", "subtle", "accent", "destructive"];
  const CONTRASTS = [["High", { contrast: "high" }], ["Low", { contrast: "low" }], ["Subtle", { contrast: "subtle" }], ["Accent", { contrast: "accent" }], ["Destr.", { contrast: "destructive" }]];
  const STATES = [["Live", "live"], ["Ready", "ready"], ["Attention", "needsAttention"], ["Error", "error"], ["Done", "done"]];
  const stateCols = STATES.map(([n, state]) => [n, { state }]);
  const stateRows = (base) => ({ kind: "rows", base, items: STATES.map(([n, state]) => [n, { state }]) });

  Kit.define([
    { id: "button", doc: "button", align: "center",
      params: [sel("variant", ["compactText", "fullWidthText", "link", "iconSquare", "iconCircle", "iconCircleText"]), sel("contrast", CONTRAST), sel("size", ["medium", "large"]), text("title", "Build"), text("glyph", "/"), sel("icon", ["arrow_upward", "add", "search", "edit", "delete", "settings"]), bool("disabled"), bool("pressed"), bool("focused", false, "keyboard focus")],
      states: [["High", { contrast: "high" }], ["Low", { contrast: "low" }], ["Subtle", { contrast: "subtle" }], ["Accent", { contrast: "accent" }], ["Destructive", { contrast: "destructive", title: "Delete" }], ["Pressed", { pressed: true }], ["Focused", { focused: true }], ["Disabled", { disabled: true }], ["Accent disabled", { contrast: "accent", disabled: true }], ["Full width", { variant: "fullWidthText", title: "Run" }], ["Large", { size: "large" }], ["Link", { variant: "link", title: "Need the server first?" }], ["Icon circle", { variant: "iconCircle", contrast: "accent" }], ["Icon square", { variant: "iconSquare", contrast: "low" }], ["Glyph", { variant: "iconCircleText", contrast: "low" }]],
      sheet: [
        { title: "Contrast × state", kind: "matrix", base: { title: "Build" }, rows: CONTRASTS, cols: [["Enabled", {}], ["Pressed", { pressed: true }], ["Focused", { focused: true }], ["Disabled", { disabled: true }]] },
        { title: "Shape × size", kind: "matrix", base: { title: "Build", icon: "arrow_upward", contrast: "low" }, rows: [["M · 40", { size: "medium" }], ["L · 48", { size: "large" }]], cols: [["Text", { variant: "compactText" }], ["Square", { variant: "iconSquare" }], ["Circle", { variant: "iconCircle" }], ["Glyph", { variant: "iconCircleText" }]] },
        { title: "Icon circle × contrast", kind: "matrix", base: { variant: "iconCircle", icon: "arrow_upward" }, rows: [["Enabled", {}], ["Pressed", { pressed: true }], ["Disabled", { disabled: true }]], cols: CONTRASTS },
        { title: "Link", kind: "rows", base: { variant: "link", title: "Need the server first?" }, items: [["Enabled", {}], ["Disabled", { disabled: true }]] },
        { title: "Full width", kind: "stack", inset: true, base: { variant: "fullWidthText", title: "Run" }, items: [["High", { contrast: "high" }], ["Low", { contrast: "low" }], ["Subtle", { contrast: "subtle" }], ["Accent", { contrast: "accent" }], ["Destructive", { contrast: "destructive" }], ["Disabled", { disabled: true }], ["Large", { size: "large" }]] },
      ],
      tokens: [["button.cornerRadius", 8], ["button.minHeight.medium", 40], ["button.minHeight.large", 48], ["button.paddingHorizontal.text", 14], ["button.paddingVertical.text", 10], ["button.icon.size", "40 / 48 box, 18 glyph"], ["button.borderWidth", 1], ["press.scale", "0.98, quiet 0.97 (iOS)"], ["press.opacity", "0.92, quiet 1 (iOS)"], ["press.wash", "text-strong 0.14 (iOS quiet)"], ["subtle.fill", "text-strong 0.06, disabled 0.03 (iOS)"], ["accent.disabled", "interactive 0.10 on surface-raised-base, scale 0.96"], ["type", "type.button"]],
      source: { ios: "Components/Input/OpenCodeButton.swift", android: "ui/components/input/OpenCodeButton.kt" }, divergences: div("button"), render: button },

    { id: "split-button", align: "center",
      params: [sel("contrast", ["low", "high", "subtle", "accent", "destructive"]), text("title", "Deny"), bool("disabled"), sel("pressed", ["none", "label", "menu"])],
      states: [["Low", {}], ["High", { contrast: "high", title: "Run" }], ["Disabled", { disabled: true }], ["High disabled", { contrast: "high", title: "Run", disabled: true }], ["Label pressed", { pressed: "label" }], ["Menu pressed", { pressed: "menu" }]],
      sheet: [
        { title: "Contrast × state", kind: "matrix", rows: CONTRASTS, cols: [["Enabled", {}], ["Disabled", { disabled: true }]] },
        { title: "Pressed half", kind: "rows", items: [["Label", { pressed: "label" }], ["Menu", { pressed: "menu" }]] },
        { title: "Beside compact buttons, as in the permission prompt", kind: "stack", inset: true, items: [["Tap ▾, long-press, or right-click to open the menu", { demo: "prompt" }]] },
      ],
      tokens: [["splitButton.gap", 2], ["splitButton.outerRadius", 8], ["splitButton.innerRadius", 3], ["splitButton.minHeight", 40], ["splitButton.menuWidth", "iOS 32, Android 30"], ["splitButton.menuIcon", 16], ["splitButton.longPress", "400 ms (iOS)"], ["type", "type.button"]],
      spec: "## Intent\nA compact text button joined to a narrow menu half, for a primary action with related alternatives, such as Deny and Deny with a note.\n\n## Public API\n- `title`, menu accessibility label, `enabled`, `contrast`, `action`, `menuAction`.\n\n## Behavior\n- The label half runs the action.\n- The ▾ half, a long-press, or a right-click on the label half opens the menu.\n- Screen readers get two buttons, and the label half exposes the menu as an action.",
      source: { ios: "Components/Input/OpenCodeSplitButton.swift", android: "ui/components/input/OpenCodeSplitButton.kt" }, divergences: div("split-button"),
      render: (p, ctx = Kit.ctx) => (p.demo === "prompt" ? `<div class="c-prompt">${split({ ...p, contrast: "low", title: "Deny" }, ctx)}<span class="c-prompt-sp"></span>${button({ variant: "compactText", title: "Always", contrast: "low", size: "medium" }, ctx)}${button({ variant: "compactText", title: "Allow once", contrast: "high", size: "medium" }, ctx)}</div>` : split(p, ctx)),
      mount: (stage) => once(stage, "[data-split]", (el) => {
        const [main, menu] = el.querySelectorAll("button");
        if (!main || !menu || main.disabled) return;
        const open = () => popMenu(menu, [{ title: "Deny", icon: "close" }, { title: "Deny with a note…", subtitle: "Tell the agent what to do instead", icon: "edit" }]);
        let timer = 0;
        menu.addEventListener("click", open);
        main.addEventListener("contextmenu", (e) => { e.preventDefault(); open(); });
        main.addEventListener("pointerdown", () => { timer = window.setTimeout(open, 400); });
        for (const ev of ["pointerup", "pointerleave", "pointercancel"]) main.addEventListener(ev, () => clearTimeout(timer));
      }) },

    { id: "action-pill", doc: "action-pill", align: "center",
      params: [sel("kind", ["scrollToEnd", "expandPill"]), text("title", "3 worktrees"), bool("expanded"), bool("disabled"), bool("pressed")],
      states: [["Enabled", {}], ["Pressed", { pressed: true }], ["Disabled", { disabled: true }], ["Expand, collapsed", { kind: "expandPill" }], ["Expand, open", { kind: "expandPill", expanded: true }], ["Expand, disabled", { kind: "expandPill", disabled: true }]],
      sheet: [
        { title: "Scroll to end", kind: "rows", items: [["Enabled", {}], ["Pressed", { pressed: true }], ["Disabled", { disabled: true }]] },
        { title: "Expand pill", kind: "rows", base: { kind: "expandPill" }, items: [["Collapsed", {}], ["Expanded", { expanded: true }], ["Disabled", { disabled: true }]] },
      ],
      tokens: [["actionPill.minHeight", 36], ["actionPill.paddingHorizontal", 14], ["actionPill.paddingVertical", 8], ["actionPill.iconSize", 16], ["actionPill.iconSpacing", 6], ["actionPill.shadowElevation", "4, Android"], ["actionPill.disabled", "iOS 0.72 opacity, Android fill 0.7"], ["expandPill.padding", "10 × 4"], ["expandPill.icon", "14, gap 4"], ["expandPill.fill", "text-strong 0.10, disabled 0.05"], ["type", "type.button"]],
      source: { ios: "Components/Input/OpenCodeActionPill.swift", android: "ui/components/input/OpenCodeActionPill.kt" }, divergences: div("action-pill"), render: actionPill,
      mount: (stage) => once(stage, "[data-expand]:not([disabled])", (el) => el.addEventListener("click", () => { const o = el.classList.toggle("open"); el.setAttribute("aria-expanded", o); })) },

    { id: "action-tile", doc: "action-tile", align: "stretch", pad: true,
      params: [sel("icon", ["add", "terminal", "plug", "widgets", "settings"]), text("label", "New"), sel("badge", ["none", "success", "warning", "error", "neutral"]), sel("count", ["", "2", "12", "120"], "", "count"), bool("disabled"), bool("pressed")],
      states: [["Enabled", {}], ["Pressed", { pressed: true }], ["Disabled", { disabled: true }], ["Success dot", { icon: "plug", label: "MCP servers", badge: "success" }], ["Warning dot", { icon: "plug", label: "MCP servers", badge: "warning" }], ["Error count", { icon: "plug", label: "MCP servers", badge: "error", count: "2" }], ["Neutral count, capped", { icon: "plug", label: "MCP servers", badge: "neutral", count: "120" }]],
      sheet: [
        { title: "Two-column palette", kind: "stack", inset: true, items: [["Enabled", { grid: [{ icon: "add", label: "New" }, { icon: "terminal", label: "Terminal" }] }], ["Pressed", { grid: [{ icon: "add", label: "New", pressed: true }, { icon: "terminal", label: "Terminal" }] }], ["Disabled", { grid: [{ icon: "add", label: "New", disabled: true }, { icon: "terminal", label: "Terminal", disabled: true }] }]] },
        { title: "Corner badge", kind: "stack", inset: true, base: { icon: "plug", label: "MCP servers" }, items: [["Dot × tone", { grid: [{ badge: "success" }, { badge: "warning" }] }], ["", { grid: [{ badge: "error" }, { badge: "neutral" }] }], ["Count × tone", { grid: [{ badge: "error", count: "2" }, { badge: "warning", count: "12" }] }], ["Capped at 99+", { grid: [{ badge: "neutral", count: "120" }, { badge: "success", count: "99" }] }], ["Disabled", { grid: [{ badge: "error", count: "2", disabled: true }, { badge: "success", disabled: true }] }]] },
      ],
      tokens: [["actionTile.cornerRadius", 14], ["actionTile.minHeight", 88], ["actionTile.paddingTop", 18], ["actionTile.paddingHorizontal", 14], ["actionTile.paddingBottom", 16], ["actionTile.iconSize", 24], ["actionTile.contentGap", 12], ["actionTile.badgeInset", 12], ["actionTile.badge.dot", 10], ["actionTile.badge.countHeight", 18], ["actionTile.press", "scale 0.97, surface-raised-base-hover"], ["actionTile.disabledContent", 0.7], ["type", "type.body"]],
      source: { ios: "Components/Input/OpenCodeActionTile.swift", android: "ui/components/input/OpenCodeActionTile.kt" }, divergences: div("action-tile"), render: actionTile },

    { id: "floating-pill", doc: "floating-pill-chrome", align: "center",
      params: [sel("layout", ["iconLabel", "iconOnly", "counter", "counterWaiting", "closable", "label", "summary", "turn"]), sel("state", ["live", "ready", "needsAttention", "error", "done"]), sel("phase", ["working", "attentionUp", "attentionDot", "failed", "done"], "working", "turn phase"), text("label", ""), bool("compact"), sel("count", ["1", "3"], "1", "stack count"), bool("pending")],
      states: [...STATES.map(([n, state]) => [n, { state }]), ["Compact", { compact: true }], ["Closable", { layout: "closable", state: "error" }], ["Summary", { layout: "summary", count: "3", state: "needsAttention" }], ["Turn, working", { layout: "turn", phase: "working" }], ["Turn, done", { layout: "turn", phase: "done" }]],
      sheet: [
        { title: "Icon + label", ...stateRows({ layout: "iconLabel" }) },
        { title: "Icon only", kind: "matrix", base: { layout: "iconOnly" }, rows: [["", {}]], cols: stateCols },
        { title: "Live counter", ...stateRows({ layout: "counter" }) },
        { title: "Live counter, waiting item", kind: "stack", inset: true, base: { layout: "counterWaiting" }, items: STATES.map(([n, state]) => [n, { state }]) },
        { title: "Label + close", ...stateRows({ layout: "closable" }) },
        { title: "Label only", ...stateRows({ layout: "label" }) },
        { title: "Summary, round with count", kind: "matrix", base: { layout: "summary" }, rows: [["1", { count: "1" }], ["3", { count: "3" }], ["Pending", { count: "3", pending: true }]], cols: stateCols },
        { title: "Compact (keyboard up)", kind: "matrix", base: { compact: true }, rows: [["Icon", { layout: "iconLabel" }], ["Counter", { layout: "counter" }], ["Waiting", { layout: "counterWaiting" }], ["Close", { layout: "closable" }], ["Label", { layout: "label" }]], cols: stateCols },
        { title: "Turn status", kind: "stack", inset: true, base: { layout: "turn" }, items: [["Spinner bends into the check", { demo: "turn" }], ["Working", { phase: "working" }], ["Compacting", { phase: "working", label: "Compacting…" }], ["Sending", { phase: "working", label: "Sending 40%" }], ["Retrying", { phase: "working", label: "Retrying" }], ["Review permission", { phase: "attentionUp" }], ["Answer question", { phase: "attentionUp", label: "Answer question" }], ["Waiting", { phase: "attentionDot" }], ["Child needs input", { phase: "attentionDot", label: "general needs input" }], ["Failed", { phase: "failed" }], ["Done", { phase: "done" }]] },
        { title: "Fold to compact", kind: "stack", inset: true, items: [["The width folds, nothing cross-fades", { demo: "fold" }]] },
      ],
      tokens: [["floatingPill.minHeight", 36], ["floatingPill.paddingHorizontal", 14], ["floatingPill.paddingHorizontal.compact", 10], ["floatingPill.paddingVertical", 8], ["floatingPill.iconSize", 16], ["floatingPill.iconSpacing", 6], ["floatingPill.dotSize", 8], ["floatingPill.ringWidth", 1], ["floatingPill.badgeSize", "16, offset 4 / −4"], ["floatingPill.closeTouchTarget", "iOS 44, Android 48"], ["floatingPill.foldDuration", "260 ms"], ["floatingPill.pulse", "800 ms, 1 → 0.35"], ["spinner", "r 6, stroke 1.3, 1 s rotation, 1.6 s breathe"], ["turnPill.widthDuration", "420 ms"], ["type", "type.button"]],
      spec: "## Intent\nThe shared Session Chat floating pill: one look across its layouts and five states.\n\n## Public API\n- `layout`: icon + label, icon only, counter, closable, label, and on iOS summary.\n- `state`: live, ready, needs attention, error, done.\n- `compact`, an optional action, an optional close action, and a custom accessibility label.\n\n## Behavior\n- Without an action, the pill is a status rather than a button.\n- Compact folds to the round mark and hides the label and ✕, while still speaking the full label.\n- Needs attention adds an amber ring, and a waiting counter pulses a dot.\n- The turn pill's Working to Done change is approximated here with a crossfade between spinner and check.",
      source: { ios: "Components/Chrome/OpenCodeFloatingPill.swift", android: "ui/components/chrome/OpenCodeFloatingPill.kt" }, divergences: div("floating-pill"), render: floatingPill,
      mount: (stage) => {
        once(stage, ".c-fp-x", (x) => x.addEventListener("click", (e) => { e.stopPropagation(); const f = x.closest("[data-fp]"); f.classList.add("c-dismissed"); window.setTimeout(() => f.classList.remove("c-dismissed"), 1100); }));
        once(stage, "[data-fold-demo]", (d) => {
          const t = d.querySelector(".badge"), pills = d.querySelectorAll("[data-fp]");
          const lead = pills[0], b = Object.assign(document.createElement("span"), { className: "c-fp-badge fold-only", textContent: lead.dataset.foldBadge ?? "3" });
          lead.querySelector(".c-fp-badge")?.remove(); lead.append(b);
          t.addEventListener("click", () => { const on = t.classList.toggle("sel"); pills.forEach((p) => p.classList.toggle("is-compact", on)); });
        });
        once(stage, "[data-turn-demo]", (d) => {
          const t = d.querySelector(".badge"), pills = [...d.querySelectorAll(".c-turn")];
          const fit = () => pills.forEach((p) => { const ls = p.querySelector(".c-turn-ls"), on = p.classList.contains("is-done"); const w = ls.querySelector(on ? ".c-turn-b" : ".c-turn-a").offsetWidth; ls.style.width = w + "px"; });
          fit();
          t.addEventListener("click", () => { const on = t.classList.toggle("sel"); pills.forEach((p) => { p.classList.toggle("is-done", on); p.classList.toggle("t-base", !on); p.classList.toggle("t-strong", on); }); t.textContent = on ? "Back to Working" : "Finish turn"; fit(); });
        });
      } },

    { id: "menu-chip", align: "center",
      params: [sel("chip", ["menu", "toggle"], "menu", "variant"), text("title", "All projects"), sel("leading", ["icon", "letter", "none"]), sel("icon", ["widgets", "archive"]), sel("accent", ["teal", "violet", "amber", "blue", "rose", "red", "green", "indigo"]), bool("selected"), bool("disabled"), bool("pressed")],
      states: [["Default", {}], ["Selected choice", { title: "acme-invoices", leading: "letter", selected: true }], ["No leading", { title: "No leading", leading: "none" }], ["Disabled", { title: "Disabled", disabled: true }], ["Toggle off", { chip: "toggle", title: "Include archived · 3", icon: "archive" }], ["Toggle on", { chip: "toggle", title: "Include archived · 3", icon: "archive", selected: true }]],
      sheet: [
        { title: "Menu", kind: "rows", items: [["Icon", {}], ["Letter", { title: "acme-invoices", leading: "letter" }], ["Selected", { title: "acme-invoices", leading: "letter", selected: true }], ["No leading", { title: "No leading", leading: "none" }], ["Pressed", { pressed: true }], ["Disabled", { title: "Disabled", disabled: true }], ["Sel., disabled", { title: "acme-invoices", leading: "letter", selected: true, disabled: true }]] },
        { title: "Toggle", kind: "rows", base: { chip: "toggle", title: "Include archived · 3", icon: "archive" }, items: [["Off", {}], ["On", { selected: true }], ["Disabled", { disabled: true }]] },
        { title: "Letter accents", kind: "flow", base: { leading: "letter", title: "acme" }, items: ["violet", "teal", "amber", "blue", "rose", "red", "green", "indigo"].map((accent) => ["", { accent }]) },
      ],
      tokens: [["menuChip.minHeight", 32], ["menuChip.radius", "capsule"], ["menuChip.padding", "iOS 12 / 12, Android 10 / 8 (toggle 10 / 12)"], ["menuChip.spacing", 6], ["menuChip.iconSize", "iOS 14, Android 16"], ["menuChip.letterSize", "18, glyph 10"], ["menuChip.fill", "iOS text-strong 0.08, Android surface-raised-base"], ["menuChip.selected", "interactive 0.14, border iOS 0.30, Android 0.32"], ["menuChip.titleMaxWidth", "240, Android"], ["type", "iOS 13 semibold, Android 14 semibold"]],
      spec: "## Intent\nA capsule in a filter row that shows the current choice and opens a menu, or turns one option on and off.\n\n## Public API\n- `variant`: menu or toggle.\n- `title`, `leading` (none, icon, or letter badge), `selected`, `enabled`, and an accessibility label.\n\n## Behavior\n- Menu ends with a chevron, and `selected` marks a choice other than the default.\n- Toggle has no chevron, and `selected` means on.\n- Both draw `selected` with the interactive tint.",
      source: { ios: "Components/Input/OpenCodeMenuChip.swift", android: "ui/components/input/OpenCodeMenuChip.kt" }, divergences: div("menu-chip"), render: menuChip,
      mount: (stage, ctx) => once(stage, "[data-chip]:not([disabled])", (el) => el.addEventListener("click", () => {
        if (el.dataset.chip === "toggle") { el.classList.toggle("sel"); return; }
        const title = el.querySelector(".c-chip-t"), opts = ["All projects", "opencode-mobile", "acme-invoices"];
        popMenu(el, opts.map((o, i) => ({ title: o, icon: o === title.textContent ? "check" : null, pick: () => {
          title.textContent = o; el.classList.toggle("sel", i !== 0);
          const lead = el.firstElementChild;
          if (lead !== title) { const a = LETTERS[i === 1 ? "red" : "teal"], [f, t] = ctx.dark ? [a[2], a[3]] : [a[0], a[1]]; if (i === 0) { const x = document.createElement("span"); x.innerHTML = I("widgets", isAndroid(ctx) ? 16 : 14); lead.replaceWith(x.firstElementChild); } else { const x = Object.assign(document.createElement("span"), { className: "c-chip-letter", textContent: o[0].toUpperCase() }); x.style.background = f; x.style.color = t; lead.replaceWith(x); } }
        } })));
      })) },

    { id: "choice-badge", doc: "choice-badge", align: "center",
      params: [sel("variant", ["standard", "glass"]), text("label", "Build"), bool("selected", true), bool("disabled"), bool("pressed")],
      states: [["Standard", { selected: false }], ["Standard selected", { selected: true }], ["Standard disabled", { selected: false, disabled: true }], ["Glass", { variant: "glass", selected: false }], ["Glass selected", { variant: "glass", selected: true }], ["Glass pressed", { variant: "glass", selected: false, pressed: true }], ["Glass disabled", { variant: "glass", selected: false, disabled: true }]],
      sheet: [
        { title: "Variant × state", kind: "matrix", base: { label: "Plan" }, rows: [["Standard", { variant: "standard" }], ["Glass", { variant: "glass" }]], cols: [["Off", { selected: false }], ["On", { selected: true }], ["Off, dis.", { selected: false, disabled: true }], ["On, dis.", { selected: true, disabled: true }]] },
        { title: "In a row, standard", kind: "flow", items: [["", { label: "Opus 5.5", selected: true }], ["", { label: "Sonnet 5.5", selected: false }], ["", { label: "Haiku 5.5", selected: false }]] },
        { title: "In a row, glass", kind: "flow", base: { variant: "glass" }, items: [["", { label: "Build", selected: true }], ["", { label: "Plan", selected: false }], ["", { label: "High", selected: false }]] },
      ],
      tokens: [["choiceBadge.cornerRadius", 16], ["choiceBadge.minHeight", "32, iOS"], ["choiceBadge.paddingHorizontal", 12], ["choiceBadge.paddingVertical", 8], ["choiceBadge.borderWidth", 1], ["choiceBadge.glass.cornerRadius", "capsule"], ["choiceBadge.glass.minHeight", 36], ["choiceBadge.glass.fill", "iOS 0.06 / 0.12, Android 0.08 / 0.14"], ["choiceBadge.glass.disabled", "iOS opacity 0.5"], ["type", "type.button"]],
      source: { ios: "Components/List/OpenCodeChoiceBadge.swift", android: "ui/components/list/OpenCodeChoiceBadge.kt" }, divergences: div("choice-badge"), render: badge },

    { id: "filter-tabs", doc: "filter-tabs", align: "stretch",
      params: [sel("style", ["scrolling", "segmented"]), sel("selected", ["all", "friends", "unread", "news", "mobile"]), sel("segment", ["running", "done"]), bool("counts", true), bool("unseen", true)],
      states: [["Scrolling", {}], ["Other tab selected", { selected: "unread" }], ["No counts", { counts: false, unseen: false }], ["Segmented", { style: "segmented" }], ["Segmented, Done", { style: "segmented", segment: "done" }], ["Segmented, no counts", { style: "segmented", counts: false, unseen: false }]],
      sheet: [
        { title: "Scrolling, swipe sideways", kind: "stack", items: [["With counts and an unseen dot", {}], ["Other tab selected", { selected: "unread" }], ["No counts", { counts: false, unseen: false }]] },
        { title: "Segmented", kind: "stack", base: { style: "segmented" }, items: [["Running selected, Done has news", {}], ["Done selected", { segment: "done" }], ["No counts", { counts: false, unseen: false }]] },
      ],
      tokens: [["filterTabs.tab.minHeight", 36], ["filterTabs.tab.paddingHorizontal", 12], ["filterTabs.tab.paddingVertical", 8], ["filterTabs.tab.spacing", 8], ["filterTabs.tab.contentSpacing", 6], ["filterTabs.tab.borderWidth", 1.5], ["filterTabs.count.minSize", 22], ["filterTabs.row.padding", "16 × 8"], ["filterTabs.segmented.trackPadding", 3], ["filterTabs.segmented.minHeight", 32], ["filterTabs.unseenDot", 6], ["type", "iOS type.button, Android labelMedium 12"]],
      source: { ios: "Components/List/OpenCodeFilterTabs.swift", android: "ui/components/list/OpenCodeFilterTabs.kt" }, divergences: div("filter-tabs"), render: filterTabs,
      mount: (stage) => once(stage, "[data-ftabs]", (row) => row.addEventListener("click", (e) => { const b = e.target.closest("[data-tab]"); if (!b) return; row.querySelectorAll("[data-tab]").forEach((x) => x.classList.toggle("sel", x === b)); })) },

    { id: "tag", align: "center",
      params: [sel("size", ["regular", "small"]), sel("tone", ["neutral", "accent", "warning", "success"]), text("title", "Unofficial")],
      states: [["Regular", {}], ["Small", { size: "small", title: "git" }], ["Accent", { tone: "accent", title: "Recommended" }], ["Small accent", { size: "small", tone: "accent", title: "Recommended" }], ["Paused", { size: "small", tone: "warning", title: "Paused" }], ["Met", { size: "small", tone: "success", title: "Met" }]],
      sheet: [
        { title: "Tone × size", kind: "matrix", base: { title: "Beta" }, rows: [["Regular", { size: "regular" }], ["Small", { size: "small" }]], cols: [["Neutral", { tone: "neutral" }], ["Accent", { tone: "accent" }], ["Warning", { tone: "warning" }], ["Success", { tone: "success" }]] },
        { title: "Sample labels", kind: "rows", items: [["Standalone", { title: "Unofficial" }], ["Inline", { size: "small", title: "git" }], ["Choice", { tone: "accent", title: "Recommended" }], ["Paused goal", { size: "small", tone: "warning", title: "Paused" }], ["Met goal", { size: "small", tone: "success", title: "Met" }]] },
      ],
      tokens: [["tag.radius", "capsule"], ["tag.regular.padding", "10 × 5"], ["tag.small.padding", "6 × 1"], ["tag.fill.neutral", "surface-raised-base"], ["tag.fill.accent", "interactive 0.12"], ["tag.fill.warning", "warning 0.14, iOS"], ["tag.fill.success", "success 0.14, iOS"], ["type.regular", "iOS caption bold, Android labelSmall bold"], ["type.small", "iOS caption semibold, Android labelSmall semibold"]],
      spec: "## Intent\nA static capsule label for short metadata, such as Unofficial, git, or Recommended.\n\n## Public API\n- `title`, which must not be blank.\n- `size`: regular or small.\n- `tone`: neutral and accent on both platforms, plus warning and success on iOS.\n\n## Behavior\nTags are not interactive. The small size stays on one line beside a title.",
      source: { ios: "Components/Feedback/OpenCodeTag.swift", android: "ui/components/feedback/OpenCodeTag.kt" }, divergences: div("tag"), render: tag },

    { id: "segmented-control", align: "stretch", pad: true,
      params: [sel("selection", ["system", "light", "dark"]), bool("note", true)],
      states: [["System", { selection: "system" }], ["Light", { selection: "light" }], ["Dark", { selection: "dark" }]],
      sheet: [{ title: "Selection, tap to move", kind: "stack", inset: true, items: [["System", { selection: "system" }], ["Light", { selection: "light" }], ["Dark", { selection: "dark" }]] }],
      tokens: [["segmentedControl.height", 36], ["segmentedControl.inset", 3], ["segmentedControl.cornerRadius", 10], ["segmentedControl.thumbRadius", 7], ["segmentedControl.track", "surface-raised-base"], ["segmentedControl.thumb", "background-base"], ["segmentedControl.shadow", "black 0.08, radius 2, y 1"], ["segmentedControl.animation", "easeOut 180 ms"], ["type", "type.button"]],
      spec: "## Intent\nA short exclusive choice drawn as equal-width segments in one track, such as the Appearance mode. iOS only.\n\n## Public API\n- `options` (id, title, accessibility identifier), `selection`, `onSelect`.\n\n## Behavior\n- The raised thumb slides to the selected segment over 180 ms.\n- Tapping the selected segment does nothing.\n- Each segment is a button with the selected trait.",
      source: { ios: "Components/Input/OpenCodeSegmentedControl.swift" }, divergences: div("segmented-control"), render: segmented,
      mount: (stage) => once(stage, "[data-segctl]", (w) => {
        const track = w.querySelector(".c-segctl"), note = w.querySelector(".c-segctl-n");
        track.addEventListener("click", (e) => { const b = e.target.closest("[data-i]"); if (!b || b.classList.contains("sel")) return; track.querySelectorAll("[data-i]").forEach((x) => x.classList.toggle("sel", x === b)); track.style.setProperty("--i", b.dataset.i); if (note) note.textContent = MODES[+b.dataset.i][2]; });
      }) },
  ]);
})();
