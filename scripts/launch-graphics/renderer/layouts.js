// Compositions (layout templates), parametric by aspect ratio. PURE: no DOM.
//   computeLayout(spec, { format, measure }) -> text block, device rects, crops, auto-hidden devices, notes.
// Rules the user insisted on:
//   - composition = layout only; moment = content only (never moves frames or shows/hides devices)
//   - the sliver rule is geometry-only; banner safe areas only shift defaults (overlay is editor-only)
import { DEVICES, DEVICE_IDS, kOf } from "./devices.js";
import { CONTENT, MOMENTS, resolveCopy, shotFor } from "./moments.js";
import { COMPOSITIONS, SAFE_AREAS, formatInfo, parseFormat } from "./spec.js";

// Hand-tuned originals; every other size is derived from these by aspect class and scale.
const LEGACY = {
  "1600x900": { pad: [96, 80], edge: 68, gap: [56, 40], colw: 580, type: {
    col: { hl: 70, sub: 22, kick: 14, brand: 21, icon: 44, bh: 56, bb: 18, fine: 13, g: 24 },
    band: { hl: 58, sub: 20, kick: 13, brand: 20, icon: 40, bh: 50, bb: 16, fine: 12, g: 18, subw: 820 } } },
  "1080x1350": { pad: [80, 76], edge: 72, gap: [44, 44], colw: 430, type: {
    col: { hl: 48, sub: 19, kick: 13, brand: 19, icon: 40, bh: 48, bb: 15, fine: 12, g: 20 },
    band: { hl: 80, sub: 23, kick: 14, brand: 21, icon: 44, bh: 56, bb: 18, fine: 13, g: 24, subw: 680 } } },
  "1080x1080": { pad: [72, 68], edge: 64, gap: [44, 40], colw: 440, type: {
    col: { hl: 50, sub: 20, kick: 14, brand: 21, icon: 44, bh: 50, bb: 16, fine: 12, g: 22 },
    band: { hl: 58, sub: 20, kick: 13, brand: 20, icon: 40, bh: 50, bb: 16, fine: 12, g: 20, subw: 720 } } },
  "1200x630": { pad: [64, 52], edge: 44, gap: [44, 26], colw: 440, type: {
    col: { hl: 48, sub: 17, kick: 12, brand: 18, icon: 36, bh: 44, bb: 15, fine: 11, g: 16 },
    band: { hl: 40, sub: 15, kick: 11, brand: 16, icon: 32, bh: 38, bb: 13, fine: 11, g: 12, subw: 700 } } },
};
const MINS = { hl: 24, sub: 12, kick: 10, brand: 13, icon: 24, bh: 30, bb: 11, fine: 10, g: 8 };
const clone = (v) => JSON.parse(JSON.stringify(v));
const clamp01 = (v) => Math.max(0, Math.min(1, v));

/** Spacing, type scale and aspect class for a format key. Type sizes are whole px (html-to-image rasterises fractions differently). */
export function metrics(formatKey) {
  const f = parseFormat(formatKey);
  if (!f) throw new Error(`bad format ${formatKey}`);
  const { w: W, h: H } = f;
  const r = W / H;
  const cls = r < 0.95 ? "port" : r <= 1.2 ? "sq" : r >= 2.6 ? "banner" : "land";
  let M;
  if (LEGACY[f.key]) M = clone(LEGACY[f.key]);
  else {
    const base = LEGACY[cls === "port" ? "1080x1350" : cls === "sq" ? "1080x1080" : "1600x900"];
    const k = cls === "port" || cls === "sq" ? W / 1080 : Math.min(H / 900, W / 1600);
    const sc = (t, m = 1) => Object.fromEntries(Object.entries(t).map(([n, v]) => [n, Math.round(v * k * m)]));
    M = {
      pad: base.pad.map((v) => Math.round(v * k)),
      edge: Math.round(base.edge * k),
      gap: base.gap.map((v) => Math.round(v * k)),
      colw: Math.round(base.colw * k),
      type: { col: sc(base.type.col), band: sc(base.type.band) },
    };
    if (cls === "banner") { // compact side text, devices in a strip
      M.colw = Math.round(W * 0.24);
      M.type.col = sc(base.type.col, 1.12);
      M.pad = [Math.round(W * 0.035), Math.round(H * 0.1)];
      M.edge = Math.round(H * 0.08);
      M.gap = [Math.round(W * 0.022), Math.round(H * 0.06)];
    }
    for (const t of [M.type.col, M.type.band]) for (const n in MINS) t[n] = Math.max(MINS[n], t[n]);
  }
  return { ...M, W, H, r, cls, key: f.key, safeLeft: SAFE_AREAS[f.key] ? Math.round(W * SAFE_AREAS[f.key].left) : 0 };
}

