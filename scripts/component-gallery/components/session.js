// Component group: session. Compose other groups with Kit.use(id, props).
(() => {
  const { esc, I, IF, sel, text, bool, ACCENTS } = Kit;
  // Nested components need the platform of the frame being rendered.
  let C = Kit.ctx;
  const live = (render) => (p, ctx) => { C = ctx; return render(p, ctx); };
  const button = (p) => Kit.use("button", p, C);
  const badge = (p) => Kit.use("choice-badge", p, C);
  const round = (icon, extra = {}) => button({ variant: "iconCircle", contrast: "low", icon, ...extra });
  // iOS uses the `subtle` contrast for quiet composer actions; Android has no subtle and uses Low.
  const quiet = (icon, extra = {}) => round(icon, { contrast: C.platform === "ios" ? "subtle" : "low", ...extra });
  const compact = (title, contrast = "high", disabled = false) => button({ variant: "compactText", contrast, title, disabled });
  const spinner = () => `<span class="sc-spin" aria-hidden="true"></span>`;

  /* ---------- Session Composer ---------- */
  const OPTIONS = [["Minimal patch", "Touch only the failing path"], ["Broader refactor", "Clean up nearby callers too"], ["Investigate first", "Gather more evidence before changing code"]];
  const SCENARIOS = {
    empty: { kind: "prompt", text: "" },
    prompt: { kind: "prompt", text: "Summarize the current changes" },
    mentions: { kind: "prompt", html: `Ask <span class="sc-mention">@explore</span> about this` },
    active: { kind: "prompt", text: "Summarize the current changes", stop: true },
    attachments: { kind: "prompt", text: "", files: [["requirements.pdf", "pdf"], ["notes.md", "text"]] },
    revert: { kind: "prompt", text: "Add a dark mode toggle to Settings", revert: true },
    shell: { kind: "prompt", html: `<span class="sc-mention">!</span>git status`, shell: true },
    sideQuestion: { kind: "prompt", text: "What did we change so far?", stop: true, btw: true },
    commandHint: { kind: "prompt", androidOnly: true, html: `/goal <span class="sc-hint">Describe what “done” looks like…</span>`, noSend: true },
    limitNote: { kind: "prompt", text: "/goal " + "Keep the public API the same. ".repeat(4), noSend: true, note: "4,312 / 4,000 characters · shorten the objective to send", noteError: true },
    childLockout: { kind: "child" },
    permission: { kind: "permission" },
    permissionEdit: { kind: "permission", edit: true },
    permissionNote: { kind: "note" },
    queued: { kind: "permission", count: 3 },
    subagentPermission: { kind: "permission", position: 2, count: 3, by: ["G", "general · subagent wants to run: npm test"] },
    submitting: { kind: "permission", submitting: true },
    error: { kind: "permission", error: "The permission response failed. Try again." },
    question: { kind: "question", selected: ["Minimal patch"], submit: true },
    questionWizard: { kind: "question", wizard: true, selected: ["Minimal patch"], next: true },
    questionWizardLast: { kind: "question", wizard: true, step: 1, header: "Tests", question: "Which tests should we prioritize?", selected: ["Minimal patch"], submit: true },
    multiSelect: { kind: "question", multiple: true, noCustom: true, selected: ["Minimal patch", "Investigate first"], submit: true, hint: "Select all that apply" },
    multiSelectBounded: { kind: "question", multiple: true, noCustom: true, selected: ["Minimal patch", "Investigate first"], submit: true, hint: "Select up to 2" },
    custom: { kind: "question", selected: [], custom: "Ship a focused fix tonight", submit: true },
    questionSubmitting: { kind: "question", selected: ["Minimal patch"], submit: true, submitting: true },
    questionError: { kind: "question", selected: ["Minimal patch"], submit: true, error: "The question response failed. Try again." },
    subagentQuestion: { kind: "question", selected: ["Minimal patch"], submit: true, position: 1, count: 2, by: ["E", "explore · subagent asks"] },
  };

  const attribution = ([letter, summary]) => `<div class="sc-attr"><span class="sc-attr-l">${esc(letter)}</span><span class="sc-attr-t">${esc(summary)}</span>${compact("Open", "low")}</div>`;
  const pager = (position, count, off) => count > 1 ? `<span class="sc-pager">${round("arrow_back", { disabled: off || position <= 1 })}<span class="t-caption">${position} of ${count}</span>${round("arrow_forward", { disabled: off || position >= count })}</span>` : "";
  const dots = (position, count) => count > 1 ? `<div class="sc-dots">${Kit.use("page-indicator", { pages: String(count), current: String(position - 1) }, C)}</div>` : "";

  const promptBody = (s, p) => {
    const hasText = !!(s.text || s.html);
    const sendOn = !s.noSend && (hasText || !!s.files);
    const chips = s.btw ? badge({ variant: "glass", label: "/btw", selected: true })
      : s.shell ? badge({ variant: "glass", label: "Shell", selected: true })
      : ["Claude Sonnet 4", "Build", "Default"].map((label) => badge({ variant: "glass", label, selected: false })).join("");
    const placeholder = s.btw ? "Side question, not added to chat" : "Ask anything...";
    const field = hasText ? (s.html ?? esc(s.text)) : `<span class="ph">${placeholder}</span>`;
    const revert = s.revert ? `<div class="sc-chip sc-revert"><span class="sc-chip-i">${I("undo")}</span><span class="sc-chip-t"><b>Reverting this message</b><small>REVERT</small></span><button class="sc-x" aria-label="Cancel revert">${I("close", 20)}</button></div>` : "";
    const files = (s.files ?? []).map(([filename, variant]) => Kit.use("attachment", { mode: "chip", variant, filename }, C)).join("");
    const send = button({ variant: "iconCircle", contrast: s.btw ? "high" : "accent", icon: "arrow_upward", disabled: !sendOn });
    return `${revert || files ? `<div class="sc-chips-stack">${revert}${files}</div>` : ""}
      <div class="sc-field">${field}</div>
      ${s.note ? `<div class="sc-note ${s.noteError ? "err" : ""}">${esc(s.note)}</div>` : ""}
      <div class="sc-pickers">${chips}</div>
      <div class="sc-actions"><span class="sc-group">${quiet("add", { disabled: !!(s.shell || s.btw) })}${quiet("slash")}${p.dictation ? quiet("mic") : ""}</span><span class="sc-group">${s.stop ? round("stop") : ""}${send}</span></div>`;
  };

  const permissionBody = (s) => {
    const off = !!s.submitting;
    const position = s.position ?? 1, count = s.count ?? 1;
    const lines = s.edit
      ? [["README.md", 1, 1], ["notes/todo.md", 4, 0]].map(([f, a, d]) => `<div class="sc-code">${f}  <span class="add">+${a}</span> <span class="del">−${d}</span></div>`).join("")
      : ["git status --short", "git diff --stat"].map((x) => `<div class="sc-code">${x}</div>`).join("");
    const always = s.edit ? "*" : "git status *, git diff *";
    return `${s.by ? attribution(s.by) : ""}<div class="sc-copy">
        <div class="sc-head"><span class="t-heading">Permission requested</span>${pager(position, count, off)}${round("keyboard_arrow_down")}</div>
        <div class="t-secondary">${s.edit ? "edit" : "bash"}</div><div class="sc-lines">${lines}</div>
        <div class="t-caption sc-weak">Always allows: ${esc(always)}</div>
        ${s.error ? `<div class="t-caption sc-err" role="alert">${esc(s.error)}</div>` : ""}
      </div>
      <div class="sc-decide"><span class="sc-split-host"><span class="sc-split">${Kit.use("split-button", { contrast: "low", title: "Deny", disabled: off }, C)}</span>
        <span class="sc-menu float" hidden><button class="press">${I("close", 18)}<span>Deny</span></button><button class="press">${I("edit", 18)}<span>Deny with a note…<small>Tell the agent what to do instead</small></span></button></span></span>
        <span class="sc-flex"></span>${off ? spinner() : ""}${compact("Always", "low", off)}${compact("Allow once", "high", off)}</div>${dots(position, count)}`;
  };

  const noteBody = () => `<div class="sc-copy"><div class="t-heading">Deny with a note</div>
      <div class="sc-summary"><span class="t-secondary">bash</span><span class="sc-code sc-weak">git status --short</span></div>
      <textarea class="sc-area" rows="3" placeholder="Tell OpenCode what to do differently">Run the tests with --watch=false instead</textarea>
      <div class="t-caption sc-weak">Also denies 1 other waiting request.</div></div>
    <div class="sc-decide">${compact("Cancel", "low")}<span class="sc-flex"></span>${compact("Send", "high")}</div>`;

  const radioIcon = (on) => (on ? "radio_button_checked" : "radio_button_unchecked");
  const optionCard = (title, subtitle, on, off, mode) => Kit.use("list-item", { title, subtitle, caption: "", style: "card", decorator: "icon", icon: radioIcon(on), status: "none", textFlow: "wrapped", selected: on, disabled: off }, C)
    .replace('<div class="c-li', `<div data-sc-opt="${mode}" class="c-li`);

  const questionBody = (s) => {
    const off = !!s.submitting, step = s.step ?? 0, total = s.wizard ? 2 : 1;
    const position = s.position ?? 1, count = s.count ?? 1;
    const counter = total > 1 ? `<span class="t-caption">${count > 1 ? "Step " : ""}${step + 1} of ${total}</span>` : "";
    const options = OPTIONS.map(([l, d]) => optionCard(l, d, s.selected.includes(l), off, s.multiple ? "multi" : "one")).join("");
    const custom = s.noCustom ? "" : optionCard("Type your own answer", "", !!s.custom, off, "custom") + `<textarea class="sc-area" rows="3" placeholder="Enter a custom answer" ${s.custom ? "" : "hidden"}>${esc(s.custom ?? "")}</textarea>`;
    return `${s.by ? attribution(s.by) : ""}<div class="sc-copy">
        <div class="sc-head"><span class="t-heading">${esc(s.header ?? "Approach")}</span>${counter}${pager(position, count, off)}${round("keyboard_arrow_down")}</div>
        <div class="t-secondary">${esc(s.question ?? "How should we implement this change?")}</div>
        <div class="t-caption sc-base">${esc(s.hint ?? "Select one")}</div>
        <div class="sc-options">${options}${custom}</div>
        ${s.error ? `<div class="t-caption sc-err" role="alert">${esc(s.error)}</div>` : ""}
      </div>
      <div class="sc-decide">${compact("Dismiss", "low", off)}<span class="sc-flex"></span>${off ? spinner() : ""}${step > 0 ? compact("Back", "low", off) : ""}${s.next ? compact("Next", "high", off) : compact("Submit", "high", off || !s.submit)}</div>${dots(position, count)}`;
  };

  const childBody = () => `<div class="sc-lock-chips"><span>Claude Sonnet 4</span><span>High</span></div>
    <div class="t-caption sc-base sc-lock-cap">Child sessions cannot be prompted.</div>
    <button class="sc-cta press">Back to main session</button>`;

  const composer = (p, ctx) => {
    const s = SCENARIOS[p.scenario] ?? SCENARIOS.empty;
    if (s.androidOnly && ctx.platform === "ios") return `<div class="sc-only">Android only. iOS has no argument hint on the prompt.</div>`;
    const body = s.kind === "permission" ? permissionBody(s) : s.kind === "note" ? noteBody() : s.kind === "question" ? questionBody(s) : s.kind === "child" ? childBody() : promptBody(s, p);
    const edge = s.kind === "permission" || s.kind === "note" ? (s.error ? "err" : "warn") : "";
    const shell = `<div class="sc-wrap ${p.style}"><div class="sc-shell ${p.style === "docked" ? "" : "float"} ${edge}">${p.interrupt ? `<div class="sc-kbd">Esc again to interrupt</div>` : ""}${body}</div></div>`;
    if (!p.backdrop) return shell;
    const lines = [92, 78, 86, 64, 90, 72].map((w) => `<i style="width:${w}%"></i>`).join("");
    return `<div class="sc-stage"><div class="sc-lines-bg">${lines}</div>${p.style === "docked" ? "" : `<div class="sc-scrim ${p.away ? "away" : ""}"></div>`}${shell}</div>`;
  };

  /* ---------- Floating pill: the shared component, with this feature's icon ---------- */
  const floating = (props, _from, to) => Kit.use("floating-pill", to ? { ...props, icon: to } : props, C);

  /* ---------- Session Branch Pill ---------- */
  const FILES = [["Sources/App/Session/BranchPill.swift", 96, 4], ["Sources/App/Session/BranchPopover.swift", 24, 2], ["Tests/BranchPillTests.swift", 8, 8], ["Sources/App/Legacy/OldBranchRow.swift", 0, 6], ["Resources/branch-icon.png", 0, 0]];
  const BRANCHES = {
    changes: { branch: "feature/session-branch", files: FILES },
    noChanges: { branch: "feature/session-branch", files: [] },
    worktree: { branch: "feature/worktree-checkout", worktree: true, files: FILES.slice(0, 2) },
    manyFiles: { branch: "feature/ope-48-session-branch-pill-with-a-very-long-name", files: [...FILES, ["README.md", 3, 0], ["docs/notes.md", 0, 4]] },
  };
  const totals = (files) => files.reduce((t, f) => [t[0] + f[1], t[1] + f[2]], [0, 0]);
  const stat = (a, d) => `<span class="sb-stat">${a > 0 ? `<span class="add">+${a}</span>` : ""}${d > 0 ? `<span class="del">−${d}</span>` : ""}</span>`;
  const branchLabel = (b) => {
    if (!b.files.length) return "";
    const [a, d] = totals(b.files);
    const parts = [a > 0 && `+${a}`, d > 0 && `−${d}`].filter(Boolean);
    return parts.length ? parts.join(" ") : `${b.files.length} ${b.files.length === 1 ? "file" : "files"}`;
  };
  const branchPill = (b) => {
    const label = branchLabel(b), icon = b.worktree ? "call_split" : "git_branch";
    return label ? floating({ layout: "iconLabel", state: "ready", label }, "call_split", icon) : floating({ layout: "iconOnly", state: "ready", label: `Branch ${b.branch}, no changes` }, "arrow_downward", icon);
  };
  const popover = (b) => {
    const [a, d] = totals(b.files);
    const top = [...b.files].sort((x, y) => (y[1] + y[2]) - (x[1] + x[2]) || x[0].localeCompare(y[0])).slice(0, 3);
    const action = (icon, title) => `<button class="sb-action press"><span class="sb-action-i">${I(icon)}</span><span>${title}</span></button>`;
    return `<div class="sb-pop float"><div class="sb-sec sb-head"><span class="sb-ic">${I(b.worktree ? "call_split" : "git_branch", 16)}</span><span class="sb-names"><b>${esc(b.branch)}</b><small>Based on main</small></span></div>
      ${b.files.length ? `<div class="sb-sec sb-files"><div class="sb-file sb-count"><span>${b.files.length} uncommitted ${b.files.length === 1 ? "file" : "files"}</span>${stat(a, d)}</div>${top.map(([f, x, y]) => `<div class="sb-file"><span class="sb-path"><bdi>${esc(f)}</bdi></span>${stat(x, y)}</div>`).join("")}</div>` : ""}
      <div class="sb-acts">${b.files.length ? action("description", "View all changes") : ""}${action("content_copy", "Copy branch name")}</div></div>`;
  };

  /* ---------- Session Todo Item ---------- */
  const TODO = { pending: "Summarize what the README says in 2 sentences", inProgress: "List the top-level files in this repo", completed: "Suggest one small follow-up improvement", cancelled: "Optional deep dive into the build scripts" };
  const todo = (p) => `<div class="st-row ${p.status}"><span class="st-mark">${p.status === "completed" || p.status === "cancelled" ? I("check", 12) : p.status === "inProgress" ? "<i></i>" : ""}</span><span class="st-text">${esc(p.content || TODO[p.status])}</span></div>`;

  /* ---------- Session Status Bar ---------- */
  const ring = (fraction) => {
    const r = 6.25, c = 2 * Math.PI * r;
    const color = fraction >= 0.85 ? "var(--error)" : fraction >= 0.7 ? "var(--warning)" : "var(--icon-strong-base)";
    return `<svg viewBox="0 0 14 14" width="14" height="14" fill="none" stroke-width="1.5"><circle cx="7" cy="7" r="${r}" stroke="var(--icon-base)"/>${fraction == null ? "" : `<circle cx="7" cy="7" r="${r}" stroke="${color}" stroke-linecap="round" stroke-dasharray="${(c * fraction).toFixed(2)} ${c.toFixed(2)}" transform="rotate(-90 7 7)"/>`}</svg>`;
  };
  const statusBar = (p) => `<div class="ss-bar"><button class="ss-btn" aria-label="${p.usage === "unavailable" ? "Context usage unavailable" : `Context usage ${parseInt(p.usage, 10)} percent`}">${ring(p.usage === "unavailable" ? null : parseInt(p.usage, 10) / 100)}</button></div>`;

  /* ---------- Background Work Pill ---------- */
  const WORK = { subagents: "3 subagents", mixed: "3 subagents · 2 shells", shells: "2 shells", one: "general needs input", oneRunning: "general needs input · 4 running", many: "2 subagents need input · 3 running" };
  const workPill = (p) => {
    const waiting = p.work === "one" || p.work === "oneRunning" || p.work === "many";
    return floating({ layout: waiting ? "counterWaiting" : "counter", state: waiting ? "needsAttention" : "live", label: WORK[p.work], compact: p.compact });
  };

  /* ---------- Dictation Card ---------- */
  const SPEECH = (ctx) => ctx.platform === "ios" ? "iOS" : "Android";
  const unavailable = (v, ctx) => {
    const sys = SPEECH(ctx), model = "English speech model";
    return ({
      permissionDenied: { title: "Microphone access is off", action: "Open Settings" },
      modelNotInstalled: { title: `${sys} needs its ${model}`, detail: `Dictation runs on this device, so your voice is never uploaded. ${sys} downloads the model once and keeps it for every app.`, action: "Download" },
      modelDownloading: { title: `${sys} is downloading the ${model}`, progress: true, detail: "This is a system download. It keeps going if you close this card.", action: "Downloading…", off: true },
      modelDownloadStarting: { title: `${sys} is downloading the ${model}`, progress: "start", detail: "This is a system download. It keeps going if you close this card.", action: "Downloading…", off: true },
      modelDownloadQueued: { title: `${sys} will download the ${model} later`, detail: `${sys} queued the download. It may wait for Wi-Fi, charging, or free storage. Check again in a few minutes.`, action: "Check Again" },
      modelDownloadFailed: { title: `${sys} couldn't download the ${model}`, detail: "Check the connection and free storage on this device, then try again.", action: "Try Again" },
      recognizerError: { title: "Dictation stopped", action: "Retry" },
    })[v];
  };
  const orb = (level, tint) => {
    const c = 74, bars = Array.from({ length: 24 }, (_, i) => {
      const a = (i / 24) * 2 * Math.PI - Math.PI / 2, ext = level * (0.62 + 0.38 * Math.sin(i * 1.7));
      const len = 4 + 24 * ext;
      return `<line data-i="${i}" x1="${(c + 40 * Math.cos(a)).toFixed(2)}" y1="${(c + 40 * Math.sin(a)).toFixed(2)}" x2="${(c + (40 + len) * Math.cos(a)).toFixed(2)}" y2="${(c + (40 + len) * Math.sin(a)).toFixed(2)}"/>`;
    }).join("");
    const halo = 32 * (1 + 0.6 * level);
    return `<span class="sd-orb" data-level="${level}" style="--tint:var(--${tint})"><svg viewBox="0 0 148 148" width="148" height="148"><circle class="sd-halo" cx="74" cy="74" r="${halo}" style="opacity:${(0.22 * level).toFixed(3)}"/><g class="sd-bars">${bars}</g><circle cx="74" cy="74" r="32" fill="var(--${tint})"/></svg><span class="sd-glyph">${I("mic", 28)}</span></span>`;
  };
  const dictation = (p, ctx) => {
    const v = p.variant;
    if ((v === "stopped" || v === "modelDownloadQueued") && ctx.platform === "ios") return `<div class="sc-only">Android only. iOS has no ${v === "stopped" ? "Stopped" : "download queued"} state.</div>`;
    const actions = (label, on) => `<div class="sd-actions">${button({ variant: "fullWidthText", contrast: "low", title: "Cancel" })}${button({ variant: "fullWidthText", contrast: "high", title: label, disabled: !on })}</div>`;
    if (!["listening", "finishing", "stopped"].includes(v)) {
      const u = unavailable(v, ctx);
      const pct = Math.round(p.level * 100);
      const prog = u.progress ? `<div class="sd-prog"><div class="sd-track ${u.progress === "start" ? "ind" : ""}"><i style="width:${u.progress === "start" ? 35 : pct}%"></i></div><span class="t-secondary">${u.progress === "start" ? "Starting…" : `${pct}%`}</span></div>` : "";
      return `<div class="sd-card"><div class="sd-un"><div class="sd-title">${esc(u.title)}</div>${prog}${u.detail ? `<div class="t-secondary sd-detail">${esc(u.detail)}</div>` : ""}</div>${actions(u.action, !u.off)}</div>`;
    }
    const level = v === "listening" ? p.level : 0;
    const transcript = v === "listening" ? `Hello<span class="sd-vol"> there</span>` : "Hello";
    return `<div class="sd-card"><div class="sd-listen">${orb(level, v === "finishing" ? "success" : "interactive")}<div class="sd-trans">${transcript}</div>
      <div class="sd-status">${v === "stopped" ? "" : `<i></i>`}<span>${v === "stopped" ? "Ready to insert · English · on-device" : "Listening · English · on-device"}</span><b>0:12</b></div></div>${actions("Insert", v !== "finishing")}</div>`;
  };

  /* ---------- Drop Overlay ---------- */
  const DROPS = {
    images: { title: "Drop to attach 3 images", subtitle: "Adds them to the message you are writing", previews: ["IMG", "IMG", "IMG"] },
    mixed: { title: "Drop to attach 2 files", subtitle: "1 will be skipped · Folders can't be attached.", previews: ["IMG", "TEXT"] },
    reject: { title: "Can't attach this", subtitle: "Folders can't be attached.", reject: true },
  };
  const drop = (p) => {
    const d = DROPS[p.variant];
    return `<div class="so-host"><div class="so-scrim"></div><div class="so-card ${d.reject ? "reject" : ""}"><svg class="so-dash" aria-hidden="true"><rect x="1" y="1" rx="21" ry="21" style="width:calc(100% - 2px);height:calc(100% - 2px)"/></svg><span class="so-icon">${I(d.reject ? "close" : "arrow_downward", 28)}</span>${d.previews ? `<div class="so-prev">${d.previews.map((x) => `<span>${x}</span>`).join("")}</div>` : ""}<div class="t-heading so-title">${esc(d.title)}</div><div class="t-secondary so-sub">${esc(d.subtitle)}</div></div></div>`;
  };

  /* ---------- Session List Skeleton ---------- */
  const WIDTHS = [[72, 46], [58, 52], [80, 40], [64, 50], [50, 44], [70, 48]];
  // iOS tints the avatar with the badge fill, Android with the badge tint.
  const accentFill = (accent, ctx) => { const a = ACCENTS[accent]; return ctx.platform === "android" ? (ctx.dark ? a[3] : a[1]) : (ctx.dark ? a[2] : a[0]); };
  const skelRow = (i, accent, ctx, opacity = 1) => {
    const [t, s] = WIDTHS[i % WIDTHS.length];
    const avatar = accent !== "neutral" ? `style="background:${accentFill(accent, ctx)};opacity:var(--sk-avatar)"` : `class="neutral"`;
    return `<div class="sk-row" style="opacity:${opacity}"><span class="sk-av-wrap"><i ${avatar}></i></span><span class="sk-lines"><i class="sk-bar t" style="width:${t}%"></i><i class="sk-bar s" style="width:${s}%"></i></span><i class="sk-bar sk-pill"></i></div>`;
  };
  const skeleton = (p, ctx) => {
    const n = p.mode === "row" ? 1 : parseInt(p.rows, 10);
    const rows = Array.from({ length: n }, (_, i) => skelRow(i, p.accent, ctx, p.mode === "row" ? 1 : Math.max(1 - i * 0.12, 0.2))).join("");
    return `<div class="sk-list">${rows}${p.slow && p.mode !== "row" ? `<div class="sk-cap t-caption">Still loading — large projects can take a moment</div>` : ""}</div>`;
  };

  /* ---------- Session List Dock ---------- */
  const TABS = {
    all: { title: "All projects", subtitle: "3 projects", all: true },
    mobile: { title: "opencode-mobile", subtitle: "~/opencode-mobile", letter: "O", accent: "violet" },
    playground: { title: "playground", subtitle: "~/playground", letter: "P", accent: "teal" },
    api: { title: "api-client", subtitle: "~/api-client", letter: "A", accent: "amber" },
  };
  const ORDER = ["all", "mobile", "playground", "api"];
  const scopeMark = (t, ctx) => {
    if (t.all) return `<span class="sl-mark all">${I("widgets", 16)}</span>`;
    const a = ACCENTS[t.accent];
    return `<span class="sl-mark" style="background:${ctx.dark ? a[2] : a[0]};color:${ctx.dark ? a[3] : a[1]}">${t.letter}</span>`;
  };
  const scope = (id, load, ctx) => {
    const t = TABS[id];
    const subtitle = load === "loading" ? "Loading sessions…" : load === "slow" ? "Still loading…" : load === "failed" ? "Couldn’t load sessions" : t.subtitle;
    return `<button class="sl-scope" data-sl-scope="${id}">${scopeMark(t, ctx)}<span class="sl-id"><b>${esc(t.title)}</b><small class="${load === "failed" ? "err" : ""}">${esc(subtitle)}</small></span>${load === "loading" || load === "slow" ? spinner() : ""}<span class="sl-chev">${I("keyboard_arrow_down", 16)}</span></button>`;
  };
  const dock = (p, ctx) => {
    const tab = p.project === "one" ? "mobile" : p.selected;
    return `<div class="sl-dock-wrap"><div class="sl-dock float">${p.error ? `<div class="t-secondary sc-err">Could not create session. Try again.</div>` : ""}${p.project === "none" ? "" : scope(tab, p.load, ctx)}<div class="sl-cta"><button class="sl-add press" aria-label="Add project">${I("create_new_folder", 22)}</button><button class="sl-new press">New session</button></div></div></div>`;
  };

  /* ---------- Session List ---------- */
  const SESSIONS = [
    { title: "Refactor Navigation", project: "opencode-mobile", tree: "803-compact-home-session-rows-with-a-classic-option", letter: "O", accent: "violet", status: "Running", tone: "interactive", caption: "now", pulse: true },
    { title: "Resume Cached Chat", project: "opencode-mobile", letter: "O", accent: "violet", status: "Running", tone: "stale", caption: "14m ago" },
    { title: "Bug: Header Clipping", project: "playground", letter: "P", accent: "teal", status: "Idle", tone: "info", caption: "14m ago" },
    { title: "Add Dark Mode Assets", project: "opencode-mobile", tree: "opencode-mobile-779", letter: "O", accent: "violet", status: "Ready", tone: "success", caption: "3h ago", bold: true },
    { title: "Review Session List", project: "api-client-for-the-enterprise-billing-platform", tree: "ope-1042-retry", letter: "A", accent: "amber", status: "Retrying · 2", tone: "warning", caption: "2d ago" },
  ];
  const compactRow = (s) => Kit.use("list-item", { variant: "compact", decorator: "compactLetter", title: s.title, letter: s.letter, accent: s.accent, caption: s.caption, subtitle: "", pathPrimary: s.project, pathSecondary: s.tree ?? "", emphasized: !!s.bold, dot: s.status === "Idle" ? "none" : s.tone, pulsing: !!s.pulse, status: s.status === "Idle" ? "none" : s.tone, statusText: s.status }, C);
  const classicRow = (s) => Kit.use("list-item", { title: s.title, letter: s.letter, subtitle: `~/${s.project}`, caption: s.caption, style: "plain", decorator: "letter", accent: s.accent, dot: "none", status: s.tone, statusText: s.status }, C);
  const sessionList = (p, ctx) => {
    const rows = p.selected === "all" ? SESSIONS : SESSIONS.filter((s) => TABS[p.selected].title === s.project || (p.selected === "api" && s.letter === "A"));
    const body = p.state === "loading" ? `<div class="sl-sec">${esc(TABS[p.selected].title)}</div>${skeleton({ mode: "list", rows: "6", accent: TABS[p.selected].accent ?? "neutral", slow: false }, ctx)}`
      : p.state === "empty" ? ""
      : `<div class="sl-sec">Recent</div>${rows.map((s) => p.rowStyle === "compact" ? compactRow(s) : classicRow(s)).join("")}`;
    return `<div class="sl-screen"><div class="sl-feed">${body}</div><div class="sl-fade"></div>${dock({ selected: p.selected, project: "many", load: "none", error: false }, ctx)}</div>`;
  };

  const SPEC = {
    branch: "## Intent\nSession Chat context pill for the session's branch. Shows an icon and line counts, or the icon alone without changes.\n## Public API\n`summary` (branch, base, isWorktree, files), `onViewAllChanges`, `onBranchNameCopied`.\n## Behavior\nTap opens a 320 wide card under the pill with branch details, the three most-changed files, View all changes, and Copy branch name.",
    work: "## Intent\nLive counter of running subagents and shells in Session Chat.\n## Public API\n`pill` (label, needsInput, accessibilityLabel), `compact`, `action`.\n## Behavior\nRunning shows a spinner. When a subagent waits on a permission or question the pill turns amber with a pulsing dot and a 1pt ring. Tap opens the background work panel.",
    dictation: "## Intent\nListening takeover inside the Session Composer.\n## Public API\n`variant`, committed and volatile transcript, `level`, status, elapsed, language, and Cancel, Insert, and recovery actions.\n## Behavior\nThe orb follows input level. Insert needs a transcript and is off while finishing. Unavailable variants replace the orb with copy and a recovery action.",
    drop: "## Intent\nDrag-over state for a drop target that covers a whole screen.\n## Public API\n`variant` (accept, reject), `title`, `subtitle`, `previews` (first four shown).\n## Behavior\nContent dims at 0.32 black. A centered dashed card, at most 420 wide, says what a drop would do.",
    skeleton: "## Intent\nLoading placeholder shaped like session rows.\n## Public API\n`rowCount` (default 6), `accent`, `caption`.\n## Behavior\nRows fade down the list by 0.12 per row to a 0.2 floor. One shared 1.4 s shimmer runs across rows and stays static under Reduce Motion.",
  };

  const composerStyleBase = { backdrop: true };
  Kit.define([
    { id: "session-composer", doc: "session-chat-composer", align: "stretch", dock: true,
      params: [sel("scenario", Object.keys(SCENARIOS)), sel("style", ["outlineFade", "docked"]), bool("dictation", false, "dictation button"), bool("interrupt", false, "interrupt hint"), bool("backdrop", false, "timeline backdrop"), bool("away", false, "away from end")],
      states: [["Empty", { scenario: "empty" }], ["Prompt", { scenario: "prompt" }], ["Mentions", { scenario: "mentions" }], ["Active", { scenario: "active" }], ["Attachments", { scenario: "attachments" }], ["Revert", { scenario: "revert" }], ["Shell", { scenario: "shell" }], ["/btw", { scenario: "sideQuestion" }], ["Hint (Android)", { scenario: "commandHint" }], ["Limit", { scenario: "limitNote" }], ["Child", { scenario: "childLockout" }], ["Dictation button", { scenario: "empty", dictation: true }], ["Interrupt hint", { scenario: "active", interrupt: true }], ["Docked permission", { scenario: "permission", style: "docked" }], ["Docked child lockout", { scenario: "childLockout", style: "docked" }], ["Permission", { scenario: "permission" }], ["Edit", { scenario: "permissionEdit" }], ["Deny note", { scenario: "permissionNote" }], ["Queued", { scenario: "queued" }], ["Subagent", { scenario: "subagentPermission" }], ["Submitting", { scenario: "submitting" }], ["Error", { scenario: "error" }], ["Question", { scenario: "question" }], ["Wizard", { scenario: "questionWizard" }], ["Wizard last", { scenario: "questionWizardLast" }], ["Multi-select", { scenario: "multiSelect" }], ["Up to 2", { scenario: "multiSelectBounded" }], ["Custom", { scenario: "custom" }], ["Q Submit", { scenario: "questionSubmitting" }], ["Q Error", { scenario: "questionError" }], ["Q Subagent", { scenario: "subagentQuestion" }], ["Docked", { style: "docked", scenario: "prompt" }]],
      sheet: [
        { title: "Composer style", kind: "stack", base: composerStyleBase, items: [["Outline fade, at end", { scenario: "prompt" }], ["Outline fade, scrolled away", { scenario: "prompt", away: true }], ["Docked", { scenario: "prompt", style: "docked" }], ["Docked, permission edge", { scenario: "permission", style: "docked" }]] },
        { title: "Prompt", kind: "stack", items: [["Empty", { scenario: "empty" }], ["Populated", { scenario: "prompt" }], ["Mentions", { scenario: "mentions" }], ["Active, stop available", { scenario: "active" }], ["Dictation button", { scenario: "empty", dictation: true }], ["Interrupt hint", { scenario: "active", interrupt: true }], ["Attachments", { scenario: "attachments" }], ["Pending revert", { scenario: "revert" }], ["Shell", { scenario: "shell" }], ["/btw side question", { scenario: "sideQuestion" }], ["Argument hint (Android)", { scenario: "commandHint" }], ["Over limit", { scenario: "limitNote" }]] },
        { title: "Permission", kind: "stack", items: [["Pending", { scenario: "permission" }], ["Edit with diff counts", { scenario: "permissionEdit" }], ["Deny with a note", { scenario: "permissionNote" }], ["Queued", { scenario: "queued" }], ["From a subagent", { scenario: "subagentPermission" }], ["Submitting", { scenario: "submitting" }], ["Response error", { scenario: "error" }]] },
        { title: "Question", kind: "stack", items: [["Single select", { scenario: "question" }], ["Wizard, first step", { scenario: "questionWizard" }], ["Wizard, last step", { scenario: "questionWizardLast" }], ["Multi-select", { scenario: "multiSelect" }], ["Multi-select, up to 2", { scenario: "multiSelectBounded" }], ["Custom answer", { scenario: "custom" }], ["Submitting", { scenario: "questionSubmitting" }], ["Response error", { scenario: "questionError" }], ["From a subagent", { scenario: "subagentQuestion" }]] },
        { title: "Child lockout", kind: "stack", items: [["Outline fade", { scenario: "childLockout" }], ["Docked", { scenario: "childLockout", style: "docked" }]] },
      ],
      tokens: [["sessionComposer.cornerRadius", 26], ["sessionComposer.docked.topCornerRadius", 22], ["sessionComposer.screenPaddingHorizontal", 12], ["sessionComposer.screenPaddingBottom", 4], ["sessionComposer.contentPadding", 12], ["sessionComposer.primaryContentInset", 4], ["sessionComposer.rowSpacing", 10], ["sessionComposer.actionSpacing", 8], ["sessionComposer.outlineWidth", 1], ["sessionComposer.edge", "surface-raised-base → text-strong 20%"], ["sessionComposer.scrim.opacity", 0.7], ["sessionComposer.fade.resting", 16], ["sessionComposer.fade.scrolled", 46], ["sessionComposer.docked.shadowHeight", 14], ["sessionComposer.docked.shadowOpacity", 0.1], ["sessionComposer.backCta.height", 52]],
      source: { ios: "Components/Input/OpenCodeSessionComposer.swift", android: "ui/components/input/OpenCodeSessionComposer.kt" },
      render: live(composer),
      mount: (stage) => {
        stage.querySelectorAll(".sc-split-host").forEach((host) => {
          if (host.dataset.bound) return; host.dataset.bound = "1";
          const toggle = [...host.querySelectorAll(".sc-split button")].pop(), menu = host.querySelector(".sc-menu");
          toggle?.addEventListener("click", (e) => { e.stopPropagation(); menu.hidden = !menu.hidden; });
          menu?.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => { menu.hidden = true; }));
        });
        stage.querySelectorAll("[data-sc-opt]").forEach((el) => {
          if (el.dataset.bound) return; el.dataset.bound = "1";
          const paint = (node, on) => { node.classList.toggle("sel", on); const icon = node.querySelector(".c-li-deco"); if (icon) icon.innerHTML = I(radioIcon(on), 24); };
          el.addEventListener("click", () => {
            if (el.getAttribute("aria-disabled") === "true") return;
            const root = el.closest(".sc-options"), area = root.querySelector("textarea"), mode = el.dataset.scOpt, on = !el.classList.contains("sel");
            if (mode === "multi") return paint(el, on);
            root.querySelectorAll("[data-sc-opt]").forEach((n) => paint(n, false));
            if (mode === "custom") { paint(el, on); if (area) area.hidden = !on; } else { paint(el, true); if (area) area.hidden = true; }
          });
        });
      } },
    { id: "session-list", doc: "session-list", align: "stretch", cellHeight: 560, layout: "scroll",
      params: [sel("rowStyle", ["compact", "classic"]), sel("selected", ORDER), sel("state", ["default", "loading", "empty"])],
      states: [["Compact", {}], ["Classic", { rowStyle: "classic" }], ["Project filter", { selected: "mobile" }], ["Loading", { state: "loading" }], ["Empty", { state: "empty", selected: "mobile" }]],
      tokens: [["listItem.compact.height", 66], ["listItem.compact.badge", 32], ["listItem.compact.lineSpacing", 5], ["listItem.compact.statusDot", 8], ["bottomFade.stops", "0 → 0.7 → 0.7"], ["bottomFade.overhang", 16], ["crossfade", "400 ms"]],
      source: { ios: "Components/List/SessionList.swift", android: "ui/components/list/SessionList.kt" },
      render: live(sessionList) },
    { id: "session-list-dock", doc: "session-list-dock", align: "stretch", dock: true,
      params: [sel("selected", ORDER), sel("project", ["many", "one", "none"]), sel("load", ["none", "loading", "slow", "failed"]), bool("error", false, "create error")],
      states: [["All projects", {}], ["Project", { selected: "mobile" }], ["One project", { project: "one" }], ["No projects", { project: "none" }], ["Loading", { selected: "api", load: "loading" }], ["Slow", { selected: "api", load: "slow" }], ["Load failed", { selected: "api", load: "failed" }], ["Create error", { error: true }]],
      sheet: [{ title: "Scope", kind: "stack", items: [["All projects", {}], ["Selected project", { selected: "mobile" }], ["One project", { project: "one" }], ["No projects, actions only", { project: "none" }]] }, { title: "Scope load", kind: "stack", base: { selected: "api" }, items: [["Loading", { load: "loading" }], ["Slow", { load: "slow" }], ["Failed", { load: "failed" }]] }, { title: "Create error", kind: "stack", items: [["Error above scope", { error: true }]] }],
      tokens: [["dock.cornerRadius", 26], ["scope.cornerRadius", 20], ["scope.minHeight", 44], ["scope.padding", "10, 8"], ["scope.mark", 32], ["cta.height", 52], ["cta.iconSize", 22], ["cta.gap", 8], ["paging.commit", "28 pt"], ["paging.switch", "450 ms"], ["paging.return", "180 ms"], ["control.wash", "iOS 0.06, Android 0.05"]],
      source: { ios: "Components/Chrome/OpenCodeSessionListDock.swift", android: "ui/components/chrome/OpenCodeSessionListDock.kt" },
      render: live(dock),
      mount: (stage) => stage.querySelectorAll("[data-sl-scope]").forEach((el) => {
        if (el.dataset.bound) return; el.dataset.bound = "1";
        let startX = null;
        el.addEventListener("pointerdown", (e) => { startX = e.clientX; el.setPointerCapture?.(e.pointerId); });
        el.addEventListener("pointermove", (e) => { if (startX == null) return; const d = e.clientX - startX; const v = Math.sign(d) * 52 * (1 - Math.exp(-Math.abs(d) / 70)); el.querySelector(".sl-id").style.transform = `translateX(${v}px)`; el.querySelector(".sl-mark").style.transform = `translateX(${v}px)`; });
        const end = () => { startX = null; for (const n of [el.querySelector(".sl-id"), el.querySelector(".sl-mark")]) { n.style.transition = "transform 180ms cubic-bezier(.45,0,.12,1)"; n.style.transform = ""; setTimeout(() => { n.style.transition = ""; }, 200); } };
        el.addEventListener("pointerup", end); el.addEventListener("pointercancel", end);
      }) },
    { id: "session-list-skeleton", align: "stretch",
      params: [sel("mode", ["list", "row"]), sel("rows", ["6", "3"]), sel("accent", ["teal", "violet", "neutral"]), bool("slow", false, "slow caption")],
      states: [["List", {}], ["Row", { mode: "row", accent: "violet" }], ["3 rows", { rows: "3" }], ["Neutral", { accent: "neutral" }], ["Slow caption", { slow: true, rows: "3" }]],
      sheet: [{ title: "Row", kind: "stack", items: [["Accent", { mode: "row", accent: "violet" }], ["Neutral", { mode: "row", accent: "neutral" }]] }, { title: "List", kind: "stack", items: [["3 rows, slow caption", { rows: "3", slow: true }], ["6 rows", {}]] }],
      tokens: [["skeleton.avatar", 32], ["skeleton.title", 13], ["skeleton.subtitle", 10], ["skeleton.pill", "44 × 18"], ["skeleton.rowFadeStep", 0.12], ["skeleton.minRowOpacity", 0.2], ["skeleton.shimmerCycle", "1.4 s"], ["skeleton.shimmerHalfWidth", 0.35]],
      spec: SPEC.skeleton, source: { ios: "Components/List/OpenCodeSessionListSkeleton.swift", android: "ui/components/list/OpenCodeSessionListSkeleton.kt" },
      render: skeleton },
    { id: "session-status-bar", doc: "session-status-bar", align: "stretch",
      params: [sel("usage", ["0%", "50%", "70%", "85%", "unavailable"], "50%")],
      states: [["0%", { usage: "0%" }], ["50%", { usage: "50%" }], ["70% warning", { usage: "70%" }], ["85% error", { usage: "85%" }], ["Unavailable", { usage: "unavailable" }]],
      sheet: [{ title: "Context usage", kind: "stack", items: [["0%", { usage: "0%" }], ["50%", { usage: "50%" }], ["70%, warning", { usage: "70%" }], ["85%, error", { usage: "85%" }], ["Unavailable", { usage: "unavailable" }]] }],
      tokens: [["statusBar.height", 36], ["statusBar.paddingHorizontal", 16], ["statusBar.touchSize", 22], ["statusBar.ringSize", 14], ["statusBar.ringStroke", 1.5], ["threshold.warning", 0.7], ["threshold.error", 0.85]],
      source: { ios: "Components/Chrome/OpenCodeSessionStatusBar.swift", android: "ui/components/chrome/OpenCodeSessionStatusBar.kt" }, render: statusBar },
    { id: "session-branch-pill", align: "stretch", pad: true,
      params: [sel("sample", Object.keys(BRANCHES)), bool("open", false, "popover open")],
      states: [["Changes", {}], ["No changes", { sample: "noChanges" }], ["Worktree", { sample: "worktree" }], ["Many files", { sample: "manyFiles" }], ["Popover", { open: true }]],
      sheet: [{ title: "Pill", kind: "flow", items: Object.keys(BRANCHES).map((sample) => ["", { sample }]) }, { title: "Popover", kind: "stack", inset: true, base: { open: true }, items: [["Changes", {}], ["No changes", { sample: "noChanges" }], ["Worktree", { sample: "worktree" }], ["Many files, long name", { sample: "manyFiles" }]] }],
      tokens: [["pill.minHeight", 36], ["pill.paddingHorizontal", 14], ["pill.iconSize", 16], ["pill.iconSpacing", 6], ["popover.width", 320], ["popover.paddingHorizontal", 16], ["popover.sectionPaddingVertical", 12], ["popover.fileRowSpacing", 8], ["popover.topFiles", 3], ["popover.actionHeight", "iOS 44, Android 48"]],
      spec: SPEC.branch, source: { ios: "Components/Chat/OpenCodeSessionBranchPill.swift", android: "ui/components/chat/OpenCodeSessionBranchPill.kt" },
      render: live((p) => { const b = BRANCHES[p.sample]; return p.open ? popover(b) : `<span class="sb-host">${branchPill(b)}<span class="sb-float" hidden>${popover(b)}</span></span>`; }),
      mount: (stage) => stage.querySelectorAll(".sb-host").forEach((host) => {
        if (host.dataset.bound) return; host.dataset.bound = "1";
        const card = host.querySelector(".sb-float");
        host.firstElementChild?.addEventListener("click", () => { card.hidden = !card.hidden; });
        card?.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => { card.hidden = true; }));
      }) },
    { id: "session-todo-item", doc: "session-todo-item", align: "stretch",
      params: [sel("status", ["pending", "inProgress", "completed", "cancelled"], "inProgress"), text("content", "")],
      states: [["Pending", { status: "pending" }], ["In progress", { status: "inProgress" }], ["Completed", { status: "completed" }], ["Cancelled", { status: "cancelled" }], ["Long", { status: "inProgress", content: "Trace every caller of the session store and list the ones that still read the legacy cache before the migration runs" }]],
      sheet: [{ title: "Status", kind: "stack", items: [["Pending", { status: "pending" }], ["In progress", { status: "inProgress" }], ["Completed", { status: "completed" }], ["Cancelled", { status: "cancelled" }]] }, { title: "Wrapping", kind: "stack", items: [["Long content", { status: "inProgress", content: "Trace every caller of the session store and list the ones that still read the legacy cache before the migration runs" }]] }],
      tokens: [["todo.paddingHorizontal", 20], ["todo.paddingVertical", 10], ["todo.contentSpacing", 12], ["todo.mark", 18], ["todo.markRadius", 4], ["todo.markBorder", 1.5], ["todo.markFill", "text-interactive-base 0.15"], ["todo.icon", 12], ["todo.indeterminate", "8 × 2"], ["todo.pendingOpacity", 0.85]],
      source: { ios: "Components/Chat/OpenCodeSessionTodoItem.swift", android: "ui/components/chat/OpenCodeSessionTodoItem.kt" }, render: todo },
    { id: "background-work-pill", align: "center",
      params: [sel("work", Object.keys(WORK)), bool("compact")],
      states: [["Subagents", {}], ["Subagents + shells", { work: "mixed" }], ["Shells", { work: "shells" }], ["Needs input", { work: "one" }], ["Needs input + running", { work: "oneRunning" }], ["Several need input", { work: "many" }], ["Compact", { compact: true }], ["Compact, needs input", { work: "one", compact: true }]],
      sheet: [{ title: "Running", kind: "rows", items: [["Subagents", { work: "subagents" }], ["Both", { work: "mixed" }], ["Shells", { work: "shells" }]] }, { title: "Needs input", kind: "stack", inset: true, items: [["One waiting", { work: "one" }], ["Waiting, others running", { work: "oneRunning" }], ["Several waiting", { work: "many" }]] }, { title: "Compact", kind: "flow", base: { compact: true }, items: [["", { work: "subagents" }], ["", { work: "one" }]] }],
      tokens: [["pill.minHeight", 36], ["pill.paddingHorizontal", 14], ["pill.compactPaddingHorizontal", 10], ["pill.iconSize", 16], ["pill.iconSpacing", 6], ["pill.dot", 8], ["pill.ringWidth", 1], ["pulse", "0.8 s, 1 → 0.35"]],
      spec: SPEC.work, source: { ios: "UI/SessionChat/View/SessionChatSubagentsSheet.swift", android: "ui/sessionchat/view/SessionChatBackgroundWorkSheet.kt" }, render: live(workPill) },
    { id: "dictation-card", align: "stretch", pad: true,
      params: [sel("variant", ["listening", "finishing", "stopped", "permissionDenied", "modelNotInstalled", "modelDownloading", "modelDownloadStarting", "modelDownloadQueued", "modelDownloadFailed", "recognizerError"]), sel("level", [0, 0.2, 0.4, 0.7, 1], 0.4)],
      states: [["Listening", {}], ["Finishing", { variant: "finishing" }], ["Stopped (Android)", { variant: "stopped" }], ["Permission denied", { variant: "permissionDenied" }], ["Model not installed", { variant: "modelNotInstalled" }], ["Downloading", { variant: "modelDownloading" }], ["Download starting", { variant: "modelDownloadStarting" }], ["Queued (Android)", { variant: "modelDownloadQueued" }], ["Download failed", { variant: "modelDownloadFailed" }], ["Recognizer error", { variant: "recognizerError" }]],
      sheet: [{ title: "Listening", kind: "stack", inset: true, items: [["Listening", {}], ["Finishing", { variant: "finishing" }], ["Stopped (Android)", { variant: "stopped" }]] }, { title: "Unavailable", kind: "stack", inset: true, items: [["Permission denied", { variant: "permissionDenied" }], ["Model not installed", { variant: "modelNotInstalled" }], ["Downloading", { variant: "modelDownloading" }], ["Download starting", { variant: "modelDownloadStarting" }], ["Queued (Android)", { variant: "modelDownloadQueued" }], ["Download failed", { variant: "modelDownloadFailed" }], ["Recognizer error", { variant: "recognizerError" }]] }],
      tokens: [["orb.canvas", 148], ["orb.size", 64], ["orb.barCount", 24], ["orb.barWidth", 3], ["orb.barLength", "4–28"], ["orb.orbit", 40], ["orb.haloAlpha", 0.22], ["card.spacing", "iOS 16, Android 12"], ["transcript.maxHeight", "iOS 72, Android 78"]],
      spec: SPEC.dictation, source: { ios: "Components/Input/OpenCodeDictationCard.swift", android: "ui/components/input/OpenCodeDictationCard.kt" },
      render: live(dictation),
      mount: (stage) => stage.querySelectorAll(".sd-orb").forEach((el) => {
        if (el.dataset.bound) return; el.dataset.bound = "1";
        const level = parseFloat(el.dataset.level);
        if (!level || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
        const lines = [...el.querySelectorAll("line")];
        const tick = (now) => {
          if (!el.isConnected) return;
          const t = now / 1000;
          lines.forEach((l, i) => { const a = (i / 24) * 2 * Math.PI - Math.PI / 2, len = 4 + 24 * level * (0.62 + 0.38 * Math.sin(t * 3 + i * 1.7)); l.setAttribute("x2", (74 + (40 + len) * Math.cos(a)).toFixed(2)); l.setAttribute("y2", (74 + (40 + len) * Math.sin(a)).toFixed(2)); });
          requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      }) },
    { id: "drop-overlay", align: "stretch", overlay: true, cellHeight: 320,
      params: [sel("variant", Object.keys(DROPS))],
      states: [["Accept, images", { variant: "images" }], ["Accept, mixed", { variant: "mixed" }], ["Reject", { variant: "reject" }]],
      tokens: [["dropOverlay.scrim", "black 0.32"], ["dropOverlay.cornerRadius", 22], ["dropOverlay.maxWidth", 420], ["dropOverlay.screenInset", 32], ["dropOverlay.padding", "24, 28"], ["dropOverlay.spacing", 10], ["dropOverlay.dash", "2 wide, 8 on, 6 off"], ["dropOverlay.preview", 44], ["dropOverlay.maxPreviews", 4]],
      spec: SPEC.drop, source: { ios: "Components/Feedback/OpenCodeDropOverlay.swift", android: "ui/components/feedback/OpenCodeDropOverlay.kt" }, render: drop },
  ]);
  void IF;
})();
