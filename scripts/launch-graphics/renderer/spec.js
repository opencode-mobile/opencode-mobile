// Launch graphic spec: format catalog, schema defaults, normalize/validate, --set helpers. Pure (no DOM).
import { DEVICE_IDS } from "./devices.js";
import { COPY_KEYS, MOMENTS, SCREENS } from "./moments.js";

export const SPEC_VERSION = 1;

export const FORMATS = [
  { key: "1600x900", group: "post", ratio: "16:9", use: "X · LinkedIn post" },
  { key: "1600x800", group: "post", ratio: "2:1", use: "X in-feed, widest" },
  { key: "1500x1000", group: "post", ratio: "3:2", use: "Feed photo" },
  { key: "1600x1200", group: "post", ratio: "4:3", use: "Feed · slides" },
  { key: "1200x630", group: "post", ratio: "1.91:1", use: "Link preview" },
  { key: "1080x1350", group: "post", ratio: "4:5", use: "Instagram · LinkedIn" },
  { key: "1080x1080", group: "post", ratio: "1:1", use: "Square post" },
  { key: "2100x900", group: "banner", ratio: "21:9", use: "Ultrawide hero" },
  { key: "1500x500", group: "banner", ratio: "3:1", use: "X profile header" },
  { key: "1584x396", group: "banner", ratio: "4:1", use: "LinkedIn banner" },
];
export const FORMAT_MIN = 300;
export const FORMAT_MAX = 4000;

/** Banner safe areas (editor overlay only). zones: [x, y, w, h] fractions; left: default inset for text and devices. */
export const SAFE_AREAS = {
  "1500x500": { label: "X avatar", zones: [[0, 0.6, 0.25, 0.4]], left: 0.25 },
  "1584x396": { label: "LinkedIn avatar (desktop)", zones: [[0, 0, 0.25, 1]], left: 0.25 },
};

/** Layout templates. Implementations live in layouts.js. */
export const COMPOSITIONS = [
  { id: "closeup", name: "Close-up", desc: "One big phone hanging from the top; a second joins only where it fits whole" },
  { id: "bottom", name: "Bottom crop", desc: "Phone hangs from the top edge; the bottom of the screen shows" },
  { id: "top", name: "Top crop", desc: "Phone rises from the bottom edge; the top of the screen shows" },
  { id: "full", name: "Full device", desc: "The whole phone, as large as the canvas allows" },
  { id: "pair", name: "Side by side", desc: "iPhone and Android as a matched pair: same crop, same scale" },
  { id: "phonetab", name: "Phone + tablet", desc: "Android tablet as the big back layer, iPhone in front" },
  { id: "tablet", name: "Tablet close-up", desc: "Whole iPad, its screen zoomed into the content column" },
  { id: "diagonal", name: "Diagonal", desc: "Two phones offset like stairs, both cropped" },
  { id: "triple", name: "Triple", desc: "Phone · tablet · phone" },
];
export const COMPOSITION_IDS = COMPOSITIONS.map((c) => c.id);
export const MOMENT_IDS = Object.keys(MOMENTS);
export const MODES = ["dark", "light"];
export const TEXTURES = ["none", "grain"];
export const TEXT_POSITIONS = ["left", "right", "top", "bottom"];
export const TEXT_ALIGNS = ["left", "center"];
export const ANCHORS = ["top", "bottom", "full", "custom"];
export const CROPS = ["top", "bottom"];
export const SCREEN_MODES = ["auto", "dark", "light"];

export class SpecError extends Error {
  constructor(messages) {
    super(`Invalid launch graphic spec:\n  - ${messages.join("\n  - ")}`);
    this.messages = messages;
  }
}

/** "1600x900" -> { key, w, h } (null when malformed or out of range). */
export function parseFormat(value) {
  const m = /^\s*(\d+)\s*[x×]\s*(\d+)\s*$/i.exec(String(value ?? ""));
  if (!m) return null;
  const w = Number(m[1]);
  const h = Number(m[2]);
  if (w < FORMAT_MIN || h < FORMAT_MIN || w > FORMAT_MAX || h > FORMAT_MAX) return null;
  return { key: `${w}x${h}`, w, h };
}

