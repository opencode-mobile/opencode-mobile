// Component group: navigation. Compose other groups with Kit.use(id, props).
(() => {
  const { esc, I, sel, text, bool, ACCENTS } = Kit;
  const has = (name) => !!Kit.components[name];
  const button = (p) => Kit.use("button", p);
  const platformOf = (ctx) => ctx?.platform ?? Kit.ctx.platform;

  /* Sibling components, with a small local stand-in while another group's file is unfinished. */
  const tag = (title, accent) => has("tag")
    ? Kit.use("tag", { title, label: title, size: "small", tone: accent ? "accent" : "neutral" })
    : `<span class="c-nv-tag${accent ? " accent" : ""}">${esc(title)}</span>`;
  const field = (hint, value, kind = "text") => has("text-input")
    ? Kit.use("text-input", { hint, value, kind })
    : `<div class="c-nv-field"><input placeholder="${esc(hint)}" value="${esc(value)}"${kind === "password" ? ' type="password"' : ""}></div>`;
  const notice = (message, warning) => has("inline-notice")
    ? Kit.use("inline-notice", { message, title: message, tone: warning ? "error" : "neutral", variant: "banner" })
    : `<div class="c-nv-notice${warning ? " warn" : ""}">${esc(message)}</div>`;
  const illustration = (topic) => has("skeleton-illustration")
    ? Kit.use("skeleton-illustration", { topic })
    : '<div class="c-nv-skel"><i></i><i></i><i></i></div>';
  const emptyState = (title, message, icon) => `<div class="c-nv-empty">${I(icon, 24)}${has("empty-state") ? Kit.use("empty-state", { title, message, icon: false, fills: false }) : `<b>${esc(title)}</b><span>${esc(message)}</span>`}</div>`;

  const scrimDoc = '<div class="c-ov-doc"><div class="l s"></div><div class="l"></div><div class="l m"></div><div class="l"></div><div class="l s"></div><div class="l m"></div></div>';
  const overlay = (kind, card, again, extra = "") => `<div class="c-ov" data-nv-ov="${kind}">${scrimDoc}<div class="c-ov-reopen">${button({ variant: "fullWidthText", contrast: "high", title: again })}</div><div class="c-ov-scrim"></div>${card}${extra}</div>`;

  /* Top Bar */
  const STATUS = { live: ["live", "--success"], connecting: ["connecting", "--warning"], offline: ["offline", "--error"], authFailed: ["Authentication failed", "--error"] };
  const statusLabel = (s) => `<span class="c-tb-status"><span class="dot" style="--c:var(${STATUS[s][1]})"></span><span>${esc(STATUS[s][0])}</span></span>`;
  const tbIcon = (icon, disabled, attrs = "") => `<button class="c-tb-ic press${disabled ? " off" : ""}"${disabled ? " disabled" : ""} ${attrs}>${I(icon, 18)}</button>`;
  const TB_LEADING = { back: "arrow_back", close: "close", customIcon: "edit", sidebar: "sidebar" };
  const tbActions = (p) => {
    const off = !p.actionsEnabled;
    const label = () => button({ variant: "compactText", contrast: "low", title: p.actionLabel || "Done", disabled: off });
    if (p.actions === "menu") return [tbIcon("more_vert", off, "data-nv-menu")];
    if (p.actions === "icons") return [tbIcon("search", off), tbIcon("more_vert", off, "data-nv-menu")];
    if (p.actions === "label") return [label()];
    if (p.actions === "three") return [tbIcon("search", off), tbIcon("edit", off), tbIcon("more_vert", off, "data-nv-menu")];
    if (p.actions === "labelIcons") return [label(), tbIcon("search", off), tbIcon("more_vert", off, "data-nv-menu")];
    return [];
  };
  const tbSubtitle = (p) => {
    const sub = (p.subtitle ?? "").trim(), status = p.status !== "none" ? p.status : null;
    if (!sub && !status) return "";
    if (!sub) return `<div class="c-tb-sub">${statusLabel(status)}</div>`;
    const lead = p.subtitleLeading === "letter" ? `<span class="c-tb-letter" style="background:${ACCENTS.blue[Kit.ctx.dark ? 2 : 0]};color:${ACCENTS.blue[Kit.ctx.dark ? 3 : 1]}">O</span>` : p.subtitleLeading === "worktree" ? `<span class="c-tb-wt">${I("call_split", 12)}</span>` : "";
    return `<div class="c-tb-sub">${lead}<span class="c-tb-subtext${status ? " capped" : ""}">${esc(sub)}</span>${status ? `<span class="c-tb-sep">·</span>${statusLabel(status)}` : ""}</div>`;
  };
  const tbTitle = (p) => {
    const title = (p.title ?? "").trim();
    if (!title) return '<div class="c-tb-title none"></div>';
    const row = p.switcher ? `<button class="c-tb-switch"><span class="c-tb-t">${esc(title)}</span>${I("keyboard_arrow_down", 18)}</button>` : `<div class="c-tb-t">${esc(title)}</div>`;
    return `<div class="c-tb-title">${row}${tbSubtitle(p)}</div>`;
  };
  const topBar = (p, ctx) => {
    const ios = platformOf(ctx) === "ios", rail = ios && p.duo;
    const stays = p.leading === "sidebar";
    const showLead = p.leading !== "none" && (!rail || stays);
    const all = tbActions(p);
    const row = rail ? all.filter((x) => !x.includes("c-tb-ic")) : all;
    const bar = `<div class="c-topbar${p.divider ? " div" : ""}${showLead ? "" : " nolead"}${rail && showLead ? " duolead" : ""}"><div class="c-tb-row">${showLead ? tbIcon(TB_LEADING[p.leading]) : ""}${tbTitle(p)}${row.length ? `<div class="c-tb-actions">${row.join("")}</div>` : ""}</div></div>`;
    if (!rail) return bar;
    const items = [...(p.leading !== "none" && !stays ? [tbIcon(TB_LEADING[p.leading])] : []), ...all.filter((x) => x.includes("c-tb-ic"))];
    return `<div class="c-tb-duo"><div class="c-tb-duo-main">${bar}</div><div class="c-tb-rail"><div class="c-tb-rail-status"></div>${items.join("")}</div></div>`;
  };

  /* Action Bar */
  const actionBar = (p) => {
    const row = p.target && !p.compact ? `<div class="c-ab-target">${I("folder", 22)}<div class="c-ab-text"><div class="c-ab-head"><span class="c-ab-title">${esc(p.name)}</span>${p.tag ? tag(p.tag) : ""}</div><div class="c-ab-path"><bdi>${esc(p.path)}</bdi></div></div></div>` : "";
    return `<div class="c-actionbar">${row}${button({ variant: "fullWidthText", size: "large", contrast: "high", title: p.action, disabled: !p.enabled })}</div>`;
  };

  /* Overflow Menu */
  const overflowEntries = (p) => {
    const e = [{ title: "Session context", icon: p.selection ? "check" : "architecture", enabled: p.enabled }];
    if (p.reason) e.push({ title: "Move to worktree…", icon: "call_split", enabled: false, subtitle: "Needs an opencode v2 server" });
    e.push({ title: "Copy session ID", icon: p.selection ? null : "content_copy", enabled: p.enabled, divider: p.divider });
    if (+p.count >= 3) e.push({ title: "Settings", icon: "settings", enabled: p.enabled });
    if (+p.count >= 4) e.push({ title: "Search", icon: "search", enabled: p.enabled });
    if (p.destructive) e.push({ title: "Delete", icon: "close", enabled: p.enabled, destructive: true });
    if (p.textOnly) e.forEach((x) => { x.icon = null; });
    return e;
  };
  const overflowPanel = (p) => {
    const entries = overflowEntries(p), reserve = entries.some((x) => x.icon);
    return `<div class="c-om-panel">${entries.map((x) => `${x.divider ? '<div class="c-om-div"></div>' : ""}<button class="c-om-entry${x.destructive ? " danger" : ""}${x.subtitle ? " two" : ""}"${x.enabled ? "" : " disabled"}>${reserve ? `<span class="c-om-icon">${x.icon ? I(x.icon, 20) : ""}</span>` : ""}<span class="c-om-text"><span class="c-om-title">${esc(x.title)}</span>${x.subtitle ? `<span class="c-om-sub">${esc(x.subtitle)}</span>` : ""}</span></button>`).join("")}</div>`;
  };
  const overflow = (p, ctx) => {
    const anchored = p.host === "anchored";
    const trigger = anchored
      ? `<div class="c-om-anchor">${button({ variant: "iconCircle", contrast: "low", icon: "add" })}</div>`
      : topBar({ ...Kit.components["top-bar"].defaults, title: "Session", subtitle: "", status: "none", leading: "back", actions: "menu", divider: true }, ctx);
    return `<div class="c-om${anchored ? " anchored" : ""}${p.open ? " open" : ""}" data-nv-om>${trigger}${overflowPanel(p)}</div>`;
  };

  /* Bottom Sheet */
  const decorator = (kind, icon, topic) => kind === "icon" ? `<span class="c-bs-deco">${I(icon, 20)}</span>` : kind === "illustration" ? `<span class="c-bs-illo">${illustration(topic)}</span>` : "";
  const entry = (o) => `<button class="c-bs-entry${o.destructive ? " danger" : ""}"${o.enabled === false ? " disabled" : ""}${o.selected ? ' aria-selected="true"' : ""}>${o.start ?? ""}<span class="c-bs-entry-text"><span class="c-bs-entry-head"><span class="c-bs-entry-title">${esc(o.title)}</span>${o.tag ? tag(o.tag) : ""}${o.isNew ? tag("New", true) : ""}</span>${o.subtitle ? `<span class="c-bs-entry-sub">${esc(o.subtitle)}</span>` : ""}</span>${o.selected ? `<span class="c-bs-deco">${I("check", 20)}</span>` : (o.end ?? "")}</button>`;
  const ico = (name) => decorator("icon", name);
  const sheetEntries = (p) => {
    const n = +p.items, out = [entry({ title: "Open current project", enabled: p.entryEnabled, selected: p.entrySelected, tag: p.entryTag ? "Recommended" : "", subtitle: p.entrySubtitle ? "Tool cards and groups between the agent's messages" : "", start: decorator(p.start, "folder", "timelineActivityCard"), end: decorator(p.end, "chevron_right", "timelineStepRail") }), entry({ title: "Create new session", isNew: p.entryNew, start: ico("add") })];
    if (n >= 4) out.push(entry({ title: "Rename session", start: ico("edit") }), entry({ title: "Fork session", end: ico("chevron_right") }));
    if (n >= 6) out.push(entry({ title: "Review diff", start: ico("description") }), entry({ title: "Abort active work", start: ico("stop_circle"), destructive: p.destructive }));
    if (n >= 12) for (let i = 1; i <= 6; i++) out.push(entry({ title: `Extra action ${i}` }));
    return out.join("");
  };
  const sheetBody = (p, ctx) => {
    if (p.content === "folders") return Array.from({ length: 20 }, (_, i) => entry({ title: `Folder ${i + 1}`, start: ico("folder"), end: ico("chevron_right") })).join("");
    if (p.content === "form") return `<div class="c-bs-inset">${field("Server URL", "http://127.0.0.1:4096/", "url")}</div><div class="c-bs-inset">${field("Password", "gallery password", "password")}</div>${platformOf(ctx) === "android" ? sheetEntries(p) : ""}`;
    return sheetEntries(p);
  };
  const bottomSheet = (p, ctx) => {
    const lead = p.headerBack ? button({ variant: "iconCircle", contrast: "low", icon: "arrow_back" }) : "";
    const edit = p.headerEdit ? button({ variant: "compactText", contrast: "low", title: "Edit" }) : "";
    const title = p.running ? `<span class="c-bs-title running"><span class="c-bs-shimmer">${esc(p.title)}</span><span class="c-bs-elapsed">0:42</span></span>` : `<span class="c-bs-title">${esc(p.headerBack ? "Anthropic" : p.title)}</span>`;
    const pinTop = p.content === "folders" ? `<div class="c-bs-inset c-bs-filter">${field("Folder path", "", "filter")}</div>` : p.pinnedTop ? entry({ title: "Go up", start: ico("arrow_upward") }) + entry({ title: "/Users/example/project", start: ico("folder_check") }) : "";
    const foot = p.pinnedBottom ? `<div class="c-bs-foot">${button({ variant: "fullWidthText", contrast: "high", title: "Done" })}</div>` : "";
    const card = `<div class="c-bs-card" data-height="${p.height}" data-keyboard="${p.keyboard}"><header class="c-bs-header">${lead}${title}${edit}<span class="c-bs-close">${button({ variant: "iconCircle", contrast: "low", icon: "close" })}</span></header>${pinTop ? `<div class="c-bs-pin">${pinTop}</div>` : ""}<div class="c-bs-body">${sheetBody(p, ctx)}</div>${foot}</div>`;
    const kb = p.keyboard !== "dismiss" && p.keyboardUp ? '<div class="c-bs-kb"><span>Keyboard</span></div>' : "";
    return overlay("sheet", card, "Show bottom sheet", kb);
  };

  /* Dialog and Modal */
  const dialog = (p) => {
    const notes = p.notes && !p.field ? `<div class="c-dg-notes">${notice("3 uncommitted changes will be lost.", true)}${notice("1 session uses this worktree. It stays on Home but can't run here again.")}</div>` : "";
    const card = `<div class="c-dg-card"><div class="c-dg-head"><h4>${esc(p.title)}</h4>${p.subtitle ? `<p>${esc(p.subtitle)}</p>` : ""}</div>${notes}${p.field ? `<div class="c-dg-field">${field("Title", "Importing icons")}</div>` : ""}<div class="c-dg-opts">${button({ variant: "fullWidthText", contrast: "low", title: "Cancel", disabled: !p.enabled })}${button({ variant: "fullWidthText", contrast: p.destructive && !p.field ? "destructive" : "high", title: p.primary, disabled: !p.enabled })}</div></div>`;
    return overlay("dialog", `<div class="c-dg-center">${card}</div>`, "Show dialog");
  };
  const modal = (p, ctx) => {
    const ios = platformOf(ctx) === "ios";
    const art = `<div class="c-md-art">${illustration("chipTimelineGlance")}</div>`;
    const copy = p.body && ios ? `<p>${esc(p.body)}</p>` : "";
    const card = `<div class="c-md-card"><span class="c-md-close">${button({ variant: "iconCircle", contrast: "low", icon: "close" })}</span><div class="c-md-content">${art}<h4>${esc(p.title)}</h4>${copy}<div class="c-md-cta">${button({ variant: "fullWidthText", contrast: "high", title: p.cta })}</div></div></div>`;
    return overlay("modal", `<div class="c-dg-center">${card}</div>`, "Show modal");
  };

  /* Snackbar */
  const snackCard = (p) => `<div class="c-snack ${p.variant === "error" ? "error" : ""}${p.actionLabel ? " has-action" : ""}"><span class="c-snack-text">${esc(p.message)}</span>${p.actionLabel ? `<button class="c-snack-action press">${esc(p.actionLabel)}</button>` : ""}</div>`;
  const SNACKS = { default: { message: "Session compacted.", variant: "default", actionLabel: "" }, error: { message: "Couldn't compact session context.", variant: "error", actionLabel: "" }, action: { message: "/btw failed: “What did we change so far?”", variant: "error", actionLabel: "Retry" } };
  const snackbar = (p) => p.live
    ? `<div class="c-snack-demo" data-nv-snack><div class="c-snack-fire">${Object.keys(SNACKS).map((k) => `<span data-fire="${k}">${button({ variant: "compactText", contrast: "low", title: k[0].toUpperCase() + k.slice(1) })}</span>`).join("")}</div><div class="c-snack-host"></div></div>`
    : `<div class="c-snack-wrap">${snackCard(p)}</div>`;

  /* File Tabs */
  const FILES = ["README.md", "FilesView.swift", "FilesViewModel.swift", "build.gradle.kts", "a-very-long-file-name-that-keeps-going.md"];
  const tabTitle = (t, ios) => ios && t.length > 24 ? `<span class="c-ft-mid"><span class="a">${esc(t.slice(0, -10))}</span><span class="b">${esc(t.slice(-10))}</span></span>` : `<span class="c-ft-end">${esc(t)}</span>`;
  const fileTab = (t, selected, ios) => `<div class="c-ft-tab${selected ? " sel" : ""}" data-tab="${esc(t)}"><button class="c-ft-name">${tabTitle(t, ios)}</button><button class="c-ft-x" aria-label="Close ${esc(t)}">${I("close", 14)}</button></div>`;
  const fileTabs = (p, ctx) => {
    const ios = platformOf(ctx) === "ios", list = p.tabs === "single" ? ["notes.txt"] : FILES.slice(0, p.tabs === "two" ? 2 : 5);
    const selected = p.tabs === "single" ? "notes.txt" : list[Math.min(+p.selected, list.length - 1)];
    return `<div class="c-ft" data-nv-tabs="${esc(list.join("|"))}"><div class="c-ft-scroll"><div class="c-ft-row">${list.map((t) => fileTab(t, t === selected, ios)).join("")}</div></div>${p.divider ? '<div class="c-ft-div"></div>' : ""}<div class="c-ft-again">${button({ variant: "compactText", contrast: "low", title: ios ? "Reopen samples" : "Reopen all" })}</div></div>`;
  };

  /* Page Indicator and Bottom Fade */
  const pageIndicator = (p) => {
    const n = Math.max(1, +p.pages || 1), cur = Math.min(n - 1, Math.max(0, +p.current || 0));
    return `<div class="c-pi" role="img" aria-label="Page ${cur + 1} of ${n}">${Array.from({ length: n }, (_, i) => `<i class="${i === cur ? "on" : ""}"></i>`).join("")}</div>`;
  };
  const bottomFade = (p) => `<div class="c-bf"><div class="c-bf-content"><b>Content under the dock</b><span>The fade covers the last lines without taking taps.</span><i></i><i></i><i></i><i></i><i></i></div><div class="c-bf-fade" style="height:${+p.height}px"></div>${p.dock ? '<div class="c-bf-dock float"></div>' : ""}</div>`;

  /* Split Pane: laid out at tablet width and scaled to fit a phone preview. */
  const splitPane = (p, ctx) => {
    const collapsed = p.state === "collapsed", width = { default: 360, min: 280, max: 480, dragging: 420, collapsed: 360 }[p.state] ?? 360;
    const list = has("list-item")
      ? Kit.use("list-item", { title: "Selected row", subtitle: "Highlighted in the list", caption: "", decorator: "none", selected: true, dot: "none", status: "none", statusText: "" }) + Kit.use("list-item", { title: "Another row", subtitle: "Tap selects on tablets", caption: "", decorator: "none", dot: "none", status: "none", statusText: "" })
      : "";
    const bar = (title, leading) => topBar({ ...Kit.components["top-bar"].defaults, title, subtitle: "", status: "none", leading, actions: "none" }, ctx);
    return `<div class="c-sp-frame" data-nv-split data-width="${width}" data-collapsed="${collapsed ? 1 : 0}"><div class="c-sp${p.state === "dragging" ? " dragging" : ""}"><div class="c-sp-lead"><div class="c-sp-lead-in">${bar("Leading", "none")}${list}</div></div><div class="c-sp-divider"><div class="c-sp-handle"><span></span></div></div><div class="c-sp-detail">${bar("Detail", "sidebar")}<div class="c-sp-empty">${emptyState(collapsed ? "Sidebar hidden" : "Sidebar shown", "The top-bar button toggles the leading pane.", "sidebar")}</div></div></div></div>`;
  };

  const DV = {
    topBar: [
      { severity: "medium", title: "Bar height is 80pt on iOS and 72dp on Android with a title" },
      { severity: "medium", title: "Session Search hand-builds its header with different spacing per platform" },
      { severity: "low", title: "Android bar has no background; iOS paints background-base" },
      { severity: "low", title: "Android caps subtitle width at 220dp next to a status; iOS does not" },
    ],
    actionBar: [
      { severity: "medium", title: "Target title is 14pt on iOS and 16sp on Android" },
      { severity: "low", title: "Target subtitle gap is 2pt on iOS and 0 on Android" },
      { severity: "low", title: "Compact change animates on Android and snaps on iOS" },
    ],
    splitPane: [
      { severity: "medium", title: "Split layout breakpoints differ between platforms" },
      { severity: "low", title: "Idle grip is text-weaker on iOS and text-weak on Android" },
      { severity: "low", title: "Toggle easing differs: ease-out on iOS, fast-out-slow-in on Android" },
    ],
    overflow: [
      { severity: "high", title: "Long titles truncate at 240dp on Android but never on iOS" },
      { severity: "medium", title: "Subtitle rows are taller on Android" },
      { severity: "medium", title: "Android entries have no pressed feedback" },
      { severity: "low", title: "Tokens list a 12 anchor inset and 4 elevation; code uses 4 and none" },
      { severity: "low", title: "Below placement and context menu each exist on one platform" },
      { severity: "low", title: "Docs call dividers and subtitles out of scope, but both ship" },
    ],
    sheet: [
      { severity: "medium", title: "Docs describe header-only drag at 96/1200; code uses stops at 0.6/900 and body drag" },
      { severity: "medium", title: "Docs omit the fitAllContent height" },
      { severity: "low", title: "Entry subtitle is text-weak on iOS and text-base on Android" },
      { severity: "low", title: "Half detent differs by 4 between platforms" },
      { severity: "low", title: "Header actions support different content per platform" },
    ],
    dialog: [
      { severity: "medium", title: "Android card is 48dp narrower at its width cap" },
      { severity: "medium", title: "Android scrim comes from the window theme, not the 0.32 token" },
      { severity: "low", title: "Docs omit dialog notes" },
    ],
    modal: [{ severity: "low", title: "Gallery sample omits body copy and uses other spacing on Android" }],
    snackbar: [
      { severity: "high", title: "Tap dismisses on iOS; Android has neither tap nor the documented swipe" },
      { severity: "medium", title: "Error text is icon-invert on iOS and always white on Android" },
      { severity: "medium", title: "Android error snackbar can lose its color while fading out" },
      { severity: "medium", title: "Docs say no action button, but both platforms ship one" },
      { severity: "low", title: "snackbar.maxWidth 560 is not applied by our code" },
      { severity: "low", title: "Shadow differs from the 4 elevation token on Android" },
    ],
    fileTabs: [
      { severity: "medium", title: "Long names truncate in the middle on iOS and at the end on Android" },
      { severity: "low", title: "Accessibility role is button on iOS and tab on Android" },
    ],
    pageIndicator: [{ severity: "low", title: "Motion differs: 200 ms ease on iOS, width-only spring on Android" }],
  };

  Kit.define([
    { id: "top-bar", doc: "top-bar", align: "stretch",
      params: [text("title", "Session"), text("subtitle", ""), sel("subtitleLeading", ["none", "letter", "worktree"]), sel("status", ["none", "live", "connecting", "offline", "authFailed"]), sel("leading", ["back", "close", "customIcon", "sidebar", "none"]), sel("actions", ["icons", "label", "three", "labelIcons", "none"]), text("actionLabel", "Done"), bool("actionsEnabled", true), bool("divider"), bool("switcher"), bool("duo", false, "duo vertical bar (iOS)")],
      states: [["Back + actions", {}], ["Title only", { leading: "none", actions: "none", title: "Component Gallery" }], ["Status subtitle", { status: "live", title: "Importing icons explanation", actions: "icons", divider: true }], ["Project + status", { title: "Wire session project chrome", subtitle: "opencode-mobile", subtitleLeading: "letter", status: "live", divider: true }], ["Disabled actions", { actionsEnabled: false }], ["Title switcher", { leading: "none", title: "My workstation", status: "live", switcher: true, actions: "icons" }]],
      sheet: [
        { title: "Leading", kind: "stack", base: { actions: "none" }, items: [["none", { leading: "none", title: "Component Gallery" }], ["back", { leading: "back" }], ["close", { leading: "close", title: "Session actions" }], ["customIcon", { leading: "customIcon", title: "Rename session" }], ["customIcon · sidebar", { leading: "sidebar", title: "Detail" }]] },
        { title: "Subtitle and accessory", kind: "stack", base: { leading: "back", actions: "icons" }, items: [["Two-line", { subtitle: "Importing icons explanation" }], ["Status · live", { title: "Importing icons explanation", status: "live" }], ["Status · connecting", { title: "Importing icons explanation", status: "connecting" }], ["Status · offline", { title: "Importing icons explanation", status: "offline" }], ["Status · auth failed", { title: "Importing icons explanation", status: "authFailed" }], ["Project letter + status", { title: "Wire session project chrome", subtitle: "opencode-mobile", subtitleLeading: "letter", status: "live" }], ["Worktree + status", { title: "Wire session project chrome", subtitle: "crisp-cactus", subtitleLeading: "worktree", status: "connecting" }]] },
        { title: "Trailing actions", kind: "stack", base: { leading: "back" }, items: [["None", { actions: "none" }], ["Icons", { actions: "icons", title: "Session actions" }], ["Label", { actions: "label", title: "Project settings" }], ["Three icons (maximum)", { actions: "three" }], ["Label + two icons", { actions: "labelIcons" }], ["Icons disabled", { actions: "icons", actionsEnabled: false }], ["Label disabled", { actions: "label", actionLabel: "Save", actionsEnabled: false, title: "Rename session", leading: "customIcon" }]] },
        { title: "Chrome", kind: "stack", base: { leading: "back", actions: "icons" }, items: [["Divider", { divider: true }], ["Title switcher", { leading: "none", title: "My workstation", status: "live", switcher: true }], ["Long title truncates", { title: "A session title long enough to truncate on one line", subtitle: "A subtitle that is also long enough to truncate on one line" }], ["iPhone Duo vertical bar (iOS only)", { duo: true, title: "Wire session project chrome", subtitle: "opencode-mobile", subtitleLeading: "letter", status: "live", actions: "labelIcons", divider: true }]] },
      ],
      tokens: [["topBar.minHeight", 56], ["topBar.paddingHorizontal", 12], ["topBar.paddingVertical", 12], ["topBar.itemSpacing", 8], ["topBar.titleSpacing", 12], ["topBar.titleEdgeInsetWhenNoLeading", 18], ["topBar.titleBlockHeight", 48], ["topBar.subtitleSpacing", 0], ["topBar.dividerThickness", 1], ["topBar.iconButton", 40], ["topBar.icon", 18], ["topBar.letterBadge", "16, radius 4, 9 bold"], ["connectionStatusLabel.dotSize", 6], ["connectionStatusLabel.dotSpacing", 4], ["verticalBar.item / pitch", "48 / 60 (iOS Duo)"], ["divider", "border-weaker-base"]],
      source: { ios: "Components/Chrome/OpenCodeTopBar.swift", android: "ui/components/chrome/OpenCodeTopBar.kt" }, divergences: DV.topBar, render: topBar },

    { id: "action-bar", align: "stretch", dock: true,
      params: [text("name", "opencode-mobile"), text("tag", "git"), text("path", "/Users/example/Projects/opencode-mobile"), text("action", "Add this folder"), bool("target", true), bool("enabled", true), bool("compact")],
      states: [["Enabled", {}], ["Disabled reason", { enabled: false, action: "Already on Home", name: "notes", tag: "", path: "/Users/example/notes" }], ["Compact (keyboard up)", { compact: true }], ["No target", { target: false }], ["Long name and path", { name: "a-very-long-folder-name-that-does-not-fit-on-one-line", path: "/Users/example/Projects/clients/sample/monorepo/packages/a-very-long-folder-name-that-does-not-fit-on-one-line" }]],
      tokens: [["actionBar.paddingHorizontal", "layout.screenGutter (16)"], ["actionBar.paddingVertical", 12], ["actionBar.targetSpacing", 10], ["actionBar.targetIcon", 22], ["actionBar.titleTagSpacing", 6], ["actionBar.divider", "1, border-weaker-base"], ["button", "fullWidthText, large, high"]],
      spec: "## Intent\nBottom-pinned bar with one full-width primary action and, above it, the thing that action applies to.\n\n## Public API\n- `target`: optional icon, title, tag, and path-like subtitle.\n- `actionTitle`: the button label. When disabled, it states the reason.\n- `enabled`, `compact`.\n\n## Behavior\n`compact` hides the target row, for example while the keyboard is up. The button always stays. The subtitle truncates at its start so the end of a path stays readable. The fill extends under the home indicator or navigation bar.",
      source: { ios: "Components/Chrome/OpenCodeActionBar.swift", android: "ui/components/chrome/OpenCodeActionBar.kt" }, divergences: DV.actionBar, render: actionBar },

    { id: "split-pane", align: "stretch", pad: true,
      params: [sel("state", ["default", "min", "max", "dragging", "collapsed"])],
      states: [["Default · 360", {}], ["Minimum · 280", { state: "min" }], ["Maximum · 480", { state: "max" }], ["Dragging", { state: "dragging" }], ["Collapsed", { state: "collapsed" }]],
      tokens: [["splitPane.minLeading", 280], ["splitPane.defaultLeading", 360], ["splitPane.maxLeading", 480], ["splitPane.collapseThreshold", 220], ["splitPane.reopenThreshold", 120], ["splitPane.divider", "1, border-weak-base"], ["splitPane.handleGrip", "6 × 40"], ["splitPane.handleHit", "28 × 72"], ["splitPane.tapSlop", 4], ["splitPane.animation", "300 ms"], ["split.ios", "regular × regular, usable ≥ 681"], ["split.android", "window ≥ 720, smallest ≥ 600"]],
      spec: "## Intent\nTwo-pane container for wide windows: a resizable, collapsible leading pane, a divider with a grab handle, and a trailing detail pane.\n\n## Public API\n- `screen`: Home, Settings, Changes, Terminals, or Files. Width and collapsed state persist per screen.\n- `leading` and `trailing` content. The trailing pane receives a control for its sidebar toggle.\n\n## Behavior\nDrag the handle between 280 and 480. Releasing below 220 snaps closed and keeps the remembered width. A tap on the handle or the top-bar sidebar button toggles the pane. Changes, Terminals, and Files force an ancestor Home sidebar closed without saving that collapse. On iPhone Duo the iOS divider can sit in the fold.\n\nThis preview lays the pane out at 760 wide and scales it to the phone.",
      source: { ios: "Components/Layout/OpenCodeSplitPane.swift", android: "ui/components/layout/OpenCodeSplitPane.kt" }, divergences: DV.splitPane, render: splitPane },

    { id: "overflow-menu", doc: "overflow-menu", align: "stretch", cellHeight: 420,
      params: [sel("host", ["topBar", "anchored"]), sel("count", ["2", "3", "4"], "4"), bool("enabled", true), bool("selection"), bool("destructive", true), bool("divider"), bool("reason", false, "disabled with reason"), bool("textOnly", false, "text only"), bool("open", true)],
      states: [["Default", {}], ["Disabled entries", { enabled: false }], ["Selection rows", { selection: true, destructive: false }], ["Subtitle and divider", { reason: true, divider: true, count: "3" }], ["Text only", { textOnly: true, destructive: false, count: "3" }], ["Anchored to a control", { host: "anchored", count: "3", destructive: false }], ["Closed", { open: false }]],
      tokens: [["overflowMenu.cornerRadius", 8], ["overflowMenu.minWidth", 200], ["overflowMenu.paddingVertical", 4], ["overflowMenu.entry.minHeight", 48], ["overflowMenu.entry.paddingHorizontal", 12], ["overflowMenu.entry.iconSize", 20], ["overflowMenu.entry.iconSpacing", 12], ["overflowMenu.divider.paddingVertical", 4], ["overflowMenu.anchorEndInset (code)", 4], ["overflowMenu.anchorTopOffset", 8], ["disabled opacity", 0.7], ["border", "1, border-weak-base"]],
      source: { ios: "Components/Chrome/OpenCodeOverflowMenu.swift", android: "ui/components/chrome/OpenCodeOverflowMenu.kt" }, divergences: DV.overflow, render: overflow },

    { id: "bottom-sheet", doc: "bottom-sheet", overlay: true, cellHeight: 500,
      params: [text("title", "Session actions"), sel("height", ["fitContent", "half", "fitAllContent"]), sel("keyboard", ["dismiss", "keep", "expand"]), sel("content", ["actions", "form", "folders"]), sel("items", ["2", "4", "6", "12"], "4"), sel("start", ["icon", "none", "illustration"]), sel("end", ["none", "icon", "illustration"]), bool("entryEnabled", true), bool("entrySelected"), bool("entryTag"), bool("entryNew"), bool("entrySubtitle"), bool("destructive"), bool("headerBack"), bool("headerEdit"), bool("running"), bool("pinnedTop"), bool("pinnedBottom"), bool("keyboardUp", true, "keyboard up")],
      states: [["Fit content", {}], ["Fit content · over limit rests at half", { items: "12" }], ["Half", { height: "half", items: "12" }], ["Fit all content", { height: "fitAllContent", items: "12" }], ["Entry states", { entrySelected: true, entryTag: true, entryNew: true, items: "6", destructive: true }], ["Disabled entry", { entryEnabled: false, items: "2" }], ["Decorators · illustration", { start: "illustration", end: "icon", entrySubtitle: true, items: "2" }], ["Header back + Edit", { headerBack: true, headerEdit: true }], ["Running title", { running: true, title: "Background work", items: "2" }], ["Pinned top and bottom", { pinnedTop: true, pinnedBottom: true, items: "6" }], ["Keyboard · keep", { keyboard: "keep", content: "form", items: "2", title: "Add server" }], ["Keyboard · expand", { keyboard: "expand", content: "folders", height: "half", title: "Choose folder" }]],
      tokens: [["bottomSheet.inset", 8], ["bottomSheet.cornerRadiusTop", 24], ["bottomSheet.cornerRadius", "display − 8 (47 fallback)"], ["bottomSheet.maxWidth (regular)", 560], ["bottomSheet.header.padding", "12 / 20 / 8"], ["bottomSheet.content.paddingBottom", 20], ["bottomSheet.footer.paddingTop", 8], ["bottomSheet.entry.minHeight", 48], ["bottomSheet.entry.padding", "12 / 20"], ["bottomSheet.entry.decorator", "24, icon 20"], ["bottomSheet.entry.illustration", "72 × 44, radius 8"], ["bottomSheet.entry.press", "scale 0.97, radius 10"], ["bottomSheet.compactMaxHeight", 420], ["bottomSheet.scrim.alpha", 0.32], ["motion.enter", "320 ms (0.4, 0, 0.2, 1)"], ["motion.exit", "280 ms (0.4, 0, 1, 1)"], ["drag.flickVelocity (code)", 900], ["drag.dismissFraction (code)", 0.6], ["drag.settle", "spring 0.31 s, damping 0.82"]],
      source: { ios: "Components/Chrome/OpenCodeBottomSheet.swift", android: "ui/components/chrome/OpenCodeBottomSheet.kt" }, divergences: DV.sheet, render: bottomSheet },

    { id: "dialog", doc: "dialog", overlay: true, cellHeight: 390,
      params: [text("title", "Clear all data?"), text("subtitle", "This cannot be undone."), text("primary", "Clear"), bool("destructive", true), bool("enabled", true), bool("field"), bool("notes")],
      states: [["Confirmation · destructive", {}], ["Confirmation · neutral", { destructive: false, title: "Fork session?", subtitle: "Creates a copy from this message.", primary: "Fork" }], ["Without subtitle", { subtitle: "" }], ["Options disabled", { enabled: false }], ["Form", { field: true, title: "Rename session", subtitle: "Choose a short title for this session.", primary: "Save" }], ["Notes", { notes: true, title: "Remove worktree?", subtitle: "Deletes the folder on the server.", primary: "Remove anyway" }]],
      tokens: [["dialog.cornerRadius", 16], ["dialog.maxWidth", 360], ["dialog.screenPaddingHorizontal", 24], ["dialog.paddingHorizontal", 20], ["dialog.paddingVertical", 20], ["dialog.contentSpacing", 16], ["dialog.titleSubtitleSpacing", 8], ["dialog.optionSpacing", 8], ["dialog.shadow.ios", "black 18%, radius 16, y 8"], ["dialog.shadowElevation.android", 8], ["scrim (iOS)", 0.32], ["presentation (iOS)", "200 ms fade"], ["surface", "surface-base"]],
      source: { ios: "Components/Feedback/OpenCodeDialog.swift", android: "ui/components/feedback/OpenCodeDialog.kt" }, divergences: DV.dialog, render: dialog },

    { id: "modal", overlay: true, cellHeight: 470,
      params: [text("title", "See the work at a glance"), text("body", "Tool calls show as chips under the sentence that explains them."), text("cta", "Got it")],
      states: [["Promo", {}]],
      tokens: [["modal.cornerRadius", 24], ["modal.maxWidth", 360], ["modal.screenPadding", 24], ["modal.padding", 20], ["modal.paddingTop", 48], ["modal.closeInset", 10], ["modal.art", "150 high, radius 20"], ["shadow.ios", "black 18%, radius 16, y 8"], ["shadowElevation.android", 8], ["surface", "surface-base"]],
      spec: "## Intent\nCentered card with a close button and custom content, for promos and short walkthroughs.\n\n## Public API\n- `isPresented` on iOS, `visible` and `onDismissRequest` on Android.\n- `content`: any caller content.\n\n## Behavior\nUses the same window presentation as Dialog. Scrim tap and Close dismiss on both platforms, and system Back also dismisses on Android. The card is `min(360, screen − 48)` wide on both platforms.",
      source: { ios: "Components/Feedback/OpenCodeModal.swift", android: "ui/components/feedback/OpenCodeModal.kt" }, divergences: DV.modal, render: modal },

    { id: "snackbar", doc: "snackbar", align: "stretch", dock: true,
      params: [text("message", "Session compacted."), sel("variant", ["default", "error"]), text("actionLabel", ""), bool("live", false, "live demo")],
      states: [["Default", {}], ["Error", { variant: "error", message: "Couldn't compact session context." }], ["Action", { variant: "error", message: "/btw failed: “What did we change so far?”", actionLabel: "Retry" }], ["Default with action", { message: "Session archived.", actionLabel: "Undo" }], ["Long message", { message: "The server closed the connection while this session was still syncing its latest messages." }]],
      sheet: [
        { title: "Variants", kind: "stack", items: [["Default", {}], ["Error", { variant: "error", message: "Couldn't compact session context." }], ["Error + action", { variant: "error", message: "/btw failed: “What did we change so far?”", actionLabel: "Retry" }], ["Default + action", { message: "Session archived.", actionLabel: "Undo" }], ["Long message", { message: "The server closed the connection while this session was still syncing its latest messages." }]] },
        { title: "Try it · 4 s, or 10 s with an action", kind: "stack", items: [["Live", { live: true }]] },
      ],
      tokens: [["snackbar.cornerRadius", 8], ["snackbar.paddingHorizontal", 16], ["snackbar.paddingVertical", 12], ["snackbar.screenMarginHorizontal", 16], ["snackbar.screenMarginBottom", 16], ["snackbar.autoDismissDuration", "4000 ms"], ["action auto-dismiss", "10000 ms"], ["snackbar.actionSpacing (iOS)", 12], ["motion (iOS)", "200 ms ease-in-out, move + fade"], ["type", "type.bodySecondary"]],
      source: { ios: "Components/Feedback/OpenCodeSnackbar.swift", android: "ui/components/feedback/OpenCodeSnackbar.kt" }, divergences: DV.snackbar, render: snackbar },

    { id: "file-tabs", align: "stretch",
      params: [sel("tabs", ["five", "two", "single"]), sel("selected", ["0", "1", "2", "3", "4"], "1"), bool("divider", true)],
      states: [["Open files", {}], ["Long name selected", { selected: "4" }], ["Two tabs", { tabs: "two", selected: "0" }], ["Single tab, no divider", { tabs: "single", divider: false }]],
      tokens: [["fileTabs.rowPadding", "6 / 8"], ["fileTabs.spacing", 4], ["fileTabs.titlePadding", "10 / 2"], ["fileTabs.titleMaxWidth", 180], ["fileTabs.closeTarget", 32], ["fileTabs.closeIcon", 14], ["fileTabs.radius", "radius.small (8)"], ["selected", "surface-raised-base, 1 border-weak-base"], ["divider", "1, border-weaker-base"], ["type", "type.button"]],
      spec: "## Intent\nHorizontally scrolling strip of open files for the tablet File Viewer.\n\n## Public API\n- `tabs`: id and file name.\n- `selectedTabId`, `showsDivider`, `onSelect`, `onClose`.\n\n## Behavior\nTap a tab to select it and × to close it. The selected tab scrolls into view. Closing the selected tab selects the last remaining tab. With no tabs, the component renders nothing.",
      source: { ios: "Components/Chrome/OpenCodeFileTabs.swift", android: "ui/components/chrome/OpenCodeFileTabs.kt" }, divergences: DV.fileTabs, render: fileTabs },

    { id: "page-indicator", align: "center",
      params: [sel("pages", ["1", "2", "3", "4", "5"], "3"), sel("current", ["0", "1", "2", "3", "4"])],
      states: [["1 of 3", {}], ["2 of 3", { current: "1" }], ["3 of 3", { current: "2" }], ["4 of 5", { pages: "5", current: "3" }]],
      sheet: [{ title: "Position", kind: "rows", items: [["1 of 3", {}], ["2 of 3", { current: "1" }], ["3 of 3", { current: "2" }], ["4 of 5", { pages: "5", current: "3" }], ["1 of 1", { pages: "1" }]] }],
      tokens: [["pageIndicator.dot", "7 × 7"], ["pageIndicator.current", "18 × 7"], ["pageIndicator.spacing", 6], ["current", "text-strong"], ["other", "text-weaker"], ["motion (iOS)", "200 ms ease-in-out"], ["motion (Android)", "default spring, width only"]],
      spec: "## Intent\nPager position dots. The current page is a wide capsule and the others are short dots.\n\n## Public API\n- `pageCount`: at least 1.\n- `currentPage`: zero-based.\n\n## Behavior\nNot interactive. Assistive technology reads “Page N of M”.",
      source: { ios: "Components/Chrome/OpenCodePageIndicator.swift", android: "ui/components/chrome/OpenCodePageIndicator.kt" }, divergences: DV.pageIndicator, render: pageIndicator },

    { id: "bottom-fade", doc: "bottom-fade", align: "stretch",
      params: [sel("height", ["72", "48", "120"]), bool("dock")],
      states: [["72 · gallery", {}], ["48", { height: "48" }], ["120", { height: "120" }], ["Under a dock", { dock: true, height: "120" }]],
      tokens: [["bottomFade.overhang", 16], ["stops", "background-base 0 → 0.7 → 0.7"], ["hit testing", "none"]],
      source: { ios: "Components/Chrome/OpenCodeBottomFade.swift", android: "ui/components/chrome/OpenCodeBottomFade.kt" }, render: bottomFade },
  ]);

  /* Behaviors. Registered on one component because the frame calls every mount after each render. */
  const once = (el, key) => { if (el.dataset[key]) return false; el.dataset[key] = "1"; return true; };
  const ENTER = "cubic-bezier(.4,0,.2,1)", EXIT = "cubic-bezier(.4,0,1,1)";

  const mountOverlay = (root, ctx) => {
    const ios = platformOf(ctx) === "ios", ms = ios ? 200 : 0;
    const scrim = root.querySelector(":scope > .c-ov-scrim"), layer = root.querySelector(":scope > .c-dg-center");
    const close = () => { root.classList.add("closing"); setTimeout(() => { root.classList.remove("closing"); root.classList.add("closed"); }, ms); };
    root.style.setProperty("--ov-ms", `${ms}ms`);
    scrim.addEventListener("click", close);
    layer.addEventListener("click", (e) => {
      if (e.target === layer) return close();
      const b = e.target.closest("button");
      if (b && !b.disabled) close();
    });
    root.querySelector(".c-ov-reopen button").addEventListener("click", () => root.classList.remove("closed"));
  };

  const mountSheet = (root, ctx) => {
    const ios = platformOf(ctx) === "ios";
    const card = root.querySelector(".c-bs-card"), body = card.querySelector(".c-bs-body"), header = card.querySelector(".c-bs-header"), scrim = root.querySelector(":scope > .c-ov-scrim");
    const kb = root.querySelector(":scope > .c-bs-kb");
    let low = 0, high = 0, extent = 0, atHigh = false, closed = false;
    const apply = () => { card.style.height = `${Math.min(Math.max(extent, low), high)}px`; card.style.transform = `translateY(${Math.max(0, low - extent)}px)`; };
    const measure = () => {
      const H = root.clientHeight, k = kb?.offsetHeight ?? 0, avail = Math.max(0, H - 8 - k);
      const half = ios ? avail * 0.5 : (H - k) * 0.5 - 8, full = Math.max(half, H - k - 16);
      card.style.height = "auto"; card.style.transform = "";
      body.style.flex = "none"; const natural = card.offsetHeight; body.style.flex = "";
      const mode = card.dataset.height;
      if (mode === "half") { low = half; high = full; }
      else if (mode === "fitAllContent") { low = high = Math.min(natural, full); }
      else if (natural <= 420 && natural <= full) { low = high = natural; }
      else { low = half; high = full; }
      if (card.dataset.keyboard === "expand" && k > 0) low = high = Math.max(low, full);
      extent = atHigh ? high : low; apply();
    };
    const settle = (target) => { card.classList.add("settling"); extent = target; atHigh = target > low + 1; apply(); setTimeout(() => card.classList.remove("settling"), 320); };
    const hide = () => {
      if (closed) return; closed = true;
      card.classList.remove("settling");
      card.style.transition = `transform 280ms ${EXIT}, opacity 280ms ${EXIT}`;
      card.style.transform = `translateY(${card.offsetHeight + 8}px)`; card.style.opacity = "0";
      root.classList.add("sheet-hiding");
      setTimeout(() => { root.classList.remove("sheet-hiding"); root.classList.add("closed"); card.style.transition = ""; }, 280);
    };
    const show = () => {
      closed = false; atHigh = false; root.classList.remove("closed"); measure();
      const rest = card.style.transform;
      card.style.transition = "none"; card.style.transform = `translateY(${card.offsetHeight + 8}px)`; card.style.opacity = "0";
      void card.offsetHeight;
      card.style.transition = `transform 320ms ${ENTER}, opacity 320ms ${ENTER}`;
      card.style.transform = rest; card.style.opacity = "1";
      setTimeout(() => { card.style.transition = ""; }, 320);
    };
    const release = (v) => {
      let target;
      if (v > 900) target = extent > low + 1 ? low : null;
      else if (v < -900) target = high;
      else if (extent < 0.6 * low) target = null;
      else target = extent < (low + high) / 2 ? low : high;
      if (target === null) hide(); else settle(target);
    };
    const drag = (el, fromBody) => {
      let id = null, y = 0, active = false, samples = [], moved = false;
      el.addEventListener("pointerdown", (e) => { if (e.button) return; id = e.pointerId; y = e.clientY; active = false; moved = false; samples = [[e.timeStamp, e.clientY]]; });
      el.addEventListener("pointermove", (e) => {
        if (e.pointerId !== id) return;
        const dy = e.clientY - y;
        if (!active) {
          if (Math.abs(dy) < 8) return;
          if (fromBody && !(body.scrollTop <= 0 && (dy > 0 || extent < high - 1))) { id = null; return; }
          active = true; moved = true; el.setPointerCapture(id); y = e.clientY; return;
        }
        y = e.clientY; extent = Math.min(Math.max(extent - dy, 0), Math.max(high, 0)); apply();
        samples.push([e.timeStamp, e.clientY]); if (samples.length > 5) samples.shift();
        e.preventDefault();
      });
      const end = (e) => {
        if (e.pointerId !== id) return; id = null;
        if (!active) return; active = false;
        const a = samples[0], b = samples[samples.length - 1], dt = b[0] - a[0];
        release(dt > 0 ? (b[1] - a[1]) / dt * 1000 : 0);
      };
      el.addEventListener("pointerup", end); el.addEventListener("pointercancel", end);
      el.addEventListener("click", (e) => { if (moved) { e.stopPropagation(); e.preventDefault(); moved = false; } }, true);
    };
    drag(header, false); drag(body, true);
    scrim.addEventListener("click", hide);
    card.querySelector(".c-bs-close button").addEventListener("click", hide);
    card.querySelectorAll(".c-bs-foot button, .c-bs-body .c-bs-entry:not([disabled])").forEach((b) => b.addEventListener("click", hide));
    root.querySelector(".c-ov-reopen button").addEventListener("click", show);
    measure();
  };

  const mountMenu = (root) => {
    const trigger = root.querySelector(".c-om-anchor button") ?? root.querySelector(".c-tb-actions .c-tb-ic");
    trigger?.addEventListener("click", (e) => { e.stopPropagation(); root.classList.toggle("open"); });
    root.querySelector(".c-om-panel").addEventListener("click", (e) => { const b = e.target.closest("button"); if (b && !b.disabled) root.classList.remove("open"); });
    root.addEventListener("click", (e) => { if (!e.target.closest(".c-om-panel") && !e.target.closest("button")) root.classList.remove("open"); });
  };

  const mountSnack = (root, ctx) => {
    const ios = platformOf(ctx) === "ios", host = root.querySelector(".c-snack-host");
    let timer = 0;
    const drop = () => { clearTimeout(timer); const c = host.firstElementChild; if (!c) return; c.classList.add("out"); setTimeout(() => c.remove(), 200); };
    root.querySelectorAll("[data-fire]").forEach((b) => b.addEventListener("click", () => {
      clearTimeout(timer); host.innerHTML = "";
      const m = SNACKS[b.dataset.fire];
      host.innerHTML = snackCard(m);
      const c = host.firstElementChild; c.classList.add("in");
      c.querySelector(".c-snack-action")?.addEventListener("click", (e) => { e.stopPropagation(); drop(); });
      if (ios) c.addEventListener("click", drop);
      timer = setTimeout(() => { if (root.isConnected) drop(); }, m.actionLabel ? 10000 : 4000);
    }));
  };

  const mountTabs = (root, ctx) => {
    const ios = platformOf(ctx) === "ios", initial = root.dataset.nvTabs.split("|");
    const row = root.querySelector(".c-ft-row"), again = root.querySelector(".c-ft-again");
    const reveal = (el) => { const s = root.querySelector(".c-ft-scroll"); if (el) s.scrollTo({ left: el.offsetLeft - 8, behavior: "smooth" }); };
    const paint = (list, selected) => {
      row.innerHTML = list.map((t) => fileTab(t, t === selected, ios)).join("");
      root.classList.toggle("empty", !list.length); again.style.display = list.length < initial.length ? "block" : "none";
      reveal(row.querySelector(".sel"));
    };
    let list = [...initial], selected = row.querySelector(".sel")?.dataset.tab ?? null;
    const first = row.querySelector(".sel"), strip = root.querySelector(".c-ft-scroll");
    if (first && first.offsetLeft + first.offsetWidth > strip.clientWidth) strip.scrollLeft = first.offsetLeft - 8;
    row.addEventListener("click", (e) => {
      const tab = e.target.closest(".c-ft-tab"); if (!tab) return;
      const t = tab.dataset.tab;
      if (e.target.closest(".c-ft-x")) { list = list.filter((x) => x !== t); if (selected === t) selected = list[list.length - 1] ?? null; } else selected = t;
      paint(list, selected);
    });
    again.querySelector("button").addEventListener("click", () => { list = [...initial]; selected = initial[Math.min(1, initial.length - 1)]; paint(list, selected); });
  };

  const mountSplit = (root, ctx) => {
    const ios = platformOf(ctx) === "ios", W = 760, H = 420;
    const pane = root.querySelector(".c-sp"), lead = pane.querySelector(".c-sp-lead"), inner = pane.querySelector(".c-sp-lead-in"), divider = pane.querySelector(".c-sp-divider"), handle = pane.querySelector(".c-sp-handle");
    const scale = Math.min(1, root.clientWidth / W);
    root.style.height = `${H * scale}px`; pane.style.transform = `scale(${scale})`;
    pane.style.setProperty("--sp-ease", ios ? "cubic-bezier(0,0,.58,1)" : "cubic-bezier(.4,0,.2,1)");
    let width = +root.dataset.width, collapsed = root.dataset.collapsed === "1";
    const paint = (w = collapsed ? 0 : width) => { lead.style.width = `${w}px`; inner.style.width = `${Math.max(280, collapsed ? width : Math.min(Math.max(w, 280), 480))}px`; pane.classList.toggle("collapsed", collapsed && w === 0); };
    const animate = () => { pane.classList.add("animating"); setTimeout(() => pane.classList.remove("animating"), 300); };
    const toggle = () => { collapsed = !collapsed; animate(); paint(); };
    paint();
    pane.querySelector(".c-sp-detail .c-tb-ic")?.addEventListener("click", toggle);
    handle.addEventListener("pointerdown", (e) => {
      e.preventDefault(); handle.setPointerCapture(e.pointerId);
      const x0 = e.clientX, start = width; let live = null;
      const move = (m) => {
        const dx = (m.clientX - x0) / scale;
        if (live === null && Math.abs(dx) < 4) return;
        live = Math.max(0, Math.min(480, start + dx)); pane.classList.add("dragging"); paint(live);
      };
      const up = () => {
        handle.removeEventListener("pointermove", move); handle.removeEventListener("pointerup", up); handle.removeEventListener("pointercancel", up);
        pane.classList.remove("dragging");
        if (live === null) return toggle();
        if (live < 220) collapsed = true; else width = Math.min(480, Math.max(280, live));
        animate(); paint();
      };
      handle.addEventListener("pointermove", move); handle.addEventListener("pointerup", up); handle.addEventListener("pointercancel", up);
    });
    void divider;
  };

  Kit.components["top-bar"].mount = (stage, ctx) => {
    stage.querySelectorAll('[data-nv-ov="dialog"], [data-nv-ov="modal"]').forEach((x) => { if (once(x, "nvBound")) mountOverlay(x, ctx); });
    stage.querySelectorAll('[data-nv-ov="sheet"]').forEach((x) => { if (once(x, "nvBound")) mountSheet(x, ctx); });
    stage.querySelectorAll("[data-nv-om]").forEach((x) => { if (once(x, "nvBound")) mountMenu(x); });
    stage.querySelectorAll("[data-nv-snack]").forEach((x) => { if (once(x, "nvBound")) mountSnack(x, ctx); });
    stage.querySelectorAll("[data-nv-tabs]").forEach((x) => { if (once(x, "nvBound")) mountTabs(x, ctx); });
    stage.querySelectorAll("[data-nv-split]").forEach((x) => { if (once(x, "nvBound")) mountSplit(x, ctx); });
  };
})();
