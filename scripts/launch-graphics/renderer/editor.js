// Editor panel: reads and writes the spec. Layout edits go to spec.formats[format]; content (screens, copy) to the spec top level.
import { DEVICES, DEVICE_IDS, shotUrl } from "./devices.js";
import { computeLayout } from "./layouts.js";
import { MOMENTS, MOMENT_COPY_KEYS, SCREENS } from "./moments.js";
import { COMPOSITIONS, FORMATS, SAFE_AREAS, normalize, parseFormat } from "./spec.js";

const DRAFT_KEY = "launch-graphics:draft";
const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
const EYE = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8 12.1 12.5 8 12.5 1.5 8 1.5 8z"/><circle cx="8" cy="8" r="2"/></svg>';
const EYE_OFF = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4"><path d="M2 2l12 12M6.6 3.7A6.6 6.6 0 0 1 8 3.5c4.1 0 6.5 4.5 6.5 4.5a11 11 0 0 1-1.9 2.4M10.3 11.9a6 6 0 0 1-2.3.6C3.9 12.5 1.5 8 1.5 8a11.4 11.4 0 0 1 2.6-3"/></svg>';
const LAYOUT_DEVICE_KEYS = ["visible", "anchor", "x", "y", "scale", "tilt", "focus", "focusAt"];

const PANEL = `
  <div class="ph"><h2>Launch graphics</h2></div>
  <p class="pitch">Big devices, cropped by the edges, so the real UI is the hero.</p>
  <details class="sec" open><summary>Format <span class="val" id="fmtVal"></span></summary><div class="body">
    <div id="fmtPicker"></div>
    <div class="sub-lab">Mode</div><div class="seg" id="segMode"></div>
    <div class="sub-lab">Texture</div><div class="seg" id="segGrain"></div>
    <div id="safeRow"><div class="sub-lab">Safe area <span style="text-transform:none;letter-spacing:0">(editor only)</span></div><div class="seg small" id="segSafe"></div></div>
  </div></details>
  <details class="sec" open><summary>Composition <span class="val" id="compVal"></span></summary><div class="body">
    <div class="hint lead">Layout: devices, crop, positions, text block.</div>
    <div class="cards" id="cards"></div>
    <div id="cropRow"><div class="sub-lab">Crop shows</div><div class="seg small" id="segCrop"></div></div>
    <div class="note" id="notes" hidden></div>
  </div></details>
  <details class="sec" open><summary>Moment</summary><div class="body">
    <div class="hint lead">What's on screen: screenshots and copy. Never moves the layout.</div>
    <div class="seg small" id="segMoment"></div>
  </div></details>
  <details class="sec" open><summary>Devices <span class="val">click to select</span></summary><div class="body">
    <div class="devlist" id="devlist"></div><div class="devedit" id="devedit"></div>
  </div></details>
  <details class="sec"><summary>Text block <span class="val" id="textVal"></span></summary><div class="body">
    <div class="sub-lab">Position</div><div class="seg small" id="segTpos"></div>
    <div class="sub-lab">Alignment</div><div class="seg small" id="segTalign"></div>
    <div id="twRow"></div>
    <div class="hint">Click any text on the canvas to edit it.</div>
  </div></details>
  <details class="sec" open><summary>Presets <span class="val" id="presetVal"></span></summary><div class="body">
    <div class="line"><select class="inp" id="presetSel"></select><button class="btn sm" id="bOpen">Open</button></div>
    <div class="line" style="margin-top:6px"><button class="btn sm" id="bSave" style="flex:1">Save</button><input class="inp" id="saveAsName" placeholder="new-preset-name" style="flex:1.3"><button class="btn sm" id="bSaveAs">Save as</button></div>
    <div class="status" id="presetStatus"></div>
    <div class="sub-lab">Spec JSON</div>
    <textarea class="inp" id="specJson" spellcheck="false" placeholder="Copy fills this box. Paste a spec here, then Import."></textarea>
    <div class="line" style="margin-top:6px"><button class="btn sm" id="bCopy" style="flex:1">Copy current</button><button class="btn sm" id="bImport" style="flex:1">Import</button></div>
    <div class="hint" style="margin-top:8px">Presets live in scripts/launch-graphics/presets/. Unsaved edits are kept as a draft in this browser.</div>
  </div></details>
  <div class="hint" style="padding:12px 0"><kbd>←↑→↓</kbd> nudge (<kbd>⇧</kbd> ×5) · drag on stage · <kbd>Esc</kbd> deselect</div>
  <div class="actions"><div class="btns"><button class="btn" id="bReset">Reset layout</button><button class="btn primary" id="bExport">Download PNG (2×)</button></div></div>`;