/* ---------------------------------------------------------------- composition helpers
   spec = { box:{x,y,w,h}, anchor, role, z, roi?, ref?, tablet? }
     top/bottom: frame width = box.w, top edge at box.y / bottom edge at box.y+box.h (bleeds the other way)
     full: whole device fitted in box;  custom: a FIXED reference region fitted in box (frames never follow content)
   Positions assume a left text column; a right column mirrors them. */
const vspan = (c) => {
  const { A, H, edge } = c;
  return [A.y <= 0 ? edge : A.y, A.y + A.h >= H ? H - edge : A.y + A.h];
};
const bandSide = (crop) => (crop === "bottom" ? "bottom" : "top");
const sideText = (c) => (c.wideA ? { position: "left" } : { position: bandSide(c.crop) });
const vbox = (c, x, w, crop, off = 0) => {
  const [y0, y1] = vspan(c);
  return crop === "bottom" ? { x, y: y0, w, h: y1 - y0 - off } : { x, y: y0 + off, w, h: y1 - y0 - off };
};
const tabletSpec = (c, x, w, role, off = 0) => ({ box: vbox(c, x, w, c.crop, off), anchor: c.crop, role, tablet: true, z: 1 });
const pairW = (d1, d2, total) => total / (1 + kOf(d1) / kOf(d2)); // frame width of d1 such that both screens match
export const zoomFor = (id, shot, maxZ = 1.45) => {
  const [x0, x1] = CONTENT[`${id}-${shot}`] || [0, 1];
  return +Math.min(maxZ, 0.98 / (x1 - x0)).toFixed(3);
};
/**
 * A top/bottom crop showing at least this much of the device frame reads as an accident. Such a device is fitted
 * whole (`nearFull: "fit"`, the default; devices sharing a `group` shrink together) or pushed further past the
 * edge until DEEP_CROP of its frame shows (`nearFull: "deepen"`, for deliberately staggered compositions).
 */
export const NEAR_FULL = 0.85;
const DEEP_CROP = 0.78;
/** Compositions with a "crop shows top/bottom of screen" layout knob. */
export const CROP_KNOB = ["pair", "phonetab", "diagonal", "triple"];

function cropDevices(c) {
  const A = c.A;
  const w = !c.band ? Math.min(A.w * 0.88, c.H * 0.86) : c.W * (c.port ? 0.72 : 0.56);
  const x = !c.band ? A.x + (A.w - w) / 2 : (c.W - w) / 2;
  return { iphone: { box: vbox(c, x, w, c.crop), anchor: c.crop, role: "shot", z: 3 } };
}

