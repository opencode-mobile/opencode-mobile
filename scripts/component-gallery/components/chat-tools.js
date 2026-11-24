// Component group: chat-tools. Values are read from the native components on both platforms.
(() => {
  const { esc, I, sel, text, bool } = Kit;
  const isA = (ctx) => ctx?.platform === "android";
  const fmt = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  const on = (v) => v === true || v === "true";
  const has = (v) => v !== undefined && v !== null && String(v) !== "";
  const clock = (s, cls = "") => `<span class="ct-time ${cls}" data-ct-el="${s}">${fmt(s)}</span>`;
  const chev = (size, color) => `<span class="ct-chev" style="color:var(--${color})">${I("chevron_right", size)}</span>`;
  const wrap = (tappable, cls, body, label) => tappable
    ? `<button class="${cls} ct-btn press" aria-label="${esc(label)}">${body}</button>`
    : `<div class="${cls}" aria-label="${esc(label)}">${body}</div>`;

  // Tool images: the gallery a run's images form under its block in every timeline style (#1097).
  const tile = (extra = "", over = "") => `<span class="ct-img-tile ${extra}">${I("image", 20)}${over ? `<span class="ct-img-over">${esc(over)}</span>` : ""}</span>`;
  const toolImages = (count, ctx) => {
    const n = Number(count) || 0;
    if (!n) return "";
    if (Kit.components["image-gallery"]) return Kit.use("image-gallery", { count: String(n), tiles: "loaded" }, ctx);
    if (n === 1) return `<div class="ct-img ct-img-1">${tile()}</div>`;
    if (n === 2) return `<div class="ct-img ct-img-2">${tile()}${tile()}</div>`;
    if (n === 3) return `<div class="ct-img ct-img-3">${tile("ct-img-lead")}${tile()}${tile()}</div>`;
    return `<div class="ct-img ct-img-4">${tile()}${tile()}${tile()}${tile("", n > 4 ? `+${n - 3}` : "")}</div>`;
  };
  const withImages = (html, p, ctx) => (Number(p.images) ? `<div class="ct-with-img">${html}<div class="ct-with-img-g">${toolImages(p.images, ctx)}</div></div>` : html);
  const imageParam = sel("images", ["0", "1", "2", "3", "4", "6"], "0", "tool images");

  // Tool Call Card
  const cardDot = { pending: "warning", running: "warning", completed: "success", error: "error", interrupted: "text-weak" };
  const toolCard = (p, ctx) => {
    const add = Number(p.additions) || 0, del = Number(p.deletions) || 0;
    const diffInner = Kit.components["diff-stat"] ? Kit.use("diff-stat", { additions: add ? String(add) : "", deletions: del ? String(del) : "", style: "caption", separator: "space", disabled: false }, ctx) : `${add ? `<span class="add">+${add}</span>` : ""}${add && del ? " " : ""}${del ? `<span class="del">−${del}</span>` : ""}`;
    const diff = add || del ? `<span class="ct-tc-diff t-caption">${diffInner}</span>` : "";
    const meta = has(p.subtitle) || diff ? `<span class="ct-tc-meta"><span class="ct-tc-sub t-caption">${esc(p.subtitle)}</span>${diff}</span>` : "";
    const errColor = p.status === "interrupted" ? "text-weak" : "error";
    // iOS folds the decline note into errorText; Android draws it as its own muted line.
    const errBody = isA(ctx) ? p.errorText : [p.errorText, p.note].filter(has).join("\n");
    const err = has(errBody) ? `<span class="ct-tc-err t-caption" style="color:var(--${errColor})">${esc(errBody)}</span>` : "";
    const note = isA(ctx) && has(p.note) ? `<span class="ct-tc-err t-caption" style="color:var(--text-weak)">${esc(p.note)}</span>` : "";
    const body = `<span class="ct-tc-dot" style="background:var(--${cardDot[p.status]})"></span><span class="ct-tc-bd"><span class="ct-tc-title t-heading">${esc(p.title)}</span>${meta}${err}${note}</span>`;
    return withImages(wrap(on(p.tappable), "ct-tc", body, [p.status, p.title, p.subtitle, p.errorText, p.note].filter(has).join(", ")), p, ctx);
  };

  // Tool Chip
  const chipSamples = {
    ios: { file: ["file", "SessionChatView.swift"], edit: ["file", "OpenCodeListItem.swift", 12, 3], command: ["command", "xcodebuild test"], agent: ["agent", "Explore", "Find the session list"], skill: ["skill", "/feature"], explore: ["explore", "Explored · 4 reads · 2 greps"], more: ["more", "+3 more"] },
    android: { file: ["file", "SessionChatTimelineView.kt"], edit: ["file", "SessionChatTimeline.kt", 12, 3], command: ["command", "./gradlew :app:testDebugUnitTest"], agent: ["agent", "Explore", "Find where the branch pill is rendered"], skill: ["skill", "/feature"], explore: ["explore", "Explored · 4 reads · 2 greps"], more: ["more", "+3 more"] },
  };
  const chipIcon = { file: "description", edit: "edit", command: "terminal", agent: "call_split", skill: "list", explore: "search" };
  const chipColors = (key, kind, st, android) => {
    const more = kind === "more";
    if (android) {
      let c = { bg: "surface-raised-base", bd: "var(--border-weak-base)", ic: "text-weak", lb: "text-strong" };
      if (kind === "agent") c = { ...c, bd: "color-mix(in srgb, var(--info) 40%, transparent)", ic: "info" };
      if (kind === "skill") c = { ...c, bg: "surface-interactive-weak", ic: "text-interactive-base", lb: "text-interactive-base" };
      if (more) return { ...c, lb: "text-base" };
      if (st === "error") return { ...c, bg: "surface-critical-base", bd: "color-mix(in srgb, var(--error) 40%, transparent)", ic: "error", lb: kind === "agent" ? "error" : c.lb };
      if (st === "pending" || st === "interrupted") return { ...c, lb: "text-base" };
      return c;
    }
    const s = more ? null : st;
    const lb = more ? "text-base" : s === "error" ? "error" : kind === "skill" ? "text-interactive-base" : s === "pending" || s === "interrupted" ? "text-base" : "text-strong";
    const ic = s === "error" ? "error" : kind === "agent" ? "info" : kind === "skill" ? "text-interactive-base" : "icon-weak-base";
    const bg = s === "error" ? "surface-critical-base" : kind === "skill" ? "surface-interactive-weak" : "surface-raised-base";
    const bd = s === "error" ? "color-mix(in srgb, var(--error) 40%, transparent)" : kind === "agent" ? "color-mix(in srgb, var(--info) 35%, transparent)" : "var(--border-weak-base)";
    return { bg, bd, ic, lb };
  };
  const chipLabel = (label, kind) => {
    if (kind !== "file" || label.length < 16) return `<span class="ct-chip-text">${esc(label)}</span>`;
    return `<span class="ct-chip-text ct-mid"><span class="h">${esc(label.slice(0, -10))}</span><span class="t">${esc(label.slice(-10))}</span></span>`;
  };
  const toolChip = (p, ctx) => {
    const android = isA(ctx);
    const [kind, label, a, b] = chipSamples[android ? "android" : "ios"][p.variant];
    const st = kind === "more" ? "completed" : p.status;
    const c = chipColors(p.variant, kind, st, android);
    const glyphSize = android ? 12 : 11;
    const running = st === "running";
    const icon = chipIcon[p.variant];
    const iconHtml = icon ? `<span class="ct-chip-ic" style="color:var(--${c.ic})">${I(icon, 12)}</span>` : "";
    const lead = !icon ? "" : kind === "agent" ? `<span class="ct-chip-tile">${iconHtml}</span>` : iconHtml;
    const brief = kind === "agent" ? `<span class="ct-chip-brief">${esc(a)}</span>` : "";
    const diff = p.variant === "edit" ? (android ? `<span class="ct-chip-diff add">+${a}</span><span class="ct-chip-diff del">−${b}</span>` : `<span class="ct-chip-diff"><span class="add">+${a}</span> <span class="del">−${b}</span></span>`) : "";
    const glyph = kind === "more" ? "" : st === "error" ? `<span class="ct-chip-g" style="color:var(--error)">${I("close", glyphSize)}</span>` : st === "interrupted" ? `<span class="ct-chip-g" style="color:var(--text-weak)">${I("stop_circle", glyphSize)}</span>` : st === "completed" && (kind === "command" || kind === "agent") ? `<span class="ct-chip-g" style="color:var(--success)">${I("check", glyphSize)}</span>` : "";
    const trailing = on(p.trailing) && (android || kind !== "more") ? `<span class="ct-chip-trail">${android ? "42s" : "1m 4s"}</span>` : "";
    const dash = st === "pending" && kind !== "more" ? `<svg class="ct-chip-dash" aria-hidden="true"><rect x=".5" y=".5" rx="7.5" ry="7.5" stroke-dasharray="${android ? "4 3" : "3 2"}" style="stroke:${c.bd}"></rect></svg>` : "";
    const tail = android ? trailing + glyph : glyph + trailing;
    const cls = `ct-chip ct-chip-${kind} ${running ? "ct-chip-run" : ""} ${icon ? "" : "ct-chip-noicon"} ${st === "pending" && kind !== "more" ? "ct-chip-pending" : ""}`;
    const style = `background:var(--${c.bg});--ct-bd:${c.bd};--ct-rest:var(--${c.lb});color:var(--${c.lb})`;
    const body = `${dash}${lead}<span class="ct-chip-lb">${chipLabel(label, kind)}</span>${brief}${diff}${tail}`;
    const html = on(p.tappable) ? `<button class="${cls} ct-btn press" style="${style}" aria-label="${esc(`${label}, ${st}`)}">${body}</button>` : `<span class="${cls}" style="${style}" aria-label="${esc(`${label}, ${st}`)}">${body}</span>`;
    return withImages(html, p, ctx);
  };

  // Activity Card
  const activityIcon = (name, ctx) => ({ read: "description", search: "search", edit: "edit", command: "terminal", fetch: "cloud", agent: "agent", thought: "architecture", skill: isA(ctx) ? "list" : "slash", other: "widgets" }[name]);
  const activitySamples = (android) => ({
    oneTool: { live: 12, summary: android ? "1 step · 1 command" : "4 steps · 1 read · 1 search · 1 edit · 1 command", current: ["command", android ? "Running ./gradlew :app:testDebugUnitTest" : "Running xcodebuild test -scheme OpenCode Mobile", 0, 12] },
    twoAgents: { live: 64, summary: android ? "6 steps · 3 reads · 1 search · 1 agent · 1 edit" : "8 steps · 3 reads · 1 search · 2 agents · 2 edits", agents: android ? [["Explore", 'Searching "branchPill"', 64], ["Review", "Reading SessionHeader.kt", 21]] : [["Explore", 'Searching "branchPill"', 42], ["General", "Reading GitStatusRepository.swift", 9]], current: ["edit", android ? "Editing SessionChatRepoStrip.kt" : "Editing SessionChatRepoStrip.swift", 1, 3] },
    justFinished: { live: 9, summary: "3 steps · 2 reads · 1 search", current: ["search", 'Searched "branchPill"', 0, null, false] },
    waiting: { live: android ? 40 : 30, summary: android ? "4 steps · 2 reads · 2 agents" : "3 steps · 2 reads · 1 agent", agents: android ? [["Explore", 'Searching "branchPill"', 64], ["Review", "Reading SessionHeader.kt", 21]] : [["Explore", "Find where branch names render", 20]], idle: "Waiting for agents" },
    settled: { worst: "completed", steps: 6, summary: android ? "Read, Grep, Edit, Agent" : "Read, Grep, Edit" },
    settledAgents: { worst: "completed", steps: android ? 4 : 3, summary: android ? "Agent, Read, Thought" : "Agent, Thought", agents: android ? [["Explore", "Found 3 call sites of branchPill"], ["Review", "Review the session header"]] : [["Explore", "Branch names render in SessionChatRepoStrip"], ["General", "Check how GitStatusRepository refreshes"]] },
    settledFailure: { worst: "failed", steps: android ? 7 : 5, summary: android ? "Read, Grep, Edit, Shell, Agent" : "Read, Edit, Shell" },
    settledStopped: { worst: "interrupted", steps: 2, summary: "Read, Shell" },
  });
  const worstColor = { completed: "success", failed: "error", interrupted: "text-weak" };
  const acAgents = (agents, ctx) => {
    if (!agents?.length) return "";
    const rows = agents.map(([name, action, secs], i) => `${i ? '<span class="ct-ac-hair ct-ac-inset"></span>' : ""}<button class="ct-ac-agent ct-btn press" data-ct-note="Agent row tapped: opens its child session" aria-label="${esc(`${name}, ${action}`)}"><span class="ct-ac-tile">${esc(name[0].toUpperCase())}</span><span class="ct-ac-name t-secondary">${esc(name)}</span><span class="ct-ac-act t-secondary">${esc(action)}</span>${secs != null ? clock(secs, "ct-ac-time") : ""}${chev(isA(ctx) ? 16 : 14, "icon-base")}</button>`).join("");
    return `<div class="ct-ac-agents"><span class="ct-ac-hair"></span><span class="ct-ac-label">${agents.length === 1 ? "AGENT" : `${agents.length} AGENTS`}</span>${rows}</div>`;
  };
  const acFooterBody = (s, ctx) => {
    const cc = s.current;
    if (cc) {
      const running = cc[4] !== false;
      return `<span class="ct-ac-ficon">${I(activityIcon(cc[0], ctx), 16)}</span><span class="ct-ac-ftitle t-secondary">${esc(cc[1])}</span>${running && cc[2] > 0 ? `<span class="t-caption ct-ac-extra">+${cc[2]}</span>` : ""}${running && cc[3] != null ? clock(cc[3], "ct-ac-time") : ""}${chev(isA(ctx) ? 16 : 14, "text-base")}`;
    }
    return `<span class="ct-ac-ftitle ct-ac-idle t-secondary">${esc(s.idle ?? "Thinking…")}</span>${chev(isA(ctx) ? 16 : 14, "text-base")}`;
  };
  const activityCard = (p, ctx) => {
    const android = isA(ctx);
    const s = activitySamples(android)[p.sample];
    if (s.live != null) {
      const head = `<span class="ct-ac-headrow"><span class="ct-ac-work t-secondary">Working</span>${clock(s.live, "ct-ac-time")}</span>`;
      const summary = `<span class="ct-ac-sum t-caption">${esc(s.summary)}</span>`;
      // iOS makes the header a button that opens the sheet. Android leaves it inert.
      const header = android ? `<div class="ct-ac-head">${head}${summary}</div>` : `<button class="ct-ac-head ct-btn press" data-ct-note="Card tapped: opens the activity sheet">${head}${summary}</button>`;
      const footer = `<button class="ct-ac-foot ct-btn press" data-ct-note="Card tapped: opens the activity sheet" ${p.sample === "oneTool" && on(p.cycle) ? 'data-ct-cycle="1"' : ""}><span class="ct-ac-fbody">${acFooterBody(s, ctx)}</span></button>`;
      return withImages(`<div class="ct-ac">${header}${acAgents(s.agents, ctx)}${footer}</div>`, p, ctx);
    }
    const steps = `<span class="ct-ac-count">${s.steps}</span><span class="${android ? "ct-ac-strong" : "ct-ac-base"}"> steps</span>`;
    const line = `<button class="ct-ac-settled ct-btn press" data-ct-note="Card tapped: opens the activity sheet"><span class="ct-ac-dot" style="background:var(--${worstColor[s.worst]})"></span><span class="ct-ac-stext t-secondary">${steps}<span class="ct-ac-base"> · ${esc(s.summary)}</span></span>${chev(android ? 16 : 14, "icon-base")}</button>`;
    return withImages(`<div class="ct-ac ${s.agents ? "ct-ac-hasagents" : ""}">${line}${acAgents(s.agents, ctx)}</div>`, p, ctx);
  };

  // Child Session Row, Shell Row, Task Row
  const childColor = { "waiting for input": "warning", failed: "error" };
  const childRow = (p) => {
    const st = p.status === "none" ? "" : p.status;
    const trailing = `${st ? `<span class="t-caption" style="color:var(--${childColor[st] ?? "text-weaker"})">${esc(st)}</span>` : ""}${on(p.showElapsed) ? `<span class="ct-digits t-caption">${esc(p.elapsed)}</span>` : ""}${on(p.tappable) ? chev(16, "icon-strong-base") : ""}`;
    const body = `<span class="ct-avatar">${esc((p.title[0] ?? "?").toUpperCase())}</span><span class="ct-lr-text"><span class="ct-lr-title"><span class="t-heading ct-ell">${esc(p.title)}</span>${on(p.showCaption) ? `<span class="t-caption ct-weaker">${esc(p.caption)}</span>` : ""}</span>${on(p.showSubtitle) ? `<span class="t-caption ct-ell ct-base">${esc(p.subtitle)}</span>` : ""}</span><span class="ct-lr-trail">${trailing}</span>`;
    return wrap(on(p.tappable), "ct-lr", body, [p.title, p.subtitle, st, p.elapsed].filter(has).join(", "));
  };
  const shellRow = (p, ctx) => {
    const longCmd = isA(ctx) ? "./gradlew :app:testDebugUnitTest --tests 'dev.example.sessionchat.SessionChatViewModelTest' --info --stacktrace" : "for i in $(seq 1 40); do echo line $i; sleep 0.5; done && ./scripts/build.sh --release --verbose";
    const multi = "npm run build\nnpm test";
    const raw = p.command === "long" ? longCmd : p.command === "multiline" ? multi : "npm test";
    // Android joins a multi-line command; iOS draws it with lineLimit(1), which stops at the first newline.
    const cmd = isA(ctx) ? raw.split("\n").map((x) => x.trim()).filter(Boolean).join(" ") : raw.split("\n")[0];
    const desc = isA(ctx) ? "Run the unit tests" : "Run the test suite";
    const trailing = `${on(p.showElapsed) ? clock(72, "ct-digits t-caption") : ""}${on(p.tappable) ? chev(16, "icon-strong-base") : ""}`;
    const body = `<span class="ct-avatar ct-avatar-ic">${I("terminal", 16)}</span><span class="ct-lr-text"><span class="t-code ct-ell">${esc(cmd)}</span>${on(p.showDescription) ? `<span class="t-caption ct-ell ct-base">${esc(desc)}</span>` : ""}</span><span class="ct-lr-trail">${trailing}</span>`;
    return wrap(on(p.tappable), "ct-lr", body, `Shell, ${cmd}`);
  };
  const taskSamples = {
    running: ["Find the session store", "explore", "Searching SessionStore", "7 tools", "0:42"],
    waiting: ["Run the test suite", "", "Waiting for input", "", "1:35"],
    done: ["Review the diff", "review", "No blocking issues; two nits in the parser", "4m ago", "1:20"],
    failed: ["Migrate the fixtures", "", "Rate limited", "6m ago", "0:20"],
    stopped: ["Profile the parser", "explore", "Stopped", "8m ago", ""],
  };
  const taskDetail = { running: "text-base", waiting: "warning", done: "text-base", failed: "error", stopped: "error" };
  const iosSpinner = () => `<span class="ct-spin-ios">${Array.from({ length: 8 }, (_, i) => `<i style="--a:${i * 45}deg;--o:${(i + 1) / 8}"></i>`).join("")}</span>`;
  const androidSpinner = () => '<svg class="ct-spin-android" viewBox="0 0 14 14" aria-hidden="true"><circle class="tr" cx="7" cy="7" r="6"></circle><circle class="hd" cx="7" cy="7" r="6"></circle></svg>';
  const taskGlyph = (st, ctx) => {
    if (st === "running") return isA(ctx) ? androidSpinner() : iosSpinner();
    if (st === "waiting") return '<span class="ct-task-dot ct-pulse"></span>';
    if (st === "done") return `<span style="color:var(--text-strong)">${I("check", 16)}</span>`;
    return '<span class="ct-task-bang">!</span>';
  };
  const taskRow = (p, ctx) => {
    const [title, tag, detail, caption, time] = taskSamples[p.state];
    const side = time || caption ? `<span class="ct-task-side">${time ? `<span class="ct-digits t-caption">${esc(time)}</span>` : ""}${caption ? `<span class="t-caption ct-weaker">${esc(caption)}</span>` : ""}</span>` : "";
    const tagHtml = on(p.showTag) && tag ? (Kit.components.tag ? `<span class="ct-tag-host">${Kit.use("tag", { size: "small", tone: "neutral", title: tag }, ctx)}</span>` : `<span class="ct-tag">${esc(tag)}</span>`) : "";
    const body = `<span class="ct-avatar">${taskGlyph(p.state, ctx)}</span><span class="ct-lr-text"><span class="ct-lr-title ct-task-title"><span class="t-heading ct-ell">${esc(title)}</span>${tagHtml}</span>${on(p.showDetail) ? `<span class="t-caption ct-ell" style="color:var(--${taskDetail[p.state]})">${esc(detail)}</span>` : ""}</span><span class="ct-lr-trail">${side}${on(p.tappable) ? chev(16, "icon-strong-base") : ""}</span>`;
    return wrap(on(p.tappable), "ct-lr", body, [title, tag, p.state, detail, caption, time].filter(has).join(", "));
  };

  // Step Rail
  const railData = (android) => ({
    oneRunning: android
      ? [["s", "SessionChatScreen.kt", "", "completed"], ["s", "branchPill", "4 matches", "completed"], ["s", "Running ./gradlew :app:testDebugUnitTest", "", "running", { secs: 12 }]]
      : [["s", "SessionChatView.swift", "", "completed"], ["s", "chipTimeline", "14 matches", "completed"], ["s", "Running xcodebuild test -scheme OpenCode…", "", "running", { secs: 0 }]],
    parallel: android
      ? [["s", "Thought · 3.2s", "", "completed"], ["s", "Reading OpenCodeToolChip.kt", "", "running", { secs: 3 }], ["s", "Searching statusDot", "", "running", { secs: 2, branch: true }], ["c", "explore", "Find where the branch pill is rendered · 6 tools so far", "running", { secs: 64 }]]
      : [["s", "Package.swift", "", "completed"], ["s", "Editing TimelineBlocks.swift", "", "running", { secs: 3 }], ["s", "Searching stepRail", "", "running", { secs: 0, branch: true }], ["c", "explore", "Find every timeline caller · 6 tools so far", "running", { secs: 42 }]],
    folded: android
      ? [["f", 6, "failed"], ["s", "OpenCodeStepRail.kt", "", "completed", { add: 18, del: 4 }], ["s", "Ran ./gradlew :app:assembleDebug", "Exited with code 1", "failed"], ["s", "build.gradle.kts", "", "completed", { add: 2 }], ["t"]]
      : [["f", 3, "failed"], ["s", "TimelineBlocks.swift", "", "completed", { add: 18, del: 4 }], ["s", "Ran swift test", "12 passed", "completed"], ["s", "SessionChatTimeline.swift", "", "completed", { add: 6 }], ["t"]],
    unfolded: android
      ? [["s", "File0.kt", "", "completed"], ["s", "File1.kt", "", "completed"], ["s", "Ran npm run lint", "Exited with code 2", "failed"], ["s", "File3.kt", "", "completed"], ["s", "File4.kt", "", "completed"], ["s", "File5.kt", "", "completed"], ["s", "OpenCodeStepRail.kt", "", "completed", { add: 18, del: 4 }], ["s", "Ran ./gradlew :app:assembleDebug", "Exited with code 1", "failed"], ["s", "build.gradle.kts", "", "completed", { add: 2 }], ["t"]]
      : [["s", "README.md", "", "completed"], ["s", "Ran swift build", "Exited with code 1", "failed"], ["s", "Thought · 2.1s", "", "completed"], ["s", "TimelineBlocks.swift", "", "completed", { add: 18, del: 4 }], ["s", "Ran swift test", "12 passed", "completed"], ["s", "SessionChatTimeline.swift", "", "completed", { add: 6 }], ["t"]],
    finished: android
      ? [["s", "README.md", "", "completed"], ["s", "Ran npm test", "12 passed", "completed"], ["c", "review", "Standards review of the diff", "completed", { ended: 84 }], ["s", "Thought · 1.4s", "", "completed"]]
      : [["s", "Thought · 4.0s", "", "completed"], ["s", "OpenCodeStepRail.swift", "", "completed", { add: 120 }], ["c", "review", "Standards review of #779", "completed"], ["s", "Ran xcodebuild test", "", "interrupted"]],
    declined: [["s", "Ran npm test", "Declined", "interrupted", { note: "Your note: Run the tests with --watch=false instead" }], ["s", "Thought · 2.1s", "", "completed"]],
    pending: [["s", "Package.swift", "", "completed"], ["s", "Running npm test", "", "pending", { secs: 1 }]],
    failedChild: [["s", "Package.swift", "", "completed"], ["c", "explore", "Rate limited", "failed", { ended: 18 }]],
  });
  const railDot = (st, android, live) => {
    if (st === "pending") return `<svg class="ct-rail-pend" viewBox="0 0 8 8" aria-hidden="true"><circle cx="4" cy="4" r="3.5" stroke-dasharray="2 1.5"></circle></svg>`;
    const color = { running: "warning", completed: "success", failed: "error", interrupted: android ? "text-weak" : "text-weaker", weakest: "text-weaker" }[st];
    // iOS pulses every running dot; Android only pulses on a live rail.
    return `<span class="ct-rail-dot ${st === "running" && (!android || live) ? "ct-pulse" : ""}" style="background:var(--${color})"></span>`;
  };
  const railStep = (r, android) => {
    const [, title, detail, st, x = {}] = r;
    const inFlight = st === "running" || st === "pending";
    const elapsed = inFlight && x.secs != null ? clock(x.secs, "ct-rail-time") : "";
    const diffHtml = x.add || x.del ? (android ? `<span class="ct-rail-diff">${x.add ? `<span class="add">+${x.add}</span>` : ""}${x.del ? `<span class="del">−${x.del}</span>` : ""}</span>` : `<span class="ct-rail-diff">${x.add ? `<span class="add">+${x.add}</span>` : ""}${x.add && x.del ? " " : ""}${x.del ? `<span class="del">−${x.del}</span>` : ""}</span>`) : "";
    const word = st === "failed" ? `<span class="ct-rail-word" style="color:var(--error)">failed</span>` : st === "interrupted" ? `<span class="ct-rail-word" style="color:var(--${android ? "text-weak" : "text-weaker"})">stopped</span>` : "";
    // Android shows one trailing item (elapsed, else status, else diff). iOS stacks them.
    const trailing = android ? (elapsed || word || diffHtml) : diffHtml + word + elapsed;
    const note = !android && x.note ? `<span class="ct-rail-note t-caption">${esc(x.note)}</span>` : "";
    return `<span class="ct-rail-stack"><span class="ct-rail-main"><span class="ct-rail-lead"><span class="ct-rail-title t-secondary" style="color:var(--${inFlight ? "text-strong" : "text-weak"})">${esc(title)}</span>${detail ? `<span class="ct-rail-detail t-caption">${esc(detail)}</span>` : ""}</span>${trailing}</span>${note}</span>`;
  };
  const railChild = (r, android) => {
    const [, agent, line, st, x = {}] = r;
    const name = android ? agent[0].toUpperCase() + agent.slice(1) : agent;
    const time = st === "running" && x.secs != null ? clock(x.secs, "ct-rail-time") : x.ended != null ? `<span class="ct-rail-time">${fmt(x.ended)}</span>` : "";
    const nameColor = android && st === "failed" ? "error" : "info";
    const lineColor = !android && st === "failed" ? "error" : "text-weak";
    return `<span class="ct-rail-child"><span class="ct-rail-chead"><span class="t-secondary ct-rail-agent" style="color:var(--${nameColor})">${esc(name)}</span>${time}${chev(14, android ? "icon-base" : "icon-strong-base")}</span>${line ? `<span class="t-caption ct-ell" style="color:var(--${lineColor})">${esc(line)}</span>` : ""}</span>`;
  };
  const railHtml = (key, ctx, live) => {
    const android = isA(ctx);
    const rows = railData(android)[key];
    const items = rows.map((r, i) => {
      const edge = `${i === 0 ? "first" : ""} ${i === rows.length - 1 ? "last" : ""}`;
      const type = r[0];
      if (type === "f") return `<button class="ct-rail-row ct-rail-tap ${edge}" data-ct-unfold="1" aria-label="${r[1]} earlier steps, expands"><span class="ct-rail-line"></span>${railDot(r[2] === "failed" ? "failed" : "weakest", android)}<span class="ct-rail-c"><span class="ct-rail-fold t-secondary">${r[1]} earlier steps${I("chevron_right", 14)}</span></span></button>`;
      if (type === "t") return `<div class="ct-rail-row ${edge}"><span class="ct-rail-line"></span>${railDot("running", android, true)}<span class="ct-rail-c"><span class="ct-rail-think t-secondary">Thinking…</span></span></div>`;
      const st = r[3], x = r[4] ?? {};
      const inner = type === "c" ? railChild(r, android) : railStep(r, android);
      const enter = type === "s" && (st === "running" || st === "pending") ? "ct-rail-enter" : "";
      return `<button class="ct-rail-row ct-rail-tap ${edge} ${x.branch ? "branch" : ""} ${enter}"><span class="ct-rail-line"></span>${x.branch ? '<span class="ct-rail-conn"></span>' : ""}${railDot(st, android, live)}<span class="ct-rail-c">${inner}</span></button>`;
    }).join("");
    return `<div class="ct-rail" data-ct-rail="${key}" data-ct-live="${live ? 1 : 0}">${items}</div>`;
  };

  // Reasoning Row, Working Indicator
  const bodyRole = (ctx) => (isA(ctx) ? "t-secondary" : "t-body");
  const toneVar = (ctx) => (isA(ctx) ? "text-base" : "text-weak");
  const reasoningRow = (p, ctx) => {
    const label = p.state === "working" ? "Thinking" : p.state === "expanded" && has(p.duration) ? `Thought · ${p.duration}` : "Thought";
    const mark = p.state === "working" ? `<span class="ct-wi-dot ct-pulse" style="background:var(--${toneVar(ctx)})"></span>` : p.state === "idle" ? `<span class="ct-wi-check" style="color:var(--${toneVar(ctx)})">${I("check", 12)}</span>` : "";
    const cls = `ct-rs ${p.state === "collapsed" ? "ct-collapsed" : ""}`;
    const body = `${mark}<span class="${bodyRole(ctx)}" style="color:var(--${toneVar(ctx)})">${esc(label)}</span>`;
    return on(p.tappable) && p.state !== "collapsed" ? `<button class="${cls} ct-btn press" aria-label="${esc(label)}">${body}</button>` : `<div class="${cls}" aria-hidden="${p.state === "collapsed"}">${body}</div>`;
  };

  // The app's turn indicator since #1098: every timeline style shares one floating turn pill.
  const arcPath = '<svg class="ct-arc" viewBox="0 0 16 16" aria-hidden="true"><path></path></svg>';
  const pillMark = (phase, ctx) => ({
    working: arcPath,
    done: `<span style="color:var(--text-strong)">${I("check", 16)}</span>`,
    arrow: `<span style="color:var(--warning)">${I("arrow_upward", 16)}</span>`,
    dot: '<span class="ct-tp-dot ct-pulse"></span>',
    failed: "",
  }[phase]);
  const pillTone = { working: "text-base", done: "text-strong", arrow: "warning", dot: "warning", failed: "error" };
  const turnPill = (phase, label, compact, ctx) => {
    const mark = pillMark(phase, ctx);
    const ring = phase === "arrow" || phase === "dot" ? "ct-tp-ring" : "";
    return `<span class="ct-tp float ${ring} ${compact ? "compact" : ""}" role="status" aria-label="${esc(label)}">${mark ? `<span class="ct-tp-m">${mark}</span>` : ""}<span class="ct-tp-l" style="color:var(--${pillTone[phase]})">${esc(label)}</span></span>`;
  };
  const turnStates = {
    working: ["working", "Working"], compactWorking: ["working", "Working", true], compacting: ["working", "Compacting…"], attentionArrow: ["arrow", "Review permission"],
    attentionDot: ["dot", "Waiting for you"], failed: ["failed", "Request failed"], done: ["done", "Done"], compactDone: ["done", "Done", true],
  };
  const workingIndicator = (p, ctx) => {
    if (p.turn === "lifecycle") return `<span class="ct-tp-live" data-ct-turn="1">${turnPill("working", "Working", false, ctx)}</span>`;
    if (p.turn && p.turn !== "none") { const [ph, lb, c] = turnStates[p.turn]; return turnPill(ph, lb, c, ctx); }
    if (p.state === "lifecycle") return `<div class="ct-wi ct-wi-demo" data-ct-wi="1">${wiBody("Working", false, ctx)}</div>`;
    const idle = p.state === "idle";
    const cls = `ct-wi ${p.state === "collapsed" ? "ct-collapsed" : ""} ${on(p.fading) ? "fading" : ""} ${idle ? "idle" : ""}`;
    return `<div class="${cls}" aria-hidden="${p.state === "collapsed"}">${wiBody(p.label, idle, ctx)}</div>`;
  };
  const wiBody = (label, idle, ctx) => `<span class="ct-wi-mark"><span class="ct-wi-dot ct-pulse" style="background:var(--${toneVar(ctx)})"></span><span class="ct-wi-check" style="color:var(--${toneVar(ctx)})">${I("check", 12)}</span></span><span class="ct-wi-label ${bodyRole(ctx)}" style="color:var(--${toneVar(ctx)})">${esc(label)}</span>`;

  // Motion
  const bezier = (x1, y1, x2, y2, t) => {
    let lo = 0, hi = 1, u = t;
    const f = (a, b, v) => 3 * a * v * (1 - v) ** 2 + 3 * b * v * v * (1 - v) + v ** 3;
    for (let i = 0; i < 18; i++) { u = (lo + hi) / 2; if (f(x1, x2, u) < t) lo = u; else hi = u; }
    return f(y1, y2, u);
  };
  const TAU = Math.PI * 2;
  const lerp = (a, b, t) => a + (b - a) * t;
  const pose = (sec) => {
    const rotation = ((sec % 1) / 1) * TAU;
    const phase = (sec % 1.6) / 1.6;
    let dash, offset;
    if (phase < 0.5) { const e = bezier(0.45, 0, 0.3, 1, phase / 0.5); dash = lerp(0.28, 0.72, e); offset = lerp(0, 0.12, e); }
    else { const e = bezier(0.45, 0, 0.3, 1, (phase - 0.5) / 0.5); dash = lerp(0.72, 0.28, e); offset = lerp(0.12, 1, e); }
    return { start: rotation + offset * TAU, sweep: dash * TAU };
  };
  const arcPoints = (p, n = 36) => Array.from({ length: n + 1 }, (_, i) => { const a = p.start + (p.sweep * i) / n; return [8 + 6 * Math.cos(a), 8 + 6 * Math.sin(a)]; });
  const toD = (pts) => "M" + pts.map(([x, y]) => `${x.toFixed(3)} ${y.toFixed(3)}`).join("L");
  const checkPoints = (n = 36) => {
    const line = [[2.667, 8], [6, 11.333], [13.333, 4]];
    const lens = [Math.hypot(3.333, 3.333), Math.hypot(7.333, 7.333)], total = lens[0] + lens[1];
    return Array.from({ length: n + 1 }, (_, i) => {
      const d = (i / n) * total;
      if (d <= lens[0]) { const t = d / lens[0]; return [lerp(line[0][0], line[1][0], t), lerp(line[0][1], line[1][1], t)]; }
      const t = (d - lens[0]) / lens[1];
      return [lerp(line[1][0], line[2][0], t), lerp(line[1][1], line[2][1], t)];
    });
  };
  const reduced = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
  let rafOn = false;
  const frame = (now) => {
    const arcs = document.querySelectorAll(".ct-arc:not(.ct-morphing) path");
    if (!arcs.length) { rafOn = false; return; }
    const p = reduced() ? { start: -Math.PI / 2, sweep: TAU * 0.6 } : pose(now / 1000);
    const d = toD(arcPoints(p));
    arcs.forEach((a) => a.setAttribute("d", d));
    requestAnimationFrame(frame);
  };
  const startArcs = () => { if (!rafOn && document.querySelector(".ct-arc")) { rafOn = true; requestAnimationFrame(frame); } };

  const morphToCheck = (svg, done) => {
    const path = svg.querySelector("path");
    const from = arcPoints(pose(performance.now() / 1000)), to = checkPoints();
    svg.classList.add("ct-morphing");
    const t0 = performance.now();
    const step = (now) => {
      if (!svg.isConnected) return;
      const ms = now - t0, t = bezier(0.3, 0, 0.2, 1, Math.min(1, ms / 540));
      path.setAttribute("d", toD(from.map(([x, y], i) => [lerp(x, to[i][0], t), lerp(y, to[i][1], t)])));
      path.style.strokeWidth = lerp(1.3, 1, t);
      path.style.stroke = `color-mix(in srgb, var(--text-strong) ${t * 100}%, var(--text-base))`;
      const pop = (ms - 110) / 240;
      svg.style.transform = pop > 0 && pop < 1 ? `scale(${1 + 0.09 * Math.sin(pop * Math.PI)})` : "";
      if (ms < 540) requestAnimationFrame(step); else done?.();
    };
    requestAnimationFrame(step);
  };
  const runTurn = (host, ctx) => {
    const wait = (ms, fn) => setTimeout(() => host.isConnected && fn(), ms);
    const loop = () => {
      host.innerHTML = turnPill("working", "Working", false, ctx);
      host.style.opacity = 1; host.style.transform = "";
      startArcs();
      wait(2600, () => {
        const pill = host.querySelector(".ct-tp"), lb = pill.querySelector(".ct-tp-l");
        const w = lb.offsetWidth;
        lb.style.width = w + "px";
        lb.innerHTML = '<span class="ct-roll out">Working</span><span class="ct-roll in">Done</span>';
        lb.style.color = "var(--text-strong)";
        const probe = document.createElement("span");
        probe.className = "ct-tp-l"; probe.style.cssText = "position:absolute;visibility:hidden;width:auto"; probe.textContent = "Done"; pill.append(probe);
        const target = probe.offsetWidth; probe.remove();
        requestAnimationFrame(() => { lb.classList.add("rolling"); lb.style.width = target + "px"; });
        morphToCheck(pill.querySelector(".ct-arc"));
        wait(3000, () => { host.style.opacity = 0; host.style.transform = "scale(.96)"; wait(240, () => wait(500, loop)); });
      });
    };
    loop();
  };
  const runWorking = (node, ctx) => {
    const wait = (ms, fn) => setTimeout(() => node.isConnected && fn(), ms);
    const loop = () => {
      node.classList.remove("idle", "ct-collapsed");
      node.querySelector(".ct-wi-label").textContent = "Working";
      wait(2400, () => {
        node.classList.add("idle");
        node.querySelector(".ct-wi-label").textContent = "Idle";
        wait(1600, () => { node.classList.add("ct-collapsed"); wait(900, loop); });
      });
    };
    loop();
  };

  // One clock for every running shimmer, as on native.
  const syncClock = (stage) => stage.querySelectorAll(".ct-chip-run .ct-chip-lb, .ct-chip-run .ct-chip-ic, .ct-ac-work").forEach((n) => n.getAnimations().forEach((a) => { a.startTime = 0; }));

  let tickerOn = false;
  const startTicker = () => {
    if (tickerOn) return;
    tickerOn = true;
    setInterval(() => document.querySelectorAll("[data-ct-el]").forEach((n) => {
      n.dataset.ctT0 ??= Date.now();
      n.textContent = fmt(Number(n.dataset.ctEl) + Math.floor((Date.now() - Number(n.dataset.ctT0)) / 1000));
    }), 1000);
  };

  const mountTools = (stage, ctx) => {
    startTicker();
    startArcs();
    stage.querySelectorAll("[data-ct-el]").forEach((n) => { n.dataset.ctT0 ??= Date.now(); });
    stage.querySelectorAll(".ct-chip-run:not([data-ct-bound])").forEach((chip) => {
      chip.dataset.ctBound = "1";
      setTimeout(() => { chip.classList.add("revealed"); requestAnimationFrame(() => syncClock(stage)); }, 250);
    });
    stage.querySelectorAll("[data-ct-unfold]:not([data-ct-bound])").forEach((b) => {
      b.dataset.ctBound = "1";
      b.addEventListener("click", () => {
        const rail = b.closest(".ct-rail");
        if (rail) rail.outerHTML = railHtml("unfolded", ctx, rail.dataset.ctLive === "1");
      });
    });
    stage.querySelectorAll("[data-ct-note]:not([data-ct-bound])").forEach((b) => {
      b.dataset.ctBound = "1";
      b.addEventListener("click", () => {
        const host = b.closest(".ct-ac")?.parentElement;
        if (!host) return;
        let note = host.querySelector(":scope > .ct-ac-note");
        if (!note) { note = document.createElement("div"); note.className = "ct-ac-note t-caption"; host.append(note); }
        note.textContent = b.dataset.ctNote;
      });
    });
    stage.querySelectorAll("[data-ct-cycle]:not([data-ct-cycle-bound])").forEach((foot) => {
      foot.dataset.ctCycleBound = "1";
      const samples = activitySamples(isA(ctx));
      const seq = [samples.oneTool, samples.justFinished, { idle: "Thinking…" }];
      let i = 0;
      const tick = () => {
        if (!foot.isConnected) return;
        const body = foot.querySelector(".ct-ac-fbody");
        body.style.opacity = 0;
        setTimeout(() => {
          if (!foot.isConnected) return;
          i = (i + 1) % seq.length;
          body.innerHTML = acFooterBody(seq[i], ctx);
          body.querySelectorAll("[data-ct-el]").forEach((n) => { n.dataset.ctT0 = Date.now(); });
          body.style.opacity = 1;
          setTimeout(tick, 2200);
        }, 200);
      };
      setTimeout(tick, 2200);
    });
    stage.querySelectorAll("[data-ct-turn]:not([data-ct-bound])").forEach((h) => { h.dataset.ctBound = "1"; runTurn(h, ctx); });
    stage.querySelectorAll("[data-ct-wi]:not([data-ct-bound])").forEach((h) => { h.dataset.ctBound = "1"; runWorking(h, ctx); });
    requestAnimationFrame(() => syncClock(stage));
  };

  const CHIP_VARIANTS = ["file", "edit", "command", "agent", "skill", "explore", "more"];
  const STATUSES = ["completed", "running", "pending", "error", "interrupted"];
  const titleCase = (s) => s[0].toUpperCase() + s.slice(1);

  Kit.define([
    {
      id: "tool-call-card", doc: "tool-call-card", align: "stretch", pad: true,
      params: [sel("status", ["completed", "pending", "running", "error", "interrupted"]), text("title", "Called edit"), text("subtitle", "OpenCodeListItem.swift"), text("additions", "12"), text("deletions", "3"), text("errorText", "", "error text"), text("note", "", "decline note"), bool("tappable", false), imageParam],
      states: [
        ["Pending", { status: "pending", title: "Called bash", subtitle: "npm test", additions: "", deletions: "" }],
        ["Running", { status: "running", title: "Called task", subtitle: "Explore codebase", additions: "", deletions: "" }],
        ["Completed", {}],
        ["Tappable", { tappable: true }],
        ["Title only", { title: "Called skill", subtitle: "", additions: "", deletions: "" }],
        ["Error", { status: "error", title: "Called webfetch", subtitle: "", additions: "", deletions: "", errorText: "Request failed with status 403" }],
        ["Interrupted", { status: "interrupted", title: "Called shell", subtitle: "./deploy.sh", additions: "", deletions: "", errorText: "Stopped" }],
        ["Declined with note", { status: "interrupted", title: "Called shell", subtitle: "npm test", additions: "", deletions: "", errorText: "Declined", note: "Your note: Run the tests with --watch=false instead" }],
        ["With tool images", { title: "Called screenshot", subtitle: "Home screen", additions: "", deletions: "", images: "2" }],
      ],
      sheet: [
        { title: "Status", kind: "stack", inset: true, items: [
          ["Pending", { status: "pending", title: "Called bash", subtitle: "npm test", additions: "", deletions: "" }],
          ["Running", { status: "running", title: "Called task", subtitle: "Explore codebase", additions: "", deletions: "" }],
          ["Completed, tappable", { tappable: true }],
          ["Error", { status: "error", title: "Called webfetch", subtitle: "", additions: "", deletions: "", errorText: "Request failed with status 403" }],
          ["Interrupted", { status: "interrupted", title: "Called shell", subtitle: "./deploy.sh", additions: "", deletions: "", errorText: "Stopped" }],
        ] },
        { title: "Content", kind: "stack", inset: true, items: [
          ["Title only", { title: "Called skill", subtitle: "", additions: "", deletions: "" }],
          ["Diff counts only", { subtitle: "" }],
          ["Declined with a note", { status: "interrupted", title: "Called shell", subtitle: "npm test", additions: "", deletions: "", errorText: "Declined", note: "Your note: Run the tests with --watch=false instead" }],
        ] },
        { title: "Tool images under the card", kind: "stack", inset: true, items: [["Two images", { title: "Called screenshot", subtitle: "Home screen", additions: "", deletions: "", images: "2" }]] },
      ],
      tokens: [["toolCallCard.cornerRadius", 8], ["toolCallCard.paddingHorizontal", 12], ["toolCallCard.paddingVertical", 10], ["toolCallCard.statusDotSize", 6], ["toolCallCard.statusDotSpacing", 10], ["toolCallCard.statusDotTopPadding", 6], ["toolCallCard.contentSpacing", 4], ["toolCallCard.metaRowSpacing", 8], ["fill", "surface-raised-base"]],
      source: { ios: "Components/Chat/OpenCodeToolCallCard.swift", android: "ui/components/chat/OpenCodeToolCallCard.kt" },
      render: toolCard, mount: mountTools,
    },
    {
      id: "tool-chip", align: "center", pad: true,
      params: [sel("variant", CHIP_VARIANTS), sel("status", STATUSES), bool("trailing", false, "trailing text"), bool("tappable", true), imageParam],
      states: [...CHIP_VARIANTS.map((v) => [titleCase(v), { variant: v }]), ...STATUSES.slice(1).map((s) => [titleCase(s), { variant: "command", status: s }]), ["Trailing text", { variant: "command", trailing: true }], ["With tool images", { variant: "command", images: "3" }]],
      sheet: [
        ...STATUSES.map((s) => ({ title: titleCase(s), kind: "flow", items: CHIP_VARIANTS.map((v) => ["", { variant: v, status: s }]) })),
        { title: "Trailing text", kind: "flow", items: [["", { variant: "command", trailing: true }], ["", { variant: "agent", trailing: true }], ["", { variant: "command", status: "running", trailing: true }]] },
        { title: "Tool images under the paragraph", kind: "stack", inset: true, items: [["Three images", { variant: "command", images: "3" }]] },
      ],
      tokens: [["toolChip.cornerRadius", 8], ["toolChip.paddingBeforeIcon", 8], ["toolChip.paddingHorizontal", 10], ["toolChip.paddingVertical", 6], ["toolChip.contentSpacing", 6], ["toolChip.iconSize", 12], ["toolChip.minHeight", 36], ["toolChip.minTapTarget", "44 iOS, 48 Android"], ["toolChip.agentTile", "18, radius 5, info 14%"], ["toolChip.runningRevealDelay", "250ms"], ["toolChip.runningCycle", "1300ms"], ["toolChip.shimmerHalfWidth", 0.3], ["toolChip.runningIconMinOpacity", 0.35]],
      spec: "## Intent\nCompact evidence of one tool call under a chip-timeline paragraph.\n\n## Public API\n- `variant`: file (with optional diff counts), command, agent, skill, explore, more.\n- `status`: pending, running, completed, error, interrupted. The more variant ignores it.\n- `trailingText`: optional, such as an elapsed time.\n- `action` / `onClick`: optional. Tappable chips reach a 44pt (iOS) or 48dp (Android) target without growing.\n\n## Behavior\nA running chip looks idle for 250 ms so fast commands never flash. It then sweeps a warning band across the label every 1.3 s while the icon breathes between 100% and 35%. All running chips share one clock. Pending chips have a dashed outline. File labels truncate in the middle, other labels at the tail.",
      source: { ios: "Components/Chat/OpenCodeToolChip.swift", android: "ui/components/chat/OpenCodeToolChip.kt" },
      render: toolChip, mount: mountTools,
    },
    {
      id: "activity-card", align: "stretch", pad: true,
      params: [sel("sample", ["oneTool", "twoAgents", "justFinished", "waiting", "settled", "settledAgents", "settledFailure", "settledStopped"]), bool("cycle", false, "cycle footer tool"), imageParam],
      states: [["One running tool", { sample: "oneTool" }], ["Footer crossfade", { sample: "oneTool", cycle: true }], ["Two agents, +1", { sample: "twoAgents" }], ["Last tool just finished", { sample: "justFinished" }], ["Waiting for agents", { sample: "waiting" }], ["Settled", { sample: "settled" }], ["Settled with agents", { sample: "settledAgents" }], ["Settled, failed", { sample: "settledFailure" }], ["Settled, stopped", { sample: "settledStopped" }], ["With tool images", { sample: "settled", images: "4" }]],
      sheet: [
        { title: "Live", kind: "stack", inset: true, items: [["One running tool, footer crossfade", { sample: "oneTool", cycle: true }], ["Two agents, +1", { sample: "twoAgents" }], ["Last tool just finished", { sample: "justFinished" }], ["Waiting for agents", { sample: "waiting" }]] },
        { title: "Settled", kind: "stack", inset: true, items: [["Completed", { sample: "settled" }], ["With agents", { sample: "settledAgents" }], ["Failed", { sample: "settledFailure" }], ["Stopped", { sample: "settledStopped" }]] },
        { title: "Tool images under the run", kind: "stack", inset: true, items: [["Four images", { sample: "settled", images: "4" }], ["More than four", { sample: "justFinished", images: "6" }]] },
      ],
      tokens: [["activityCard.cornerRadius", 8], ["activityCard.paddingHorizontal", 12], ["activityCard.paddingVertical", 10], ["activityCard.summaryBottomPadding", 8], ["activityCard.tapRowMinHeight", "44 iOS, 48 Android"], ["activityCard.iconSize", 16], ["activityCard.footerPadding", "12 top, 13 bottom"], ["activityCard.agentTile", "20, radius 6"], ["activityCard.footerCrossfade", "200ms"], ["activityCard.shimmerCycle", "1600ms iOS, 1300ms Android"], ["border", "icon-weak-base"]],
      spec: "## Intent\nOne card per run of an agent's steps.\n\n## Public API\n- `state`: live (with start time) or settled (worst status and step count).\n- `summary`, `agents`, `current`, `idleText`, `onAgentTap`, `onOpen`.\n\n## Behavior\nLive shows a shimmering Working header with elapsed time, a summary, one row per running child session, and a footer band with the latest tool. A new tool crossfades over 200 ms, while +N and elapsed change in place. Settled shows one line with the worst-status dot, then any child sessions. Nothing expands inline.",
      source: { ios: "Components/Chat/OpenCodeActivityCard.swift", android: "ui/components/chat/OpenCodeActivityCard.kt" },
      render: activityCard, mount: mountTools,
    },
    {
      id: "child-session-row", doc: "child-session-row", align: "stretch", pad: true,
      params: [text("title", "explore"), text("caption", "background"), text("subtitle", "Standards review of OPE-144"), text("elapsed", "1:04"), sel("status", ["none", "running", "waiting for input", "done", "failed", "stopped"]), bool("showCaption", false, "caption"), bool("showSubtitle", true, "subtitle"), bool("showElapsed", true, "elapsed"), bool("tappable", true)],
      states: [["Default", {}], ["Background", { showCaption: true, subtitle: "Find auth bugs", elapsed: "0:12" }], ["Running", { status: "running" }], ["Waiting for input", { status: "waiting for input" }], ["Done", { status: "done" }], ["Failed", { status: "failed" }], ["Stopped", { status: "stopped" }], ["Display only", { title: "Task", subtitle: "Investigate flaky tests", showElapsed: false, tappable: false }]],
      sheet: [
        { title: "Status", kind: "stack", inset: true, items: [["None", {}], ["Running", { status: "running" }], ["Waiting for input", { status: "waiting for input" }], ["Done", { status: "done" }], ["Failed", { status: "failed" }], ["Stopped", { status: "stopped" }]] },
        { title: "Content", kind: "stack", inset: true, items: [["Background caption", { showCaption: true, subtitle: "Find auth bugs", elapsed: "0:12" }], ["Title only", { showSubtitle: false, showElapsed: false }], ["Display only", { title: "Task", subtitle: "Investigate flaky tests", showElapsed: false, tappable: false }]] },
      ],
      tokens: [["childSessionRow.cornerRadius", 8], ["childSessionRow.paddingHorizontal", 12], ["childSessionRow.paddingVertical", 10], ["childSessionRow.avatarSize", 32], ["childSessionRow.avatarGlyphSize", 14], ["childSessionRow.contentSpacing", 12], ["childSessionRow.titleRowSpacing", 8], ["childSessionRow.textStackSpacing", 2], ["childSessionRow.trailingSpacing", "6 iOS, 12 Android"], ["childSessionRow.chevronSize", 16]],
      source: { ios: "Components/Chat/OpenCodeChildSessionRow.swift", android: "ui/components/chat/OpenCodeChildSessionRow.kt" },
      render: childRow, mount: mountTools,
    },
    {
      id: "reasoning-row", align: "stretch", pad: true,
      params: [sel("state", ["working", "expanded", "idle", "collapsed"]), text("duration", "1.2s"), bool("tappable", false)],
      states: [["Working", { state: "working" }], ["Expanded with duration", { state: "expanded", tappable: true }], ["Expanded", { state: "expanded", duration: "", tappable: true }], ["Idle", { state: "idle" }], ["Collapsed", { state: "collapsed" }]],
      sheet: [{ title: "State", kind: "stack", inset: true, items: [["Working", { state: "working" }], ["Expanded with duration", { state: "expanded", tappable: true }], ["Expanded", { state: "expanded", duration: "", tappable: true }], ["Idle", { state: "idle" }], ["Collapsed (zero height)", { state: "collapsed" }]] }],
      tokens: [["reasoningRow.leadingPadding", 4], ["reasoningRow.topPadding", 12], ["reasoningRow.bottomPadding", 8], ["reasoningRow.spacing", 8], ["reasoningRow.dot", 6], ["reasoningRow.checkIcon", 12], ["reasoningRow.pulse", "900ms, 1 to 0.35"], ["type", "body iOS, bodySecondary Android"]],
      spec: "## Intent\nShows a model thought in the timeline.\n\n## Public API\n- `state`: working, idle, expanded, collapsed.\n- `durationLabel`: shown when expanded.\n- `action` / `onClick`: opens the full thought.\n\n## Behavior\nWorking pulses a 6pt dot and says Thinking. Idle shows a check and says Thought. Expanded says Thought or Thought · 1.2s. Collapsed has zero height and is hidden from accessibility.",
      source: { ios: "Components/Chat/OpenCodeReasoningRow.swift", android: "ui/components/chat/OpenCodeReasoningRow.kt" },
      render: reasoningRow, mount: mountTools,
    },
    {
      id: "shell-row", align: "stretch", pad: true,
      params: [sel("command", ["short", "long", "multiline"]), bool("showDescription", true, "description"), bool("showElapsed", true, "elapsed"), bool("tappable", true)],
      states: [["Running", {}], ["Long command", { command: "long", showDescription: false }], ["Multi-line command", { command: "multiline" }], ["No description", { showDescription: false }], ["No elapsed", { showElapsed: false }], ["Display only", { tappable: false, showElapsed: false, showDescription: false }]],
      sheet: [{ title: "Content", kind: "stack", inset: true, items: [["Running", {}], ["Long command", { command: "long", showDescription: false }], ["Multi-line command", { command: "multiline" }], ["No elapsed", { showElapsed: false }], ["Display only", { tappable: false, showElapsed: false, showDescription: false }]] }],
      tokens: [["shellRow.cornerRadius", 8], ["shellRow.paddingHorizontal", 12], ["shellRow.paddingVertical", 10], ["shellRow.avatarSize", 32], ["shellRow.glyphSize", 16], ["shellRow.contentSpacing", 12], ["shellRow.textStackSpacing", 2], ["shellRow.trailingSpacing", "6 iOS, 12 Android"], ["shellRow.chevronSize", 16]],
      spec: "## Intent\nA running shell command in a list, next to Child Session Row.\n\n## Public API\n- `command` (required), `description`, `elapsed`, `action` / `onClick`.\n\n## Behavior\nA terminal glyph sits in a 32pt raised circle. The command is one monospaced line that truncates at the tail. The timer never truncates. Tappable rows show a chevron.",
      source: { ios: "Components/Chat/OpenCodeShellRow.swift", android: "ui/components/chat/OpenCodeShellRow.kt" },
      render: shellRow, mount: mountTools,
    },
    {
      id: "step-rail", align: "stretch", pad: true,
      params: [sel("sample", ["oneRunning", "parallel", "folded", "finished", "declined", "pending", "failedChild"]), bool("live", true), imageParam],
      states: [["One running", { sample: "oneRunning" }], ["Parallel + child", { sample: "parallel" }], ["Folded, failed", { sample: "folded" }], ["Finished", { sample: "finished", live: false }], ["Declined with note", { sample: "declined", live: false }], ["Pending", { sample: "pending" }], ["Failed child", { sample: "failedChild", live: false }], ["With tool images", { sample: "finished", live: false, images: "2" }]],
      sheet: [
        { title: "Live", kind: "stack", inset: true, items: [["One running", { sample: "oneRunning" }], ["Parallel + child", { sample: "parallel" }], ["Pending", { sample: "pending" }], ["Folded, failed, thinking (tap the fold)", { sample: "folded" }]] },
        { title: "Settled", kind: "stack", inset: true, items: [["Finished", { sample: "finished", live: false }], ["Declined with a note", { sample: "declined", live: false }], ["Failed child", { sample: "failedChild", live: false }]] },
        { title: "Tool images under the run", kind: "stack", inset: true, items: [["Two images", { sample: "finished", live: false, images: "2" }]] },
      ],
      tokens: [["stepRail.leadingInset", "6 iOS, 4 Android"], ["stepRail.gutterWidth", 16], ["stepRail.branchIndent", 14], ["stepRail.lineX", 4], ["stepRail.lineWidth", 1.5], ["stepRail.dotSize", 8], ["stepRail.rowMinHeight", 36], ["stepRail.rowVerticalPadding", 4], ["stepRail.pressBleed", 8], ["stepRail.pulse", "900ms, 1 to 0.35"], ["stepRail.enterFade", "200ms"], ["stepRail.childRadius", "10 iOS, 8 Android"]],
      spec: "## Intent\nAn agent's work as one short line per step on a vertical rail.\n\n## Public API\n- `steps`: step, child session, fold, thinking.\n- `isLive`, `thinking`, `onStepTap`, `onFoldTap`, `onChildSessionTap`.\n\n## Behavior\nRunning dots pulse and tick m:ss. A running step after another running step branches off the rail. A step that joins running fades in over 200 ms. The fold row expands earlier steps.",
      source: { ios: "Components/Chat/OpenCodeStepRail.swift", android: "ui/components/chat/OpenCodeStepRail.kt" },
      render: (p, ctx) => withImages(railHtml(p.sample, ctx, on(p.live)), p, ctx), mount: mountTools,
    },
    {
      id: "task-row", align: "stretch", pad: true,
      params: [sel("state", ["running", "waiting", "done", "failed", "stopped"]), bool("showTag", true, "tag"), bool("showDetail", true, "detail"), bool("tappable", true)],
      states: [["Running", { state: "running" }], ["Waiting for input", { state: "waiting" }], ["Done", { state: "done" }], ["Failed", { state: "failed" }], ["Stopped", { state: "stopped" }], ["No detail", { state: "running", showDetail: false }], ["Display only", { state: "done", tappable: false }]],
      sheet: [
        { title: "State", kind: "stack", inset: true, items: [["Running", { state: "running" }], ["Waiting for input", { state: "waiting" }], ["Done", { state: "done" }], ["Failed", { state: "failed" }], ["Stopped", { state: "stopped" }]] },
        { title: "Content", kind: "stack", inset: true, items: [["No tag", { state: "done", showTag: false }], ["No detail", { state: "running", showDetail: false }], ["Display only", { state: "done", tappable: false }]] },
      ],
      tokens: [["taskRow.cornerRadius", 8], ["taskRow.paddingHorizontal", 12], ["taskRow.paddingVertical", 10], ["taskRow.glyphFrame", 32], ["taskRow.iconSize", 16], ["taskRow.dotSize", 8], ["taskRow.contentSpacing", 12], ["taskRow.titleRowSpacing", 6], ["taskRow.textStackSpacing", 2], ["taskRow.trailingSpacing", "6 iOS, 12 Android"], ["taskRow.chevronSize", 16]],
      spec: "## Intent\nA background task, such as a subagent, in a list.\n\n## Public API\n- `title`, `state` (running, waitingForInput, done, failed, stopped), `tag`, `detail`, `detailCaption`, `time`, `action` / `onClick`.\n\n## Behavior\nThe state sets the leading glyph and detail tone. Time sits over the caption in a trailing column that never truncates.",
      source: { ios: "Components/Chat/OpenCodeTaskRow.swift", android: "ui/components/chat/OpenCodeTaskRow.kt" },
      render: taskRow, mount: mountTools,
    },
    {
      id: "working-indicator", align: "stretch", pad: true,
      params: [text("label", "Working"), sel("state", ["working", "idle", "expanded", "collapsed", "lifecycle"]), bool("fading", false), sel("turn", ["none", "lifecycle", ...Object.keys(turnStates)], "none", "turn pill")],
      states: [["Working", { state: "working" }], ["Idle", { label: "Idle", state: "idle" }], ["Expanded", { label: "Retrying", state: "expanded" }], ["Fading", { label: "Idle", state: "idle", fading: true }], ["Collapsed", { label: "Idle", state: "collapsed" }], ["Working to idle", { state: "lifecycle" }], ["Turn pill: Working to Done", { turn: "lifecycle" }], ...Object.entries(turnStates).map(([k, v]) => [`Turn pill: ${v[1]}${v[2] ? ", compact" : ""}`, { turn: k }])],
      sheet: [
        { title: "Row states", kind: "stack", inset: true, items: [["Working", { state: "working" }], ["Idle", { label: "Idle", state: "idle" }], ["Expanded", { label: "Retrying", state: "expanded" }], ["Fading", { label: "Idle", state: "idle", fading: true }], ["Collapsed (zero height)", { label: "Idle", state: "collapsed" }], ["Live: working, idle, collapse", { state: "lifecycle" }]] },
        { title: "Turn pill, the app's indicator in every timeline style", kind: "flow", items: [["", { turn: "lifecycle" }], ...Object.keys(turnStates).map((k) => ["", { turn: k }])] },
      ],
      tokens: [["workingIndicator.rowHeight", 40], ["workingIndicator.leadingPadding", 4], ["workingIndicator.spacing", 8], ["workingIndicator.markBox", 12], ["pulsingDot.size", 6], ["pulsingDot.cycle", "900ms, 1 to 0.35"], ["workingIndicator.idleTransition", "220ms"], ["workingIndicator.fadingOpacity", 0.35], ["turnPill.minHeight", 36], ["turnPill.paddingHorizontal", "14, compact 10"], ["turnPill.iconSize", 16], ["turnPill.iconSpacing", 6], ["turnPill.settle", "500ms"], ["turnPill.doneHold", "3000ms"], ["turnPill.leave", "240ms, scale 0.96"], ["turnPill.spinner", "1s rotation, 1.6s breathe"], ["turnPill.morph", "540ms"]],
      spec: "## Intent\nA working, retrying or compacting status row.\n\n## Public API\n- `label`, `state` (working, idle, expanded, collapsed), `fading`.\n\n## Behavior\nWorking pulses a 6pt dot. Idle swaps it for a check over 220 ms. Collapsed has zero height.\n\n## Turn indicator\nSince #1098 Session Chat shows one floating turn pill in every timeline style, and no screen uses this row. The pill spins while working, holds the last label for 500 ms after the turn ends, bends the spinner into a check, holds Done for 3 s, then fades over 240 ms. The pill is shown here for context. Its home is Floating Pill.",
      source: { ios: "Components/Chat/OpenCodeWorkingIndicator.swift", android: "ui/components/chat/OpenCodeWorkingIndicator.kt" },
      render: workingIndicator, mount: mountTools,
    },
  ]);
})();
