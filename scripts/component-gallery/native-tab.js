// Native tab: real iOS and Android captures of each state, from captures/<platform>/manifest.json.
// HTML renders in captures/html-<platform>/ (render-html-captures.mjs) power the swipe comparison.
(() => {
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const slug = (s) => String(s).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
  const OS = { ios: "iOS", android: "Android" };
  const html = { ios: null, android: null };
  const htmlReady = Promise.allSettled(["ios", "android"].map((os) => fetch(`captures/html-${os}/manifest.json`, { cache: "no-store" }).then((r) => (r.ok ? r.json() : null)).then((m) => { html[os] = m; })));
  const local = { os: null, ap: null, swipe: false, only: new Set(), forId: null };
  let ctx = null, body = null;

  const statesOf = (id) => (window.COMPONENTS[id]?.states ?? [["Default", {}]]).map(([name]) => ({ id: slug(name), name }));
  const native = (os, st, ap) => ctx.captures[os]?.captures?.find((x) => x.component === ctx.id && x.state === st && x.appearance === ap);
  const skipped = (os, st) => ctx.captures[os]?.skipped?.find((x) => x.component === ctx.id && x.state === st);
  const htmlCap = (os, st, ap) => html[os]?.captures?.find((x) => x.component === ctx.id && x.state === st && x.appearance === ap);
  const captured = () => ["ios", "android"].some((os) => ctx.captures[os]?.captures?.some((x) => x.component === ctx.id));
  const oses = () => (local.os === "both" ? ["ios", "android"] : [local.os]);

  const tile = (os, st, ap, open = true) => {
    const n = native(os, st, ap);
    if (n) return `<button class="nt-tile" ${open ? `data-open="${os}|${st}"` : 'tabindex="-1"'} title="Open ${OS[os]} capture large"><img src="captures/${os}/${n.file}" alt="${OS[os]} native capture, ${esc(st)}, ${ap}" loading="lazy"></button>`;
    const s = skipped(os, st);
    return `<div class="nt-miss">${s ? `Not on ${OS[os]}: ${esc(s.reason)}` : "Not captured yet"}</div>`;
  };
  const swipe = (os, st, ap) => {
    const n = native(os, st, ap), h = htmlCap(os, st, ap);
    if (!n || !h) return tile(os, st, ap, false);
    return `<div class="nt-swipe" data-swipe><img src="captures/html-${os}/${h.file}" alt="HTML replica"><img class="top" src="captures/${os}/${n.file}" alt="Native capture" style="clip-path:inset(0 0 0 50%)"><i class="bar" style="left:50%"></i><span class="l">HTML</span><span class="r">Native</span><input type="range" min="0" max="100" value="50" aria-label="Swipe between HTML and native"></div>`;
  };
  const wireSwipes = (root) => root.querySelectorAll("[data-swipe]").forEach((s) => {
    const r = s.querySelector("input"), top = s.querySelector(".top"), bar = s.querySelector(".bar");
    r.oninput = () => { top.style.clipPath = `inset(0 0 0 ${r.value}%)`; bar.style.left = r.value + "%"; };
  });

  const seg = (key, opts) => `<div class="nt-seg" data-k="${key}">${opts.map(([v, l]) => `<button data-v="${v}" class="${local[key] === v ? "on" : ""}">${l}</button>`).join("")}</div>`;
  const meta = () => `<div class="nt-meta">${oses().map((os) => {
    const m = ctx.captures[os];
    if (!m) return `<span><b>${OS[os]}</b> no captures yet</span>`;
    const when = m.generatedAt ? new Date(m.generatedAt).toLocaleDateString(undefined, { day: "numeric", month: "short" }) : "";
    return `<span><b>${OS[os]}</b> ${esc(m.device.replace(/ emulator.*$/, ""))} · ${esc(m.os.replace(/ \(API \d+\)/, ""))} · <code>${esc((m.gitSha || "").slice(0, 7))}</code>${when ? ` · ${when}` : ""}</span>`;
  }).join("")}</div>`;

  function list() {
    if (local.forId !== ctx.id) { local.only.clear(); local.forId = ctx.id; }
    const all = statesOf(ctx.id), shown = local.only.size ? all.filter((s) => local.only.has(s.id)) : all;
    const haveHtml = oses().some((os) => html[os]?.captures?.some((x) => x.component === ctx.id));
    return `<div class="nt-chips"><button data-only="*" class="${local.only.size ? "" : "on"}">All · ${all.length}</button>${all.map((s) => `<button data-only="${s.id}" class="${local.only.has(s.id) ? "on" : ""}">${esc(s.name)}</button>`).join("")}</div>
      <div class="nt-row"><label class="nt-check${haveHtml ? "" : " off"}" title="${haveHtml ? "" : "Run npm run gallery:html-captures first"}"><input type="checkbox" data-swipe-toggle ${local.swipe && haveHtml ? "checked" : ""} ${haveHtml ? "" : "disabled"}> Swipe against HTML</label><span class="nt-count">${shown.length} of ${all.length} states</span></div>
      ${shown.map((s) => `<section class="nt-state"><h4>${esc(s.name)}<small>${s.id}</small></h4>${oses().map((os) => `<div class="nt-lab">${OS[os]}</div>${local.swipe && haveHtml ? swipe(os, s.id, local.ap) : tile(os, s.id, local.ap)}`).join("")}</section>`).join("")}`;
  }

  function lightbox(os, st) {
    const name = statesOf(ctx.id).find((s) => s.id === st)?.name ?? st;
    let box = ctx.overlayHost.querySelector(".nt-lightbox");
    if (!box) { box = document.createElement("div"); box.className = "nt-lightbox"; ctx.overlayHost.append(box); }
    box.innerHTML = `<div class="nt-lb" role="dialog" aria-label="${esc(name)}, ${OS[os]}"><header><b>${esc(name)}</b><span>${OS[os]} · native capture</span><button class="x" aria-label="Close">×</button></header>
      <div class="nt-lb-ap">${["light", "dark"].map((ap) => `<div><div class="nt-lab">${ap}</div>${swipe(os, st, ap)}</div>`).join("")}</div>
      <p>Drag across a capture to swipe between the HTML replica and the native render.</p></div>`;
    box.hidden = false; wireSwipes(box);
    const close = () => { box.hidden = true; removeEventListener("keydown", onKey); };
    const onKey = (e) => { if (e.key === "Escape") close(); };
    addEventListener("keydown", onKey);
    box.querySelector(".x").onclick = close; box.onclick = (e) => { if (e.target === box) close(); };
  }

  function render(b, c) {
    ctx = c; body = b;
    local.os ??= c.platform; local.ap ??= c.mode === "dark" ? "dark" : "light";
    if (!c.ready) { b.innerHTML = '<div class="note">Loading captures…</div>'; return; }
    if (!captured()) {
      b.innerHTML = `<div class="nt-empty"><b>No native captures yet</b><p>Render this component on a simulator and an emulator:</p><code>scripts/component-gallery/capture-ios.sh ${esc(c.id)}</code><code>scripts/component-gallery/capture-android.sh ${esc(c.id)}</code><p>Then <code>npm run gallery:html-captures</code> for the swipe comparison.</p></div>`;
      return;
    }
    b.innerHTML = `<div class="nt-tools">${seg("os", [["both", "Both"], ["ios", "iOS"], ["android", "Android"]])}${seg("ap", [["light", "Light"], ["dark", "Dark"]])}</div>${meta()}${list()}`;
    wireSwipes(b);
    const again = () => { const keep = body.scrollTop; render(body, ctx); body.scrollTop = keep; };
    b.querySelectorAll(".nt-seg").forEach((sg) => { sg.onclick = (e) => { const x = e.target.closest("button"); if (x) { local[sg.dataset.k] = x.dataset.v; again(); } }; });
    b.querySelectorAll("[data-open]").forEach((t) => { t.onclick = () => { const [os, st] = t.dataset.open.split("|"); lightbox(os, st); }; });
    b.querySelectorAll("[data-only]").forEach((ch) => { ch.onclick = () => { const v = ch.dataset.only; if (v === "*") local.only.clear(); else if (local.only.has(v)) local.only.delete(v); else local.only.add(v); again(); }; });
    const sw = b.querySelector("[data-swipe-toggle]"); if (sw) sw.onchange = () => { local.swipe = sw.checked; again(); };
  }
  htmlReady.then(() => { if (ctx && body?.isConnected && body.querySelector(".nt-chips")) render(body, ctx); });

  window.NativeTab = { render };
})();