const IMPL = {
  closeup: {
    crop: () => "bottom",
    text: (c) => (c.port ? { position: "bottom" } : { position: "left" }),
    devices(c) {
      const A = c.A;
      const out = {};
      if (c.band) {
        const w = c.W * (c.port ? 0.7 : 0.56);
        out.iphone = { box: { x: (c.W - w) / 2, y: A.y, w, h: A.h }, anchor: "bottom", role: "shot", z: 3 };
        return out;
      }
      const roi = [0, 1, 0.555, 0.968];
      const hb = c.sq
        ? { x: A.x, y: c.H * 0.176, w: A.w * 0.9, h: c.H * 0.74 }
        : { x: A.x + A.w * 0.078, y: c.H * 0.071, w: Math.min(A.w * 0.7, c.H * (c.banner ? 1.6 : 0.78)), h: c.H * 0.858 };
      hb.w = Math.min(hb.w, hb.h / ((roi[3] - roi[2]) * DEVICES.iphone.ar)); // box = fitted hero, so a second can sit beside it
      const sx = hb.x + hb.w + c.edge * 0.75;
      const room = A.x + A.w - c.edge * 0.6 - sx;
      if (!c.sq && room >= c.H * 0.34) out.android = { box: { x: sx, y: c.H * 0.133, w: room, h: c.H * 0.733 }, anchor: "custom", roi: [0, 1, 0.245, 0.79], role: "alt", z: 2 };
      else hb.x = A.x + (A.w - hb.w) / 2;
      out.iphone = { box: hb, anchor: "custom", roi, role: "shot", z: 3 };
      return out;
    },
  },
  bottom: { crop: () => "bottom", text: (c) => (c.land ? { position: "left" } : { position: "bottom" }), devices: cropDevices },
  top: { crop: () => "top", text: (c) => (c.land ? { position: "left" } : { position: "top" }), devices: cropDevices },
  full: {
    crop: () => "full",
    text: () => ({ position: "left" }),
    fallback: (c) => (c.banner ? "top" : null),
    devices(c) {
      const A = c.A;
      const [y0, y1] = vspan(c);
      const mx = A.w * 0.05;
      return { iphone: { box: { x: A.x + mx, y: y0, w: A.w - 2 * mx, h: y1 - y0 }, anchor: "full", role: "shot", z: 3 } };
    },
  },
  pair: {
    crop: (c) => c.cropOv || "top",
    text: sideText,
    devices(c) {
      let x0, total, gap;
      if (!c.band) {
        gap = c.A.w * 0.04;
        total = c.A.w * 0.9 - gap;
        x0 = c.A.x + c.A.w * 0.05;
      } else {
        const side = c.W * (c.land ? 0.094 : c.port ? 0.037 : 0.046);
        gap = Math.round(c.W * 0.03);
        total = c.W - 2 * side - gap;
        x0 = side;
      }
      const wi = pairW("iphone", "android", total);
      const wa = (wi * kOf("iphone")) / kOf("android");
      return {
        iphone: { box: vbox(c, x0, wi, c.crop), anchor: c.crop, role: "shot", z: 3, group: "pair" },
        android: { box: vbox(c, x0 + wi + gap, wa, c.crop), anchor: c.crop, role: "shot", z: 3, group: "pair" },
      };
    },
  },
  phonetab: {
    crop: (c) => c.cropOv || "top",
    text: (c) => (c.port || c.sq ? { position: bandSide(c.crop) } : { position: "left" }),
    devices(c) {
      const A = c.A;
      const span = vspan(c)[1] - vspan(c)[0];
      let tab, pw, px;
      if (c.band) {
        tab = tabletSpec(c, c.W * 0.04, c.W * 0.74, "tab");
        pw = c.W * (c.port ? 0.42 : 0.37);
        px = c.W - pw - c.W * 0.05;
      } else {
        const tw = Math.min(A.w * 0.7, c.H * 1.35);
        const tx = A.x + tw * 0.14; // the bezel and gutter reach ~13% of the box width past the content column
        tab = { box: { x: tx, y: c.edge * 0.5, w: tw, h: c.H - c.edge }, anchor: "custom", roi: [0.06, 0.94, 0.12, 0.78], role: "tab", tablet: true, z: 1 };
        pw = Math.min(A.w * 0.44, tw * 0.6);
        px = Math.min(A.x + A.w - pw - A.w * 0.03, tx + tw - pw * 0.55);
      }
      return { tablet: tab, iphone: { box: vbox(c, px, pw, c.crop, span * 0.16), anchor: c.crop, role: "shot", z: 3, nearFull: "deepen" } };
    },
  },
  tablet: {
    crop: () => "full",
    text: (c) => (c.land ? { position: "left" } : { position: "top" }),
    devices(c) { // the frame touches the canvas (or band) edges; the zoom inside makes the UI big, whatever the moment
      const A = c.A;
      const y0 = c.band ? A.y : 0;
      const h = c.band ? A.h - c.edge * 0.6 : c.H;
      return { ipad: { box: { x: A.x + A.w * 0.04, y: y0, w: A.w * 0.92, h }, anchor: "full", role: "solo", tablet: true, z: 3 } };
    },
  },
  diagonal: {
    crop: (c) => c.cropOv || "top",
    text: (c) => (c.land ? { position: "left" } : { position: bandSide(c.crop) }),
    devices(c) {
      const A = c.A;
      const [y0, y1] = vspan(c);
      const span = y1 - y0;
      const step = 0.95; // overlap only bezel + gutter, never content
      const w = !c.band ? Math.min((A.w * 0.92) / (1 + step), c.H * (c.banner ? 0.9 : 0.62)) : c.W * (c.port ? 0.47 : 0.44);
      const x0 = !c.band ? A.x + (A.w - w * (1 + step)) / 2 : (c.W - w * (1 + step)) / 2;
      const off = span * (c.land ? 0.2 : 0.14);
      return {
        iphone: { box: vbox(c, x0, w, c.crop, c.crop === "top" ? off : 0), anchor: c.crop, role: "shot", z: 3, nearFull: "deepen" },
        android: {
          box: c.crop === "top" ? vbox(c, x0 + w * step, w, "top") : { x: x0 + w * step, y: y0, w, h: span - off },
          anchor: c.crop,
          role: "alt",
          z: 2,
          nearFull: "deepen",
        },
      };
    },
  },
  triple: {
    crop: (c) => c.cropOv || "top",
    text: sideText,
    fallback: (c) => (c.banner ? "phonetab" : null),
    devices(c) {
      const R = c.band ? { x: 0, w: c.W } : c.A;
      const tw = R.w * (c.band ? (c.land ? 0.54 : 0.6) : 0.56);
      const pw = R.w * (c.band ? (c.land ? 0.19 : c.port ? 0.3 : 0.27) : 0.21);
      const tx = R.x + (R.w - tw) / 2;
      const span = vspan(c)[1] - vspan(c)[0];
      const wa = (pw * kOf("iphone")) / kOf("android");
      return {
        ipad: tabletSpec(c, tx, tw, "tab"),
        iphone: { box: vbox(c, Math.max(R.x + R.w * 0.04, tx - pw * 0.78), pw, c.crop, span * 0.1), anchor: c.crop, role: "shot", z: 3, nearFull: "deepen" },
        android: { box: vbox(c, Math.min(R.x + R.w * 0.96 - wa, tx + tw - wa * 0.22), wa, c.crop, span * 0.1), anchor: c.crop, role: "shot", z: 3, nearFull: "deepen" },
      };
    },
  },
};
export const IMPLEMENTED = Object.keys(IMPL);