export const formatInfo = (key) => FORMATS.find((f) => f.key === key) || null;

/** "all" | "posts" | "banners" | "1600x900,1500x500" -> format keys. */
export function expandFormats(arg) {
  const out = [];
  for (const part of String(arg).split(",").map((s) => s.trim()).filter(Boolean)) {
    if (part === "all") out.push(...FORMATS.map((f) => f.key));
    else if (part === "posts") out.push(...FORMATS.filter((f) => f.group === "post").map((f) => f.key));
    else if (part === "banners") out.push(...FORMATS.filter((f) => f.group === "banner").map((f) => f.key));
    else {
      const f = parseFormat(part);
      if (!f) throw new SpecError([`format "${part}" must be WxH with each side ${FORMAT_MIN}–${FORMAT_MAX}, or all/posts/banners`]);
      out.push(f.key);
    }
  }
  return [...new Set(out)];
}

export function defaultSpec() {
  return {
    version: SPEC_VERSION,
    format: "1600x900",
    mode: "dark",
    composition: "closeup",
    moment: "approval",
    copy: {},
    texture: "none",
    safeArea: true,
    text: {},
    devices: {},
    formats: {},
  };
}

const TOP_KEYS = new Set([...Object.keys(defaultSpec()), "name", "description", "crop"]);
const DEVICE_KEYS = {
  visible: "boolean",
  shot: "shot",
  screenMode: "screenMode",
  anchor: "anchor",
  x: "number",
  y: "number",
  scale: [0.2, 4],
  tilt: [-45, 45],
  focus: [0.5, 4],
  focusAt: [0, 100],
};

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const clone = (v) => JSON.parse(JSON.stringify(v));

function checkEnum(errors, path, value, allowed) {
  if (!allowed.includes(value)) errors.push(`${path} must be one of ${allowed.join(", ")} (got ${JSON.stringify(value)})`);
}

function checkText(errors, path, text) {
  if (!isObj(text)) return errors.push(`${path} must be an object`);
  for (const [k, v] of Object.entries(text)) {
    if (k === "position") checkEnum(errors, `${path}.position`, v, TEXT_POSITIONS);
    else if (k === "align") checkEnum(errors, `${path}.align`, v, TEXT_ALIGNS);
    else if (k === "width") {
      if (typeof v !== "number" || !(v >= 120 && v <= FORMAT_MAX)) errors.push(`${path}.width must be a number 120–${FORMAT_MAX}`);
    } else if (k === "size") {
      if (typeof v !== "number" || !(v >= 0.5 && v <= 2.5)) errors.push(`${path}.size must be a number 0.5–2.5`);
    } else if (k === "x" || k === "y") {
      Number.isFinite(v) || errors.push(`${path}.${k} must be a number`);
    } else errors.push(`${path}.${k} is not a text setting (position, align, width, size, x, y)`);
  }
}

function checkDevices(errors, path, devices) {
  if (!isObj(devices)) return errors.push(`${path} must be an object`);
  for (const [id, d] of Object.entries(devices)) {
    if (!DEVICE_IDS.includes(id)) {
      errors.push(`${path}.${id} is not a device (${DEVICE_IDS.join(", ")})`);
      continue;
    }
    if (!isObj(d)) {
      errors.push(`${path}.${id} must be an object`);
      continue;
    }
    for (const [k, v] of Object.entries(d)) {
      const rule = DEVICE_KEYS[k];
      const p = `${path}.${id}.${k}`;
      if (!rule) errors.push(`${p} is not a device setting (${Object.keys(DEVICE_KEYS).join(", ")})`);
      else if (rule === "boolean") typeof v === "boolean" || errors.push(`${p} must be true or false`);
      else if (rule === "shot") checkEnum(errors, p, v, Object.keys(SCREENS));
      else if (rule === "screenMode") checkEnum(errors, p, v, SCREEN_MODES);
      else if (rule === "anchor") checkEnum(errors, p, v, ANCHORS);
      else if (rule === "number") Number.isFinite(v) || errors.push(`${p} must be a number`);
      else if (!(Number.isFinite(v) && v >= rule[0] && v <= rule[1])) errors.push(`${p} must be a number ${rule[0]}–${rule[1]}`);
    }
  }
}