export function startEditor(scene) {
  const panel = $("#panel");
  panel.innerHTML = PANEL;
  const stage = $("#stage");
  const frame = $("#frame");
  const canvas = scene.canvas;
  let spec = loadDraft();
  let sel = null;
  let presetName = localStorage.getItem(`${DRAFT_KEY}:preset`) || null;
  let presets = [];
  let layout = null;
  let scale = 1;

  function loadDraft() {
    const qs = new URLSearchParams(location.search);
    if (qs.get("preset")) return normalize({});
    try {
      return normalize(JSON.parse(localStorage.getItem(DRAFT_KEY) || "{}"));
    } catch {
      return normalize({});
    }
  }
  const saveDraft = () => {
    try {
      localStorage.setItem(DRAFT_KEY, JSON.stringify(spec));
      if (presetName) localStorage.setItem(`${DRAFT_KEY}:preset`, presetName);
      else localStorage.removeItem(`${DRAFT_KEY}:preset`);
    } catch {}
  };
  const fo = () => (spec.formats[spec.format] ||= {});
  const lay = (id) => ((fo().devices ||= {})[id] ||= {});
  const content = (id) => (spec.devices[id] ||= {});
  const textOv = () => (fo().text ||= {});

  /* ---------------- render ---------------- */
  function update(panelToo = true) {
    try {
      ({ layout } = scene.render(spec, { editor: true, selected: sel }));
    } catch (err) {
      setStatus(err.message, true);
      return;
    }
    fit();
    if (panelToo) renderPanel();
    saveDraft();
  }
  function fit() {
    const cs = getComputedStyle(stage);
    const aw = stage.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    const ah = stage.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
    scale = Math.min(aw / layout.W, ah / layout.H);
    canvas.style.transform = `scale(${scale})`;
    frame.style.width = `${layout.W * scale}px`;
    frame.style.height = `${layout.H * scale}px`;
  }
  addEventListener("resize", () => layout && fit());

  /* ---------------- panel ---------------- */
  function seg(id, items, cur, on) {
    const el = $(`#${id}`);
    el.innerHTML = "";
    for (const [v, label] of items) {
      const b = document.createElement("button");
      b.textContent = label;
      b.className = v === cur ? "on" : "";
      b.onclick = () => on(v);
      el.append(b);
    }
  }
  const FMT = {};
  const slider = (name, label, val, min, max, step, fmt) => {
    FMT[name] = fmt;
    return `<div class="row"><label>${label}</label><input type="range" name="${name}" min="${min}" max="${max}" step="${step}" value="${val}"><output>${fmt(+val)}</output></div>`;
  };

  function thumb(compId) {
    const L = computeLayout({ ...spec, composition: compId, formats: {}, devices: {} }, { measure: scene.measure });
    const { W, H } = L;
    const t = L.text.box;
    let s = `<svg viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg"><rect width="${W}" height="${H}" style="fill:var(--ui-raised)"/>`;
    for (const id of [...DEVICE_IDS].sort((a, b) => L.devices[a].z - L.devices[b].z)) {
      const G = L.devices[id];
      if (!G.visible) continue;
      const g = DEVICES[id];
      const r = g.R * G.w;
      const b = (g.rim + g.bz) * G.w;
      s += `<rect x="${G.x}" y="${G.y}" width="${G.w}" height="${G.h}" rx="${r}" style="fill:var(--ui-weak)"/>` +
        `<rect x="${G.x + b}" y="${G.y + b}" width="${G.w - 2 * b}" height="${G.h - 2 * b}" rx="${Math.max(2, r - b)}" style="fill:var(--ui-line)"/>`;
    }
    const hl = L.text.type.hl;
    const cx = L.text.align === "center";
    const bar = (y, w, h, op) => `<rect x="${cx ? t.x + (t.w - w) / 2 : t.x}" y="${y}" width="${w}" height="${h}" rx="${h / 3}" style="fill:var(--ui-strong)" opacity="${op}"/>`;
    const y1 = L.text.band || L.text.compact ? t.y + hl * 1.2 : t.y + t.h / 2 - hl * 1.2;
    s += bar(t.y, Math.min(t.w * 0.3, hl * 3), hl * 0.5, 0.5) + bar(y1, Math.min(t.w * 0.85, hl * 7.5), hl * 0.78, 0.95) + bar(y1 + hl * 1.05, Math.min(t.w * 0.6, hl * 5.5), hl * 0.78, 0.95);
    return `${s}</svg>`;
  }

  function renderFormats() {
    const known = FORMATS.some((f) => f.key === spec.format);
    let h = "";
    for (const [group, label] of [["post", "Post"], ["banner", "Banner"]]) {
      h += `<div class="sub-lab">${label}</div><div class="fchips">`;
      for (const f of FORMATS.filter((x) => x.group === group)) {
        h += `<button class="fchip${f.key === spec.format ? " on" : ""}" data-f="${f.key}"><span class="r">${f.ratio}</span><span class="s">${f.key.replace("x", "×")}</span><span class="u">${f.use}</span></button>`;
      }
      h += "</div>";
    }
    const cur = parseFormat(known ? localStorage.getItem(`${DRAFT_KEY}:custom`) || "1800x700" : spec.format) || { w: 1800, h: 700 };
    h += `<div class="sub-lab">Custom</div><div class="fcustom${known ? "" : " on"}"><input class="inp" type="number" id="cfW" min="300" max="4000" value="${cur.w}"><span>×</span><input class="inp" type="number" id="cfH" min="300" max="4000" value="${cur.h}"><button class="btn sm" id="cfGo">${known ? "Use" : "Apply"}</button></div>`;
    const box = $("#fmtPicker");
    box.innerHTML = h;
    box.querySelectorAll("[data-f]").forEach((b) => (b.onclick = () => { spec.format = b.dataset.f; sel = null; update(); }));
    const go = () => {
      const f = parseFormat(`${$("#cfW").value}x${$("#cfH").value}`);
      if (!f) return setStatus("Custom size must be 300–4000 on each side.", true);
      localStorage.setItem(`${DRAFT_KEY}:custom`, f.key);
      spec.format = f.key;
      sel = null;
      update();
    };
    $("#cfGo").onclick = go;
    for (const i of [$("#cfW"), $("#cfH")]) i.addEventListener("keydown", (e) => e.key === "Enter" && go());
    const info = FORMATS.find((f) => f.key === spec.format);
    $("#fmtVal").textContent = info ? `${info.ratio} · ${spec.format.replace("x", "×")}` : spec.format.replace("x", "×");
  }

  function renderPanel() {
    renderFormats();
    seg("segMode", [["dark", "Dark"], ["light", "Light"]], spec.mode, (v) => { spec.mode = v; update(); });
    seg("segGrain", [["none", "Off"], ["grain", "Subtle grain"]], spec.texture, (v) => { spec.texture = v; update(); });
    const zone = SAFE_AREAS[layout.format];
    $("#safeRow").hidden = !zone;
    if (zone) seg("segSafe", [[false, "Off"], [true, `Show ${zone.label}`]], spec.safeArea, (v) => { spec.safeArea = v; update(); });

    // compositions
    const cards = $("#cards");
    cards.innerHTML = "";
    for (const c of COMPOSITIONS) {
      const b = document.createElement("button");
      b.className = `card${c.id === spec.composition ? " on" : ""}`;
      b.title = c.desc;
      b.innerHTML = `<span class="th">${thumb(c.id)}</span><span class="nm">${c.name}</span>`;
      b.onclick = () => { // a template applies fresh: drop layout edits, keep content
        spec.composition = c.id;
        spec.formats = {};
        delete spec.crop;
        spec.text = {};
        for (const d of Object.values(spec.devices)) for (const k of LAYOUT_DEVICE_KEYS) delete d[k];
        sel = null;
        update();
      };
      cards.append(b);
    }
    $("#compVal").textContent = COMPOSITIONS.find((c) => c.id === spec.composition).name;
    $("#cropRow").hidden = !layout.cropKnob;
    if (layout.cropKnob) seg("segCrop", [["top", "Top of screen"], ["bottom", "Bottom of screen"]], layout.crop, (v) => { fo().crop = v; if (spec.formats[spec.format].text) delete spec.formats[spec.format].text.position; update(); });
    const notes = $("#notes");
    notes.hidden = !layout.notes.length;
    notes.innerHTML = layout.notes.map((n) => `<div>${esc(n)}</div>`).join("");

    seg("segMoment", Object.entries(MOMENTS).map(([k, m]) => [k, m.name]), spec.moment, (v) => { // content only
      spec.moment = v;
      for (const k of MOMENT_COPY_KEYS) delete spec.copy[k];
      for (const d of Object.values(spec.devices)) { delete d.shot; delete d.screenMode; }
      update();
    });

    // devices
    const list = $("#devlist");
    list.innerHTML = "";
    for (const id of DEVICE_IDS) {
      const G = layout.devices[id];
      const it = document.createElement("div");
      it.className = `it${sel === id ? " on" : ""}${G.visible ? "" : " off"}`;
      const state = G.visible ? `${G.anchor} · ${SCREENS[G.shot].toLowerCase()}` : G.autoHidden ? "auto-hidden" : "not in layout";
      it.innerHTML = `<b>${DEVICES[id].name}</b><small>${state}</small><button class="eye" title="Show / hide">${G.visible ? EYE : EYE_OFF}</button>`;
      it.onclick = (e) => {
        if (e.target.closest(".eye")) {
          lay(id).visible = !G.visible;
          sel = lay(id).visible ? id : sel === id ? null : sel;
        } else sel = id;
        update();
      };
      list.append(it);
    }
    renderDeviceEditor();

    // text
    const T = layout.text;
    $("#textVal").textContent = `${T.position} · ${T.align}`;
    seg("segTpos", [["left", "Left"], ["right", "Right"], ["top", "Top"], ["bottom", "Bottom"]], T.position, (v) => { textOv().position = v; delete textOv().width; update(); });
    seg("segTalign", [["left", "Left"], ["center", "Center"]], T.align, (v) => { textOv().align = v; update(); });
    const W = layout.W;
    const [mn, mx] = T.band ? [Math.round(W * 0.4), W - 2 * layout.M.pad[0]] : [160, Math.round(W * 0.55)];
    $("#twRow").innerHTML = slider("tw", "Width", Math.round(T.width), mn, mx, 2, (v) => `${v}px`);
    const tw = $("#twRow input");
    tw.oninput = () => { textOv().width = +tw.value; tw.nextElementSibling.textContent = `${tw.value}px`; update(false); };
    tw.onchange = () => update();

    // presets
    $("#presetVal").textContent = presetName || "unsaved";
    $("#bSave").disabled = !presetName;
  }

  function renderDeviceEditor() {
    const ed = $("#devedit");
    if (!sel) {
      ed.innerHTML = '<div class="hint">Select a device to change its screen, crop anchor, scale, tilt and focus.</div>';
      return;
    }
    const id = sel;
    const G = layout.devices[id];
    const o = lay(id);
    const mode = G.screenMode === "auto" ? spec.mode : G.screenMode;
    ed.innerHTML = `<div class="sub-lab">${DEVICES[id].name} · screen</div>
      <div class="shots">${Object.entries(SCREENS).map(([k, n]) => `<button data-shot="${k}" class="${k === G.shot ? "on" : ""}"><img src="${shotUrl(id, mode, k)}" alt="" loading="lazy"><span>${n}</span></button>`).join("")}</div>
      <div class="sub-lab">Crop anchor</div><div class="seg small" id="segAnchor"></div>
      <div class="sub-lab">Screenshot mode</div><div class="seg small" id="segSmode"></div>
      <div class="sub-lab">Adjust</div>
      ${slider("scale", "Scale", o.scale ?? 1, 0.4, 2, 0.01, (v) => `${Math.round(v * 100)}%`)}
      ${slider("tilt", "Tilt", o.tilt || 0, -12, 12, 0.5, (v) => `${v}°`)}
      ${slider("focus", "Focus", G.focus, 1, 2.6, 0.01, (v) => `${v.toFixed(2)}×`)}
      ${slider("focusAt", "Focus at", Math.round(G.focusAt), 0, 100, 1, (v) => `${v}%`)}`;
    seg("segAnchor", [["top", "Top"], ["bottom", "Bottom"], ["full", "Full"], ["custom", "Custom"]], G.anchor, (v) => { o.anchor = v; delete o.x; delete o.y; update(); });
    seg("segSmode", [["auto", "Follow mode"], ["dark", "Dark"], ["light", "Light"]], G.screenMode, (v) => { content(id).screenMode = v; update(); });
    ed.querySelectorAll("[data-shot]").forEach((b) => (b.onclick = () => { content(id).shot = b.dataset.shot; update(); }));
    ed.querySelectorAll("input[type=range]").forEach((inp) => {
      inp.oninput = () => { o[inp.name] = +inp.value; inp.nextElementSibling.textContent = FMT[inp.name](+inp.value); update(false); };
      inp.onchange = () => update();
    });
  }

  /* ---------------- canvas interaction ---------------- */
  scene.tb.querySelectorAll("[data-k]").forEach((el) => {
    el.setAttribute("contenteditable", "plaintext-only");
    el.addEventListener("paste", (e) => {
      e.preventDefault();
      document.execCommand("insertText", false, e.clipboardData.getData("text/plain"));
    });
    el.addEventListener("input", () => {
      if (el.dataset.k === "headline" && layout.text.wide) return; // one-line view: committed on blur
      spec.copy[el.dataset.k] = el.innerText.replace(/\n$/, "");
      update(false);
    });
    el.addEventListener("blur", () => {
      if (el.dataset.k === "headline" && layout.text.wide) {
        const v = el.innerText.trim();
        const cur = (spec.copy.headline ?? MOMENTS[spec.moment].copy.headline).replace(/\s*\n\s*/g, " ");
        if (v !== cur) spec.copy.headline = v;
      }
      update();
    });
  });
  let drag = null;
  canvas.addEventListener("pointerdown", (e) => {
    if (e.target.closest("[contenteditable]")) return;
    const w = e.target.closest(".devw");
    document.activeElement?.blur?.();
    if (!w) {
      if (sel) { sel = null; update(); }
      return;
    }
    const id = w.dataset.dev;
    const o = lay(id);
    sel = id;
    update();
    drag = { id, px: e.clientX, py: e.clientY, x: o.x || 0, y: o.y || 0, el: w };
    w.classList.add("dragging");
    canvas.setPointerCapture?.(e.pointerId);
    e.preventDefault();
  });
  canvas.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const o = lay(drag.id);
    o.x = Math.round(drag.x + (e.clientX - drag.px) / scale);
    o.y = Math.round(drag.y + (e.clientY - drag.py) / scale);
    update(false);
  });
  const endDrag = () => {
    if (!drag) return;
    drag.el.classList.remove("dragging");
    drag = null;
    update();
  };
  canvas.addEventListener("pointerup", endDrag);
  canvas.addEventListener("pointercancel", endDrag);
  const isEditing = () => {
    const a = document.activeElement;
    return !!a && (a.isContentEditable || (/^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) && a.type !== "range"));
  };
  addEventListener("keydown", (e) => {
    if (isEditing()) {
      if (e.key === "Escape") document.activeElement.blur();
      return;
    }
    if (e.key === "Escape") { sel = null; update(); return; }
    if (!sel) return;
    const step = e.shiftKey ? 20 : 4;
    const mv = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[e.key];
    if (!mv) return;
    if (document.activeElement?.type === "range") document.activeElement.blur();
    e.preventDefault();
    const o = lay(sel);
    o.x = (o.x || 0) + mv[0];
    o.y = (o.y || 0) + mv[1];
    update();
  });

  /* ---------------- presets ---------------- */
  function setStatus(msg, bad = false) {
    const el = $("#presetStatus");
    el.textContent = msg;
    el.style.color = bad ? "var(--ui-strong)" : "";
  }
  /** Spec without empty override objects, for saving and sharing. */
  function tidy(s) {
    const prune = (o) => {
      if (!o || typeof o !== "object" || Array.isArray(o)) return o;
      for (const k of Object.keys(o)) {
        o[k] = prune(o[k]);
        if (o[k] && typeof o[k] === "object" && !Array.isArray(o[k]) && !Object.keys(o[k]).length) delete o[k];
      }
      return o;
    };
    const out = prune(JSON.parse(JSON.stringify(s)));
    return { version: 1, ...out };
  }
  async function refreshPresets() {
    try {
      presets = await (await fetch("/api/presets")).json();
    } catch {
      presets = [];
    }
    const s = $("#presetSel");
    s.innerHTML = presets.map((p) => `<option value="${esc(p.id)}"${p.id === presetName ? " selected" : ""}>${esc(p.id)} · ${esc(p.format)}</option>`).join("") || "<option>No presets</option>";
  }
  async function openPreset(id) {
    const res = await fetch(`/api/presets/${encodeURIComponent(id)}`);
    if (!res.ok) return setStatus(`Could not open ${id}: ${res.status}`, true);
    spec = normalize(await res.json());
    presetName = id;
    $("#presetSel").value = id;
    sel = null;
    setStatus(`Opened ${id}.`);
    update();
  }
  async function savePreset(id) {
    const res = await fetch(`/api/presets/${encodeURIComponent(id)}`, { method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify(tidy(spec), null, 2) });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) return setStatus(body.error || `Save failed: ${res.status}`, true);
    presetName = id;
    setStatus(`Saved ${body.path || id}.`);
    await refreshPresets();
    update();
  }
  $("#bOpen").onclick = () => $("#presetSel").value && openPreset($("#presetSel").value);
  $("#bSave").onclick = () => presetName && savePreset(presetName);
  $("#bSaveAs").onclick = () => {
    const id = $("#saveAsName").value.trim();
    if (!/^[a-z0-9][a-z0-9-]{0,60}$/.test(id)) return setStatus("Name: lowercase letters, digits and dashes.", true);
    spec.name = id;
    delete spec.description;
    savePreset(id);
  };
  $("#bCopy").onclick = async () => {
    const txt = JSON.stringify(tidy(spec), null, 2);
    $("#specJson").value = txt;
    try {
      await navigator.clipboard.writeText(txt);
      setStatus("Copied spec JSON.");
    } catch {
      setStatus("Spec JSON is in the box below.");
    }
  };
  $("#bImport").onclick = () => {
    try {
      spec = normalize(JSON.parse($("#specJson").value));
      presetName = null;
      sel = null;
      setStatus("Imported spec.");
      update();
    } catch (err) {
      setStatus(err.message, true);
    }
  };
  $("#bReset").onclick = () => {
    delete spec.formats[spec.format];
    sel = null;
    update();
  };

  /* ---------------- in-browser PNG (html-to-image); headless renders use render.mjs ---------------- */
  let fontCSS = null;
  async function embedFonts() {
    if (fontCSS) return fontCSS;
    const toData = async (u) => {
      const blob = await (await fetch(u)).blob();
      return new Promise((r) => {
        const fr = new FileReader();
        fr.onload = () => r(fr.result);
        fr.readAsDataURL(blob);
      });
    };
    const rules = [];
    for (const sheet of document.styleSheets) for (const rule of sheet.cssRules) if (rule instanceof CSSFontFaceRule) rules.push(rule.cssText);
    const out = [];
    for (const r of rules) {
      const m = r.match(/url\("?([^")]+)"?\)/);
      out.push(m ? r.replace(m[1], await toData(m[1])) : r);
    }
    return (fontCSS = out.join("\n"));
  }
  async function loadHtmlToImage() {
    if (window.htmlToImage) return window.htmlToImage;
    await new Promise((resolve, reject) => {
      const s = Object.assign(document.createElement("script"), { src: "/vendor/html-to-image.js", onload: resolve, onerror: reject });
      document.head.append(s);
    });
    return window.htmlToImage;
  }
  $("#bExport").onclick = async () => {
    const b = $("#bExport");
    b.disabled = true;
    b.textContent = "Rendering…";
    const prev = sel;
    sel = null;
    update(false);
    canvas.classList.add("exporting");
    try {
      const h2i = await loadHtmlToImage();
      const url = await h2i.toPng(canvas, {
        width: layout.W, height: layout.H, pixelRatio: 2, cacheBust: false, fontEmbedCSS: await embedFonts(),
        style: { transform: "none", left: "0", top: "0" },
        filter: (n) => !(n.classList && (n.classList.contains("ui") || n.classList.contains("measure"))),
      });
      Object.assign(document.createElement("a"), { href: url, download: `${presetName || "launch-graphic"}-${spec.format}-${spec.mode}.png` }).click();
    } catch (err) {
      setStatus(`Export failed: ${err.message || err}`, true);
    } finally {
      canvas.classList.remove("exporting");
      sel = prev;
      b.disabled = false;
      b.textContent = "Download PNG (2×)";
      update();
    }
  };

  /* ---------------- boot ---------------- */
  const qs = new URLSearchParams(location.search);
  refreshPresets().then(() => qs.get("preset") && openPreset(qs.get("preset")));
  if (qs.get("open")) for (const label of qs.get("open").split(",")) [...panel.querySelectorAll("details")].find((d) => d.querySelector("summary").textContent.toLowerCase().startsWith(label))?.setAttribute("open", "");
  if (DEVICE_IDS.includes(qs.get("select"))) sel = qs.get("select");
  update();
  window.launchGraphics.editor = { get spec() { return spec; }, update, openPreset, savePreset };
}
