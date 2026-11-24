// Navigation, device frames, controls, and the spec panel.
(() => {
  const { themes, docs, entries, duo } = window.DS, COMPONENTS = window.COMPONENTS;
  const $ = (s) => document.querySelector(s);
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  // Published logical sizes, except iPhone Duo, which comes from docs/devices/iphone-duo/geometry.json.
  const duoDevice = (id, name, pose) => {
    const p = duo.poses[pose], d = duo.displays[p.display], r = d.cornerRadii;
    return { id, name, os: "ios", w: p.window[0], h: p.window[1], safe: { t: p.safeArea.top, r: p.safeArea.right, b: p.safeArea.bottom, l: p.safeArea.left }, radii: pose === "closed" ? [r.topLeft, r.topRight, r.bottomRight, r.bottomLeft] : [r.topLeft, r.topLeft, r.topLeft, r.topLeft], chrome: "duo", pose: p };
  };
  const DEVICES = [
    { id: "iphone-17", name: "iPhone 17", os: "ios", w: 402, h: 874, safe: { t: 62, r: 0, b: 34, l: 0 }, radii: 62, chrome: "island" },
    { id: "iphone-17-pm", name: "iPhone 17 Pro Max", os: "ios", w: 440, h: 956, safe: { t: 62, r: 0, b: 34, l: 0 }, radii: 69, chrome: "island" },
    { id: "iphone-se", name: "iPhone SE", os: "ios", w: 375, h: 667, safe: { t: 20, r: 0, b: 0, l: 0 }, radii: 0, chrome: "classic" },
    duoDevice("duo-closed", "iPhone Duo, closed", "closed"),
    duoDevice("duo-open", "iPhone Duo, open", "open"),
    { id: "pixel-10", name: "Pixel 10", os: "android", w: 412, h: 915, safe: { t: 44, r: 0, b: 24, l: 0 }, radii: 48, chrome: "punch" },
    { id: "pixel-10-pro-xl", name: "Pixel 10 Pro XL", os: "android", w: 448, h: 998, safe: { t: 48, r: 0, b: 24, l: 0 }, radii: 52, chrome: "punch" },
    { id: "galaxy-s25", name: "Galaxy S25", os: "android", w: 360, h: 780, safe: { t: 36, r: 0, b: 24, l: 0 }, radii: 40, chrome: "punch" },
    { id: "compact-android", name: "Compact, 3-button", os: "android", w: 360, h: 640, safe: { t: 24, r: 0, b: 48, l: 0 }, radii: 16, chrome: "buttons" },
  ];
  const device = (id) => DEVICES.find((d) => d.id === id);
  const CATS = ["Foundations", "Controls", "Lists", "Navigation", "Chat", "Session", "Diffs"];
  const docFor = (e) => COMPONENTS[e.id]?.doc ?? (docs[e.id] ? e.id : null);

  const state = { id: "button", platform: "both", ios: "iphone-17", android: "pixel-10", theme: "porcelain", mode: "light", scale: 1, view: "all", tab: "props", inspect: false, only: false, q: "", props: {}, insp: null };
  try {
    const h = new URLSearchParams(location.hash.slice(1));
    if (h.get("c") && entries.some((e) => e.id === h.get("c"))) state.id = h.get("c");
    for (const k of ["platform", "ios", "android", "theme", "mode", "view", "tab"]) if (h.get(k)) state[k] = h.get(k);
    if (state.view !== "single") state.view = "all";
  } catch { /* default state */ }
  const syncHash = () => { const h = new URLSearchParams({ c: state.id, platform: state.platform, ios: state.ios, android: state.android, theme: state.theme, mode: state.mode, view: state.view, tab: state.tab }); history.replaceState(null, "", "#" + h); };

  /* ---------- Nav ---------- */
  const built = entries.filter((e) => COMPONENTS[e.id]).length;
  $("#cover-bar").style.width = (built / entries.length) * 100 + "%";
  $("#cover-text").textContent = `${built} of ${entries.length} replicated`;
  let visible = [], kb = -1;
  function renderNav() {
    const q = state.q.trim().toLowerCase();
    visible = [];
    let html = "";
    for (const cat of CATS) {
      const rows = entries.filter((e) => e.category === cat && (!state.only || COMPONENTS[e.id]) && (!q || (e.title + " " + e.description).toLowerCase().includes(q))).sort((a, b) => (COMPONENTS[b.id] ? 1 : 0) - (COMPONENTS[a.id] ? 1 : 0) || a.title.localeCompare(b.title));
      if (!rows.length) continue;
      html += `<div class="cat"><span>${cat}</span><span>${rows.length}</span></div>`;
      for (const e of rows) { const i = visible.push(e) - 1; html += `<button class="item ${COMPONENTS[e.id] ? "" : "todo"} ${e.id === state.id ? "on" : ""} ${i === kb ? "kb" : ""}" data-id="${e.id}" title="${esc(e.description)}"><i></i>${esc(e.title)}</button>`; }
    }
    $("#list").innerHTML = html || '<div class="empty">No components match</div>';
    $("#list .kb")?.scrollIntoView({ block: "nearest" });
  }
  $("#list").addEventListener("click", (e) => { const b = e.target.closest(".item"); if (b) select(b.dataset.id); });
  $("#q").addEventListener("input", (e) => { state.q = e.target.value; kb = state.q ? 0 : -1; renderNav(); });
  $("#q").addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); kb = Math.max(0, Math.min(visible.length - 1, kb + (e.key === "ArrowDown" ? 1 : -1))); renderNav(); }
    else if (e.key === "Enter" && visible[Math.max(kb, 0)]) select(visible[Math.max(kb, 0)].id);
    else if (e.key === "Escape") { state.q = ""; e.target.value = ""; kb = -1; renderNav(); e.target.blur(); }
  });
  $("#only").addEventListener("change", (e) => { state.only = e.target.checked; renderNav(); });
  addEventListener("keydown", (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); $("#q").focus(); $("#q").select(); return; }
    if (/INPUT|SELECT|TEXTAREA/.test(document.activeElement.tagName)) return;
    if (e.key === "/") { e.preventDefault(); $("#q").focus(); }
    else if (e.key.toLowerCase() === "i" && !e.metaKey) toggleInspect();
    else if (e.key.toLowerCase() === "d" && !e.metaKey) { state.mode = state.mode === "dark" ? "light" : state.mode === "light" ? "dark" : "light"; renderBar(); update(); }
    else if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); const i = visible.findIndex((v) => v.id === state.id), n = visible[i + (e.key === "ArrowDown" ? 1 : -1)]; if (n) select(n.id); }
  });
  function select(id) { state.id = id; state.props = {}; state.insp = null; state.view = "all"; kb = -1; renderNav(); $(`#list [data-id="${id}"]`)?.scrollIntoView({ block: "nearest" }); renderBar(); renderStage(); renderPanel(); syncHash(); }

  /* ---------- Toolbar ---------- */
  const seg = (el, opts, cur, fn) => { el.innerHTML = opts.map(([v, l]) => `<button data-v="${v}" class="${v === cur ? "on" : ""}">${l}</button>`).join(""); el.onclick = (e) => { const b = e.target.closest("button"); if (b) fn(b.dataset.v); }; };
  const fill = (el, opts, cur) => { el.innerHTML = opts.map(([v, l]) => `<option value="${v}" ${v === cur ? "selected" : ""}>${esc(l)}</option>`).join(""); };
  function renderBar() {
    seg($("#platform"), [["ios", "iOS"], ["both", "Both"], ["android", "Android"]], state.platform, (v) => { state.platform = v; renderBar(); renderStage(); if (state.tab === "native") renderPanel(); syncHash(); });
    seg($("#mode"), [["light", "Light"], ["dark", "Dark"], ["split", "Split"]], state.mode, (v) => { state.mode = v; renderBar(); renderStage(); if (state.tab === "native") renderPanel(); syncHash(); });
    $("#ios-pick").style.display = state.platform === "android" ? "none" : "";
    $("#android-pick").style.display = state.platform === "ios" ? "none" : "";
  }
  fill($("#ios-device"), DEVICES.filter((d) => d.os === "ios").map((d) => [d.id, d.name]), state.ios);
  fill($("#android-device"), DEVICES.filter((d) => d.os === "android").map((d) => [d.id, d.name]), state.android);
  fill($("#theme"), themes.map((t) => [t.id, t.name]), state.theme);
  $("#ios-device").onchange = (e) => { state.ios = e.target.value; renderStage(); syncHash(); };
  $("#android-device").onchange = (e) => { state.android = e.target.value; renderStage(); syncHash(); };
  $("#theme").onchange = (e) => { state.theme = e.target.value; update(); syncHash(); };
  $("#scale").onchange = (e) => { state.scale = +e.target.value; update(); };
  function toggleInspect() { state.inspect = !state.inspect; $("#inspect").classList.toggle("on", state.inspect); if (!state.inspect) state.insp = null; else state.tab = "props"; update(); renderPanel(); }
  $("#inspect").onclick = toggleInspect;

  /* ---------- Phone frames ---------- */
  let frames = [];
  const SIGNAL = '<svg viewBox="0 0 18 12"><rect x="0" y="8" width="3" height="4" rx=".8"/><rect x="5" y="5.5" width="3" height="6.5" rx=".8"/><rect x="10" y="3" width="3" height="9" rx=".8"/><rect x="15" y="0" width="3" height="12" rx=".8"/></svg>';
  const BATT = '<svg viewBox="0 0 26 12"><rect x=".5" y=".5" width="22" height="11" rx="3" fill="none" stroke="currentColor" opacity=".45"/><rect x="2" y="2" width="17" height="8" rx="1.6"/><rect x="24" y="4" width="1.6" height="4" rx=".8" opacity=".5"/></svg>';
  const WIFI = '<svg viewBox="0 0 16 12"><path d="M8 12 5.6 9.3a3.4 3.4 0 0 1 4.8 0zM3.8 7.4a6 6 0 0 1 8.4 0l1.7-1.9a8.6 8.6 0 0 0-11.8 0zM0 3.6l1.7 1.9"/><path d="M0 3.6a11.6 11.6 0 0 1 16 0l-1.7 1.9a9 9 0 0 0-12.6 0z"/></svg>';
  function sysChrome(d) {
    const fg = "var(--fg)";
    if (d.chrome === "island") return `<div class="sys top" style="top:0;height:${d.safe.t}px;padding:8px 34px 0;font-size:16px;color:${fg}"><span>9:41</span><span class="r">${SIGNAL}${WIFI}${BATT}</span></div><div class="island" style="top:11px;left:50%;width:124px;height:36px;margin-left:-62px;border-radius:18px"></div><div class="homebar" style="bottom:8px;width:144px;height:5px;color:${fg}"></div>`;
    if (d.chrome === "classic") return `<div class="sys top" style="top:0;height:20px;padding:0 8px;font-size:12px;color:${fg}"><span class="r">${SIGNAL}${WIFI}</span><span>9:41</span><span class="r">${BATT}</span></div>`;
    if (d.chrome === "duo") {
      const p = d.pose, s = p.statusRegion, hole = p.cameraHole ?? p.underDisplayCamera;
      return `${s ? `<div class="rail" style="left:${s.x}px;top:${(hole?.y ?? 20) + (hole?.height ?? 37) + 12}px;width:${s.width}px;color:${fg}"><span>9:41</span><span style="height:11px">${WIFI}</span><span style="height:11px">${BATT}</span></div>` : ""}${p.cameraHole ? `<div class="punch" style="left:${hole.x}px;top:${hole.y}px;width:${hole.width}px;height:${hole.height}px;border-radius:50%"></div>` : ""}${p.fold ? `<div class="fold" style="left:${p.fold.x}px;top:0;width:${p.fold.width}px;height:100%"></div>` : ""}<div class="homebar" style="bottom:8px;left:${(d.w - d.safe.r) / 2}px;width:144px;height:5px;color:${fg}"></div>`;
    }
    const buttons = d.chrome === "buttons";
    return `<div class="sys top" style="top:0;height:${d.safe.t}px;padding:0 ${buttons ? 12 : 26}px;font-size:${buttons ? 12 : 14}px;font-weight:500;color:${fg}"><span>9:41</span><span class="r">${WIFI}${SIGNAL}${BATT}</span></div>${buttons ? "" : `<div class="punch" style="top:${Math.round(d.safe.t / 2) - 8}px;left:50%;width:16px;height:16px;margin-left:-8px;border-radius:50%"></div>`}${buttons ? `<div class="sys" style="bottom:0;height:48px;padding:0 64px;color:${fg}"><span style="width:0;height:0;border:7px solid transparent;border-right:11px solid currentColor;margin-left:-11px"></span><span style="width:14px;height:14px;border-radius:50%;background:currentColor"></span><span style="width:13px;height:13px;background:currentColor;border-radius:2px"></span></div>` : `<div class="homebar" style="bottom:9px;width:108px;height:4px;color:${fg}"></div>`}`;
  }
  const radiusCss = (r) => Array.isArray(r) ? r.map((x) => x + "px").join(" ") : r + "px";
  const tokensFor = (dark, os = "android") => { const t = themes.find((x) => x.id === state.theme), m = dark ? "dark" : "light"; return os === "ios" ? t.ios[m] : t[m]; };

  const variantCount = (c) => c.sheet
    ? c.sheet.reduce((a, s) => a + (s.kind === "matrix" ? s.rows.length * s.cols.length : s.items.length), 0)
    : c.states?.length ?? 0;
  // Native captures from the simulator and emulator, written by the capture scripts into captures/<platform>/.
  const captures = { ios: null, android: null };
  let capturesReady = false;
  Promise.allSettled(["ios", "android"].map((os) => fetch(`captures/${os}/manifest.json`, { cache: "no-store" }).then((r) => (r.ok ? r.json() : null)).then((m) => { captures[os] = m; }))).then(() => { capturesReady = true; if (state.tab === "native") renderPanel(); });
  const viewOptions = (c) => [["all", `All states · ${variantCount(c)}`], ["single", "Playground"]];
  function setView(v) { state.view = v; seg($("#view"), viewOptions(COMPONENTS[state.id]), v, setView); renderPanel(); update(); syncHash(); }
  function plan() {
    const native = entries.find((x) => x.id === state.id)?.platforms ?? ["ios", "android"];
    const wanted = state.platform === "both" ? ["ios", "android"] : [state.platform];
    const os = wanted.filter((o) => native.includes(o)).length ? wanted.filter((o) => native.includes(o)) : native;
    const modes = state.mode === "split" ? ["light", "dark"] : [state.mode];
    return os.flatMap((o) => modes.map((m) => ({ os: o, mode: m, d: device(state[o]) })));
  }
  function renderStage() {
    const e = entries.find((x) => x.id === state.id);
    const c = COMPONENTS[state.id];
    const n = c ? variantCount(c) : 0;
    $("#title").innerHTML = `<h1>${esc(e.title)}</h1>${e.platforms?.length === 1 ? `<span class="only">${e.platforms[0] === "ios" ? "iOS" : "Android"} only</span>` : ""}<p>${esc(e.description)}</p>${n ? '<div class="seg" id="view"></div>' : ""}`;
    if (n) seg($("#view"), viewOptions(c), state.view, setView);
    const st = $("#stage");
    frames = [];
    if (!c) {
      st.innerHTML = `<div class="todo-card"><h2>Not replicated yet</h2><p>${esc(e.title)} exists in the native galleries and has no HTML version.</p><small>${docFor(e) ? "Its written spec is in the panel." : "It has no written spec doc either."}</small></div>`;
      return;
    }
    st.innerHTML = plan().map((f, i) => {
      const d = f.d, dark = f.mode === "dark", t = tokensFor(dark);
      return `<div class="slot" data-i="${i}"><div class="cap"><b>${esc(d.name)}</b> · ${d.w}×${d.h} · ${f.mode} · <span class="z"></span></div><div class="scaler"><div class="device" style="width:${d.w + 20}px;height:${d.h + 20}px;border-radius:${Array.isArray(d.radii) ? d.radii.map((x) => x + 10 + "px").join(" ") : (d.radii ? d.radii + 10 : 14) + "px"}"><div class="vp" style="left:10px;top:10px;width:${d.w}px;height:${d.h}px;border-radius:${radiusCss(d.radii)};--fg:${t["text-strong"]};color:${t["text-strong"]}"><iframe src="frame.html?f=${i}" title="${esc(e.title)} on ${esc(d.name)}"></iframe>${sysChrome(d)}</div></div></div></div>`;
    }).join("");
    frames = plan().map((f, i) => ({ ...f, el: st.querySelector(`[data-i="${i}"] iframe`), ready: false }));
    fit();
  }
  function fit() {
    const st = $("#stage"); if (!frames.length) return;
    const cs = getComputedStyle(st), W = st.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight), H = st.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom) - 24;
    const totalW = frames.reduce((a, f) => a + f.d.w + 20, 0), gaps = 36 * (frames.length - 1), maxH = Math.max(...frames.map((f) => f.d.h + 20));
    const k = Math.min(1, (W - gaps) / totalW, H / maxH);
    st.querySelectorAll(".slot").forEach((s, i) => {
      const d = frames[i].d, w = (d.w + 20) * k, h = (d.h + 20) * k, sc = s.querySelector(".scaler");
      sc.style.width = w + "px"; sc.style.height = h + "px"; s.querySelector(".device").style.transform = `scale(${k})`; s.querySelector(".z").textContent = Math.round(k * 100) + "%";
    });
  }
  new ResizeObserver(fit).observe($("#stage"));
  const send = (f, i) => {
    if (!f.ready) return;
    const dark = f.mode === "dark";
    f.el.contentWindow.postMessage({ type: "config", config: { frame: i, id: state.id, platform: f.os, dark, tokens: tokensFor(dark, f.os), safe: f.d.safe, textScale: state.scale, view: state.view, props: state.props, inspect: state.inspect } }, "*");
    const vp = f.el.parentElement, t = tokensFor(dark); vp.style.setProperty("--fg", t["text-strong"]); vp.style.color = t["text-strong"];
  };
  const update = () => frames.forEach(send);

  addEventListener("message", (e) => {
    const m = e.data;
    if (m?.type === "ready") { const f = frames.find((x) => x.el.contentWindow === e.source); if (f) { f.ready = true; send(f, frames.indexOf(f)); } }
    if (m?.type === "scroll") frames.forEach((f, i) => { if (i !== m.frame && f.ready) f.el.contentWindow.postMessage({ type: "scrollTo", ratio: m.ratio }, "*"); });
    if (m?.type === "inspect") { state.insp = { ...m.info, on: frames[m.frame] ? `${frames[m.frame].os === "ios" ? "iOS" : "Android"}` : "" }; if (state.tab === "props") renderInspector(); }
  });

  /* ---------- Panel ---------- */
  const inline = (s) => esc(s).replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>").replace(/\[([^\]]+)\]\([^)]+\)/g, "$1");
  function md(src) {
    const out = []; let para = [], depth = 0, table = null;
    const flushP = () => { if (para.length) out.push(`<p>${inline(para.join(" "))}</p>`); para = []; };
    const closeL = (to = 0) => { while (depth > to) { out.push("</li></ul>"); depth--; } };
    const flushT = () => { if (table) out.push(`<table>${table.map((r) => `<tr>${r.map((c) => `<td style="text-align:left;font-family:inherit">${inline(c)}</td>`).join("")}</tr>`).join("")}</table>`); table = null; };
    for (const raw of src.split("\n")) {
      const line = raw.trimEnd();
      let m;
      if (/^\|/.test(line.trim())) { flushP(); closeL(); if (/^\|[\s:|-]+\|$/.test(line.trim())) continue; (table ??= []).push(line.trim().slice(1, -1).split("|").map((x) => x.trim())); continue; }
      flushT();
      if ((m = line.match(/^(#{2,4})\s+(.*)/))) { flushP(); closeL(); out.push(`<h${Math.min(m[1].length, 3)}>${inline(m[2])}</h${Math.min(m[1].length, 3)}>`); }
      else if ((m = line.match(/^(\s*)[-*]\s+(.*)/))) {
        flushP(); const lvl = Math.floor(m[1].length / 2) + 1;
        if (lvl > depth) { while (depth < lvl) { out.push("<ul><li>"); depth++; } } else { closeL(lvl); out.push("</li><li>"); }
        out.push(inline(m[2]));
      } else if (!line.trim()) { flushP(); closeL(); }
      else if (depth) out.push(" " + inline(line.trim()));
      else if (!line.startsWith("# ")) para.push(line.trim());
    }
    flushP(); closeL(); flushT();
    return out.join("");
  }
  const section = (src, names) => { const parts = src.split(/^(?=## )/m); return parts.filter((p) => names.some((n) => p.toLowerCase().startsWith("## " + n))).join("\n"); };

  function renderInspector() {
    const el = $("#insp"); if (!el) return;
    const i = state.insp;
    el.innerHTML = i ? `<div class="insp"><div class="h">Inspecting .${esc(i.element)} on ${esc(i.on)}</div><table>${[["size", i.size], ["padding", i.padding], ["radius", i.radius], ["gap", i.gap], ["font", i.font], ["color", i.color], ["fill", i.fill]].map(([k, v]) => `<tr><td>${k}</td><td>${esc(v)}</td></tr>`).join("")}</table></div>` : state.inspect ? '<div class="insp"><div class="h">Inspect is on</div>Hover anything in a phone.</div>' : "";
  }
  function renderPanel() {
    const c = COMPONENTS[state.id], e = entries.find((x) => x.id === state.id), dk = docFor(e), doc = dk ? docs[dk] : null;
    const tabs = [c && ["props", "Props"], ["spec", "Spec"], c?.tokens && ["tokens", "Tokens"], ["parity", "Parity"], c && ["native", "Native"]].filter(Boolean);
    if (!tabs.some(([v]) => v === state.tab)) state.tab = tabs[0][0];
    $("#tabs").innerHTML = tabs.map(([v, l]) => `<button data-v="${v}" class="${v === state.tab ? "on" : ""}">${l}</button>`).join("");
    const b = $("#panel-body");
    if (state.tab === "props") {
      const val = (p) => state.props[p.key] ?? p.default;
      b.innerHTML = `<div id="insp"></div>${c.states ? `<div class="h">Open a state in Playground</div><div class="chips">${c.states.map(([n], i) => `<button data-state="${i}">${esc(n)}</button>`).join("")}</div>` : ""}<div class="h">Parameters</div>${variantCount(c) && state.view === "all" ? '<div class="hint">Text edits update every state. Other controls open Playground.</div>' : ""}${c.params.map((p) => `<label class="ctl"><span title="${p.label}">${p.label}</span>${p.type === "select" ? `<select data-k="${p.key}">${p.options.map((o) => `<option ${o === val(p) ? "selected" : ""}>${o}</option>`).join("")}</select>` : p.type === "bool" ? `<input type="checkbox" data-k="${p.key}" ${val(p) ? "checked" : ""}>` : `<input type="text" data-k="${p.key}" value="${esc(val(p))}">`}</label>`).join("")}<button class="reset">Reset</button>`;
      b.querySelectorAll("[data-k]").forEach((x) => { x.oninput = () => { state.props[x.dataset.k] = x.type === "checkbox" ? x.checked : x.value; if (x.type !== "text" && state.view === "all" && variantCount(c)) setView("single"); else update(); }; });
      b.querySelector(".reset").onclick = () => { state.props = {}; if (variantCount(c)) setView("all"); else { renderPanel(); update(); } };
      b.querySelectorAll("[data-state]").forEach((x) => { x.onclick = () => { state.props = { ...c.states[+x.dataset.state][1] }; setView("single"); }; });
      renderInspector();
    } else if (state.tab === "spec") {
      b.innerHTML = doc ? `<div class="doc">${md(section(doc, ["intent", "public api", "behavior", "token usage"]))}</div>${c?.spec ? `<div class="doc" style="margin-top:14px">${md(c.spec)}</div>` : ""}` : c?.spec ? `<div class="note" style="margin-bottom:12px">No Markdown spec. This is derived from the native code.</div><div class="doc">${md(c.spec)}</div>` : `<div class="note">No written spec for ${esc(e.title)}. Native code is the source of truth.</div>`;
    } else if (state.tab === "native") {
      NativeTab.render(b, { id: state.id, platform: state.platform, mode: state.mode, captures, ready: capturesReady, overlayHost: $("#main") });
    } else if (state.tab === "tokens") {
      b.innerHTML = `<table>${c.tokens.map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join("")}</table>`;
    } else {
      const s = doc ? section(doc, ["platform notes", "parity"]) : "";
      const divs = window.DS.divergences?.[state.id] ?? [];
      const found = divs.length ? `<div class="h">Divergences found <span style="font-weight:400">(local, unverified)</span></div>${divs.map((d) => `<div class="div ${esc(d.severity)}"><b>${esc(d.severity)}</b> ${inline(d.title)}</div>`).join("")}` : "";
      const src = c?.source ? `<div class="h">Native source</div><table>${Object.entries(c.source).map(([k, v]) => `<tr><td>${esc(k)}</td><td style="text-align:left;word-break:break-all">${esc(v)}</td></tr>`).join("")}</table>` : "";
      b.innerHTML = found + src + (s ? `<div class="doc" style="margin-top:14px">${md(s)}</div>` : found || src ? "" : '<div class="note">No platform notes recorded for this component.</div>');
    }
  }
  $("#tabs").addEventListener("click", (e) => { const t = e.target.closest("button"); if (t) { state.tab = t.dataset.v; renderPanel(); syncHash(); } });

  renderNav(); renderBar(); renderStage(); renderPanel();
  $(`#list [data-id="${state.id}"]`)?.scrollIntoView({ block: "nearest" });
})();