const defaultRole = (id) => (DEVICES[id].phone ? "shot" : id === "ipad" ? "solo" : "tab");

/** Merge base + per-format overrides for a format. */
export function overridesFor(spec, formatKey) {
  const fo = spec.formats?.[formatKey] || {};
  const devices = {};
  for (const id of DEVICE_IDS) devices[id] = { ...(spec.devices?.[id] || {}), ...(fo.devices?.[id] || {}) };
  return { text: { ...(spec.text || {}), ...(fo.text || {}) }, crop: fo.crop ?? spec.crop ?? null, devices };
}

/** The composition's own geometry for a device, before near-full handling and the user's scale/offset edits. */
function baseGeometry(id, spec, o, ctx) {
  const g = DEVICES[id];
  const e = g.rim + g.bz;
  const k = 1 - 2 * e;
  const A = ctx.A;
  const inComp = !!spec;
  spec ||= { box: { x: A.x + A.w * 0.25, y: ctx.edge, w: A.w * 0.5, h: ctx.H - 2 * ctx.edge }, anchor: "full", role: defaultRole(id), tablet: !g.phone, z: 0 };
  const shot = o.shot || shotFor(ctx.moment, spec.role);
  const anchor = o.anchor || spec.anchor;
  const box = spec.box;
  const H_ = (w) => 2 * e * w + k * w * g.ar;
  let W, x, y;
  if (anchor === "top" || anchor === "bottom") {
    W = box.w;
    x = box.x;
    y = anchor === "top" ? box.y : box.y + box.h - H_(W);
  } else if (anchor === "full") {
    W = Math.min(box.w, box.h / (2 * e + k * g.ar));
    x = box.x + (box.w - W) / 2;
    y = box.y + (box.h - H_(W)) / 2;
  } else { // custom: fit a FIXED reference region (never the current screenshot) into the box
    const [x0, x1, y0, y1] = spec.roi || CONTENT[`${id}-02-chat-code`] || [0, 1, 0, 1];
    const sw = Math.min(box.w / (x1 - x0), box.h / ((y1 - y0) * g.ar));
    W = sw / k;
    x = box.x + box.w / 2 - (sw * (x0 + x1)) / 2 - e * W;
    y = box.y + box.h / 2 - (sw * g.ar * (y0 + y1)) / 2 - e * W;
  }
  const H = H_(W);
  if (anchor === "custom" && ctx.band) { // never slide under the text band
    if (ctx.tpos === "top" && y < A.y) y = A.y;
    if (ctx.tpos === "bottom" && y + H > A.y + A.h) y = A.y + A.h - H;
  }
  return { id, g, e, k, spec, inComp, shot, anchor, W, H, x, y };
}

