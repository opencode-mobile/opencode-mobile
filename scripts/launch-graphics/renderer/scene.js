// spec -> canvas DOM, using layouts.js (geometry) and devices.js (frames).
// Exposes window.launchGraphics.render(spec) -> Promise resolved once fonts and screenshots are loaded.
import { DEVICE_IDS, createDeviceElement, applyDevice, shotUrl } from "./devices.js";
import { computeLayout } from "./layouts.js";
import { resolveCopy } from "./moments.js";
import { SAFE_AREAS, normalize } from "./spec.js";

const APPLE = '<svg viewBox="0 0 24 24"><path d="M16.4 12.6c0-2.4 2-3.6 2.1-3.7-1.1-1.7-2.9-1.9-3.5-1.9-1.5-.2-2.9.9-3.7.9-.8 0-1.9-.9-3.2-.8-1.6 0-3.1 1-4 2.4-1.7 3-.4 7.3 1.2 9.7.8 1.2 1.8 2.5 3 2.4 1.2 0 1.7-.8 3.2-.8s1.9.8 3.2.8c1.3 0 2.2-1.2 3-2.4.9-1.4 1.3-2.7 1.3-2.8-.1 0-2.6-1-2.6-3.8zM14 5.4c.7-.8 1.1-1.9 1-3-1 0-2.1.7-2.8 1.5-.6.7-1.2 1.8-1 2.9 1.1.1 2.1-.6 2.8-1.4z"/></svg>';
const PLAY = '<svg viewBox="0 0 24 24"><path d="M4.2 2.6c-.2.2-.3.6-.3 1v16.8c0 .4.1.8.3 1l9.4-9.4-9.4-9.4zm10.4 8.4 2.7-2.7L6.1 2c-.4-.2-.8-.3-1.1-.2l9.6 9.2zm0 2-9.6 9.2c.3.1.7 0 1.1-.2l11.2-6.3-2.7-2.7zm3.6-4.1-2.9 3.1 2.9 3.1 3.3-1.9c.9-.5.9-1.8 0-2.3l-3.3-2z"/></svg>';
const TEXT_HTML =
  '<div class="tb-head"><div class="brand"><img src="/icon.png" alt=""><span class="nm" data-k="brand"></span></div></div>' +
  '<div class="copy"><div class="kick" data-k="label"></div><h1 class="hl" data-k="headline"></h1><p class="sub" data-k="subline"></p></div>' +
  '<div class="tb-foot"><div class="badges">' +
  `<div class="badge">${APPLE}<span class="t"><small data-k="appStoreLine"></small><b data-k="appStore"></b></span></div>` +
  `<div class="badge">${PLAY}<span class="t"><small data-k="googlePlayLine"></small><b data-k="googlePlay"></b></span></div>` +
  '</div><div class="fine" data-k="disclaimer"></div></div>';

// canvas custom property -> porcelain.json override token
const CANVAS_TOKENS = { bg: "background-base", raised: "surface-raised-base", line: "border-weak-base", line2: "border-weaker-base", strong: "text-strong", base: "text-base", weak: "text-weak", acc: "interactive" };
// editor chrome (always dark) -> token
const UI_TOKENS = { "ui-bg": "background-base", "ui-raised": "surface-raised-base", "ui-line": "border-weak-base", "ui-line2": "border-weaker-base", "ui-strong": "text-strong", "ui-base": "text-base", "ui-weak": "text-weak", "ui-weaker": "text-weaker", "ui-acc": "interactive", "ui-accw": "surface-interactive-weak", "ui-btn": "button-primary-base" };

let tokens = null;
export async function loadTokens() {
  if (!tokens) {
    const res = await fetch("/tokens");
    if (!res.ok) throw new Error(`GET /tokens -> ${res.status}`);
    tokens = await res.json();
    for (const [prop, t] of Object.entries(UI_TOKENS)) document.documentElement.style.setProperty(`--${prop}`, tokens.dark.overrides[t]);
  }
  return tokens;
}

const collapse = (s) => s.replace(/\s*\n\s*/g, " ");

/** Style a text block element for a text config from layouts.textConfig. */
export function styleText(el, T) {
  el.className = `tb ${T.band ? "band" : "col"} al-${T.align}${T.wide ? " wide" : ""}${T.compact ? " compact" : ""}`;
  for (const k of ["hl", "sub", "kick", "brand", "icon", "bh", "bb", "fine", "g"]) el.style.setProperty(`--${k}`, `${T.type[k]}px`);
  el.style.setProperty("--subw", T.band ? `${T.type.subw || 9999}px` : "100%");
  el.style.width = `${T.width}px`;
  const head = el.querySelector(".tb-head");
  const foot = el.querySelector(".tb-foot");
  const badges = el.querySelector(".badges");
  if (T.band && T.align === "left") { if (badges.parentNode !== head) head.append(badges); }
  else if (badges.parentNode !== foot) foot.prepend(badges);
}