/**
 * Fill defaults and validate. A minimal spec like { composition: "bottom", moment: "code" } is valid.
 * Throws SpecError listing every problem.
 */
export function normalize(input = {}) {
  const errors = [];
  if (!isObj(input)) throw new SpecError(["spec must be a JSON object"]);
  for (const k of Object.keys(input)) if (!TOP_KEYS.has(k)) errors.push(`unknown key "${k}"`);
  const spec = { ...defaultSpec(), ...clone(input) };
  if (spec.version !== SPEC_VERSION) errors.push(`version must be ${SPEC_VERSION}`);
  const f = parseFormat(spec.format);
  if (!f) errors.push(`format must be WxH with each side ${FORMAT_MIN}–${FORMAT_MAX} (got ${JSON.stringify(spec.format)})`);
  else spec.format = f.key;
  checkEnum(errors, "mode", spec.mode, MODES);
  checkEnum(errors, "composition", spec.composition, COMPOSITION_IDS);
  checkEnum(errors, "moment", spec.moment, MOMENT_IDS);
  checkEnum(errors, "texture", spec.texture, TEXTURES);
  if (typeof spec.safeArea !== "boolean") errors.push("safeArea must be true or false");
  if (spec.crop !== undefined && spec.crop !== null) checkEnum(errors, "crop", spec.crop, CROPS);
  if (!isObj(spec.copy)) errors.push("copy must be an object");
  else for (const [k, v] of Object.entries(spec.copy)) {
    if (!COPY_KEYS.includes(k)) errors.push(`copy.${k} is not a copy field (${COPY_KEYS.join(", ")})`);
    else if (typeof v !== "string") errors.push(`copy.${k} must be a string`);
  }
  checkText(errors, "text", spec.text);
  checkDevices(errors, "devices", spec.devices);
  if (!isObj(spec.formats)) errors.push("formats must be an object keyed by WxH");
  else for (const [key, fo] of Object.entries(spec.formats)) {
    const p = `formats.${key}`;
    if (!parseFormat(key)) errors.push(`${p}: key must be WxH`);
    if (!isObj(fo)) {
      errors.push(`${p} must be an object`);
      continue;
    }
    for (const k of Object.keys(fo)) if (!["crop", "text", "devices"].includes(k)) errors.push(`${p}.${k} is not a per-format setting (crop, text, devices)`);
    if (fo.crop !== undefined && fo.crop !== null) checkEnum(errors, `${p}.crop`, fo.crop, CROPS);
    if (fo.text !== undefined) checkText(errors, `${p}.text`, fo.text);
    if (fo.devices !== undefined) checkDevices(errors, `${p}.devices`, fo.devices);
  }
  if (errors.length) throw new SpecError(errors);
  return spec;
}

/** Parse a --set value: numbers, booleans, null, JSON objects/arrays/strings, else the raw string. */
export function parseValue(raw) {
  const s = String(raw);
  if (s === "true") return true;
  if (s === "false") return false;
  if (s === "null") return null;
  if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
  if (/^[[{"]/.test(s)) {
    try {
      return JSON.parse(s);
    } catch {
      return s;
    }
  }
  return s;
}

/** setPath(spec, "copy.headline", "Ship it.") — creates intermediate objects. Returns the spec. */
export function setPath(spec, path, value) {
  const keys = path.split(".").filter(Boolean);
  if (!keys.length) throw new SpecError([`--set needs key=value (got "${path}")`]);
  let o = spec;
  for (const k of keys.slice(0, -1)) {
    if (!isObj(o[k])) o[k] = {};
    o = o[k];
  }
  o[keys.at(-1)] = value;
  return spec;
}