/** Share of a device frame's height that is on the canvas. */
const shownShare = (b, H) => (Math.min(H, b.y + b.H) - Math.max(0, b.y)) / b.H;

/** Fit near-whole top/bottom crops fully (groups shrink together) or deepen them; see NEAR_FULL. Geometry only. */
function avoidNearFull(bases, ctx) {
  const cropped = (b) => b.inComp && (b.anchor === "top" || b.anchor === "bottom");
  const near = (b) => {
    const s = shownShare(b, ctx.H);
    return cropped(b) && s < 0.999 && s >= NEAR_FULL;
  };
  const fitKeys = new Set();
  for (const b of bases) {
    if (!near(b)) continue;
    if (b.spec.nearFull !== "deepen") fitKeys.add(b.spec.group || b.id);
    else if (b.y + b.H > ctx.H) b.y = ctx.H - DEEP_CROP * b.H; // cut at the bottom: push further down
    else b.y = -(1 - DEEP_CROP) * b.H; // cut at the top: push further up
  }
  const [y0, y1] = vspan(ctx);
  const avail = y1 - y0;
  for (const key of fitKeys) {
    const members = bases.filter((b) => cropped(b) && (b.spec.group || b.id) === key);
    const f = Math.min(1, ...members.map((b) => avail / b.H));
    const gcx = (Math.min(...members.map((b) => b.x)) + Math.max(...members.map((b) => b.x + b.W))) / 2;
    const slack = (avail - Math.max(...members.map((b) => b.H * f))) / 2;
    for (const b of members) {
      const cx = gcx + (b.x + b.W / 2 - gcx) * f;
      b.W *= f;
      b.H *= f;
      b.x = cx - b.W / 2;
      b.y = b.anchor === "bottom" ? y1 - slack - b.H : y0 + slack; // keep the anchored edges aligned
    }
  }
}

/** Content focus, mirroring and the user's scale/offset edits on top of the (adjusted) base geometry. */
function finishDevice(b, o, ctx, mirror) {
  const { id, g, e, k, spec, inComp, shot, anchor } = b;
  let { W, H, x, y } = b;
  // content-only adjustment: tablets fill the screen with their content column and aim it at the visible window
  let focus = 1;
  let focusAt = 50;
  if (spec.tablet) {
    const sy = y + e * W;
    const sh = k * W * g.ar;
    const v0 = clamp01(-sy / sh);
    const v1 = clamp01((ctx.H - sy) / sh);
    const cr = CONTENT[`${id}-${shot}`] || [0, 1, 0, 1];
    const cy = (cr[2] + cr[3]) / 2;
    focus = zoomFor(id, shot);
    focusAt = focus > 1.001 ? Math.round(clamp01(((v0 + v1) / 2 - cy * focus) / (1 - focus)) * 100) : 50;
  }
  if (mirror) x = ctx.W - x - W;
  const s = o.scale ?? 1;
  const cx = x + W / 2;
  const cy2 = y + H / 2;
  W *= s;
  H *= s;
  x = cx - W / 2 + (o.x || 0);
  y = cy2 - H / 2 + (o.y || 0);
  return {
    visible: o.visible ?? inComp,
    inComp,
    forced: o.visible === true,
    autoHidden: false,
    x, y, w: W, h: H,
    tilt: o.tilt || 0,
    focus: o.focus ?? focus,
    focusAt: o.focusAt ?? focusAt,
    shot,
    screenMode: o.screenMode || "auto",
    anchor,
    role: spec.role,
    z: spec.z || 0,
    tablet: !!spec.tablet,
  };
}