function fillText(el, copy, T) {
  el.querySelectorAll("[data-k]").forEach((node) => {
    if (node === document.activeElement) return;
    let v = copy[node.dataset.k] ?? "";
    if (node.dataset.k === "headline" && T.wide) v = collapse(v);
    if (node.textContent !== v) node.textContent = v;
  });
}

function grainUrl() {
  const c = document.createElement("canvas");
  c.width = c.height = 256;
  const x = c.getContext("2d");
  const id = x.createImageData(256, 256);
  let seed = 7;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647); // deterministic grain
  for (let i = 0; i < id.data.length; i += 4) {
    const v = 128 + (rnd() - 0.5) * 90;
    id.data[i] = id.data[i + 1] = id.data[i + 2] = v;
    id.data[i + 3] = 255;
  }
  x.putImageData(id, 0, 0);
  return c.toDataURL("image/png");
}

/** Build the canvas inside `root`. Returns a scene with render(spec, opts). */
export function createScene(root) {
  const canvas = document.createElement("div");
  canvas.className = "canvas";
  const devRoot = document.createElement("div");
  const els = {};
  for (const id of DEVICE_IDS) devRoot.append((els[id] = createDeviceElement(id)));
  const tb = document.createElement("div");
  tb.className = "tb";
  tb.innerHTML = TEXT_HTML;
  const measureRoot = Object.assign(document.createElement("div"), { className: "measure" });
  const grain = Object.assign(document.createElement("div"), { className: "grain" });
  grain.style.backgroundImage = `url(${grainUrl()})`;
  const safe = Object.assign(document.createElement("div"), { className: "safe ui" });
  canvas.append(devRoot, tb, measureRoot, grain, safe);
  root.append(canvas);

  const cache = new Map();
  /** DOM text measurement for layouts.computeLayout: height of the block for each copy. */
  function measure(T, copies) {
    const key = JSON.stringify([T, copies]);
    if (cache.has(key)) return cache.get(key);
    const m = document.createElement("div");
    m.innerHTML = TEXT_HTML;
    styleText(m, T);
    measureRoot.replaceChildren(m);
    const heights = copies.map((c) => {
      fillText(m, c, T);
      return m.offsetHeight;
    });
    measureRoot.replaceChildren();
    cache.set(key, heights);
    return heights;
  }

  let last = null;
  /**
   * Render a spec. opts: { editor: bool (safe-area overlay, selection), selected: device id }.
   * Returns { spec, layout, ready } where ready resolves to { missing: [urls] } once fonts + images settle.
   */
  function render(input, opts = {}) {
    const spec = normalize(input);
    const layout = computeLayout(spec, { measure });
    const { W, H } = layout;
    canvas.style.width = `${W}px`;
    canvas.style.height = `${H}px`;
    canvas.classList.toggle("light", spec.mode === "light");
    canvas.classList.toggle("grained", spec.texture === "grain");
    canvas.classList.toggle("editing", !!opts.editor);
    if (tokens) for (const [prop, t] of Object.entries(CANVAS_TOKENS)) canvas.style.setProperty(`--${prop}`, tokens[spec.mode].overrides[t]);

    const T = layout.text;
    styleText(tb, T);
    fillText(tb, resolveCopy(spec.copy, spec.moment), T);
    Object.assign(tb.style, { left: `${T.box.x}px`, top: `${T.box.y}px`, height: T.band || T.compact ? "auto" : `${T.box.h}px` });

    const imgs = [];
    for (const id of DEVICE_IDS) {
      const G = layout.devices[id];
      const mode = G.screenMode === "auto" ? spec.mode : G.screenMode;
      const img = applyDevice(els[id], id, G, shotUrl(id, mode, G.shot));
      els[id].classList.toggle("sel", opts.selected === id);
      if (G.visible) imgs.push(img);
    }
    const zone = SAFE_AREAS[layout.format];
    safe.innerHTML = opts.editor && spec.safeArea && zone
      ? zone.zones.map(([x, y, w, h]) => `<div class="zone" style="left:${x * W}px;top:${y * H}px;width:${w * W}px;height:${h * H}px"><span>${zone.label}</span></div>`).join("")
      : "";

    const ready = (async () => {
      await document.fonts.ready;
      const missing = [];
      await Promise.all(imgs.map((img) => new Promise((resolve) => {
        const done = () => {
          img.classList.toggle("missing", img.naturalWidth === 0);
          if (img.naturalWidth === 0) missing.push(img.getAttribute("src"));
          resolve();
        };
        if (img.complete) done();
        else {
          img.addEventListener("load", done, { once: true });
          img.addEventListener("error", done, { once: true });
        }
      })));
      const icon = tb.querySelector(".brand img");
      if (!icon.complete) await new Promise((r) => { icon.onload = icon.onerror = r; });
      return { missing };
    })();
    last = { spec, layout };
    return { spec, layout, ready };
  }

  return { canvas, els, tb, render, measure, get last() { return last; } };
}