/** Screen rect (inside the bezel) of a laid-out device. */
export function screenRect(id, G) {
  const g = DEVICES[id];
  const e = (g.rim + g.bz) * G.w;
  const sw = G.w - 2 * e;
  return { x: G.x + e, y: G.y + e, w: sw, h: sw * g.ar };
}
/** Horizontal content column (fraction of the screen) used by the sliver rule: geometry only, never the screenshot. */
export const contentColumn = (G) => (G.tablet ? [0.06, 0.94] : [0, 1]);

// A secondary device (z < 3) stays only if its content column is inside the canvas (no text cut by an edge),
// at least 30% of its height shows, and >= 45% of its content width is clear of the text column and front devices.
function enforceVisibility(devs, textBox, band, W, H) {
  const hidden = [];
  for (const id of DEVICE_IDS) {
    const G = devs[id];
    if (!G.visible || !G.inComp || G.z >= 3 || G.forced) continue;
    const s = screenRect(id, G);
    const [x0, x1] = contentColumn(G);
    const cx0 = s.x + s.w * x0;
    const cx1 = s.x + s.w * x1;
    const tol = s.w * 0.03;
    const vy0 = Math.max(0, s.y);
    const vy1 = Math.min(H, s.y + s.h);
    let ok = cx0 >= -tol && cx1 <= W + tol && vy1 - vy0 >= Math.min(s.h, H) * 0.3;
    if (ok) {
      let segs = [[Math.max(0, cx0), Math.min(W, cx1)]];
      const cut = (a, b) => {
        segs = segs.flatMap(([p, q]) => (b <= p || a >= q ? [[p, q]] : [[p, Math.max(p, a)], [Math.min(q, b), q]].filter(([u, v]) => v - u > 1)));
      };
      if (!band && textBox.y < vy1 && textBox.y + textBox.h > vy0) cut(textBox.x, textBox.x + textBox.w);
      for (const f of DEVICE_IDS) {
        const F = devs[f];
        if (f === id || !F.visible || F.z <= G.z) continue;
        const r = screenRect(f, F);
        const ov = Math.min(vy1, r.y + r.h) - Math.max(vy0, r.y);
        if (ov > (vy1 - vy0) * 0.5) cut(F.x, F.x + F.w);
      }
      ok = segs.reduce((n, [p, q]) => n + q - p, 0) >= (cx1 - cx0) * 0.45;
    }
    if (!ok) {
      G.visible = false;
      G.autoHidden = true;
      hidden.push(id);
    }
  }
  return hidden;
}

const formatLabel = (key) => {
  const f = formatInfo(key);
  return f ? `${f.ratio} ${f.use}` : key.replace("x", "×");
};

/** Text block config for a composition + format (position/band/compact/width), before measuring. */
export function textConfig(ctx, compId, textOv) {
  const def = IMPL[compId].text(ctx);
  const M = ctx.M;
  const position = textOv.position || def.position;
  const band = position === "top" || position === "bottom";
  const px = M.pad[0];
  const max = band ? M.W - 2 * px : M.W - 2 * px - ctx.sl;
  const width = Math.min(textOv.width || (band ? M.W - 2 * px : M.colw), max);
  return {
    position,
    band,
    compact: !band && ctx.banner,
    wide: band && M.r > 1.2, // one-line headline
    align: textOv.align || def.align || "left",
    width,
    type: M.type[band ? "band" : "col"],
  };
}

/**
 * Lay out a normalized spec for one format.
 * measure(textConfig, copies[]) -> heights[] (px) for the text block with each copy; layouts reserves the tallest
 * so switching moment never moves anything. Omit in tests to use a rough estimate.
 */
export function computeLayout(spec, { format = spec.format, measure = estimateText } = {}) {
  const M = metrics(format);
  const chosen = spec.composition;
  const ov = overridesFor(spec, M.key);
  const ctx = {
    M, W: M.W, H: M.H, edge: M.edge, moment: spec.moment,
    land: M.r > 1.2, banner: M.cls === "banner", port: M.cls === "port", sq: M.cls === "sq", sl: M.safeLeft, cropOv: ov.crop,
  };
  ctx.wideA = ctx.banner || (ctx.land && M.W - (M.pad[0] + ctx.sl + M.colw + M.gap[0]) >= 1.25 * M.H);
  let comp = chosen;
  const fallback = IMPL[comp].fallback?.(ctx) || null;
  if (fallback) comp = fallback;
  ctx.crop = IMPL[comp].crop(ctx);

  // text block: reserve the tallest of all moments' copy
  const T = textConfig(ctx, comp, ov.text);
  const copies = Object.keys(MOMENTS).map((id) => resolveCopy(spec.copy, id));
  const heights = T.band || T.compact ? measure(T, copies) : null;
  const hmax = heights ? Math.max(...heights) : 0;
  const { W, H } = M;
  const [px, py] = M.pad;
  const [gc, gb] = M.gap;
  let box;
  if (!T.band) {
    const x = T.position === "right" ? W - px - T.width : ctx.sl + px;
    box = T.compact ? { x, y: Math.max(py * 0.6, (H - hmax) / 2), w: T.width, h: hmax } : { x, y: py, w: T.width, h: H - 2 * py };
  } else {
    const pb = Math.round(py * 0.8);
    box = { x: T.align === "center" ? (W - T.width) / 2 : px, y: T.position === "top" ? pb : H - pb - hmax, w: T.width, h: hmax };
  }
  const mirror = T.position === "right";
  ctx.band = T.band;
  ctx.tpos = T.position;
  if (!T.band) {
    const x = px + T.width + gc + (mirror ? 0 : ctx.sl);
    ctx.A = { x, y: 0, w: W - x - (mirror ? ctx.sl : 0), h: H };
  } else if (T.position === "top") {
    const y = box.y + box.h + gb;
    ctx.A = { x: 0, y, w: W, h: H - y };
  } else ctx.A = { x: 0, y: 0, w: W, h: box.y - gb };

  const specs = IMPL[comp].devices(ctx);
  const bases = DEVICE_IDS.map((id) => baseGeometry(id, specs[id], ov.devices[id], ctx));
  avoidNearFull(bases, ctx);
  const devices = {};
  for (const b of bases) devices[b.id] = finishDevice(b, ov.devices[b.id], ctx, mirror);
  const hidden = enforceVisibility(devices, box, T.band, W, H);

  const notes = [];
  if (fallback) notes.push(`${nameOf(chosen)} doesn't fit a ${formatLabel(M.key)} canvas, so this uses ${nameOf(fallback)}.`);
  for (const id of hidden) notes.push(`${DEVICES[id].name} hidden: only a cut-off sliver would show at this size. Turn it on to force it.`);
  return { format: M.key, W, H, M, chosen, composition: comp, fallback, crop: ctx.crop, cropKnob: CROP_KNOB.includes(comp), text: { ...T, box }, area: ctx.A, devices, hidden, notes };
}

const nameOf = (id) => COMPOSITIONS.find((c) => c.id === id)?.name || id;

/** Rough text height for headless tests (the renderer passes a DOM measurement). */
export function estimateText(T, copies) {
  const t = T.type;
  const lineH = (size, text, width, factor) => {
    const perLine = Math.max(1, Math.floor(width / (size * factor)));
    return String(text).split("\n").reduce((n, l) => n + Math.max(1, Math.ceil(l.length / perLine)), 0);
  };
  return copies.map((c) => {
    const hl = T.wide ? c.headline.replace(/\s*\n\s*/g, " ") : c.headline;
    let h = t.icon + t.g * 1.15 + t.kick + t.g * 0.9;
    h += lineH(t.hl, hl, T.width, 0.52) * t.hl * 1.02 + t.g;
    h += lineH(t.sub, c.subline, Math.min(T.width, t.subw || T.width), 0.5) * t.sub * 1.45;
    h += t.g * 0.75 + (T.band && T.align === "left" ? 0 : t.bh + t.g * 0.7) + t.fine * 1.4;
    return Math.round(h);
  });
}
