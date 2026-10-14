import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  COMPOSITION_IDS,
  FORMATS,
  SpecError,
  defaultSpec,
  expandFormats,
  normalize,
  parseFormat,
  parseValue,
  setPath,
} from "./renderer/spec.js";
import { IMPLEMENTED } from "./renderer/layouts.js";

const PRESETS = join(dirname(fileURLToPath(import.meta.url)), "presets");

test("a minimal spec normalizes with defaults", () => {
  const spec = normalize({ composition: "bottom", moment: "code" });
  assert.equal(spec.composition, "bottom");
  assert.equal(spec.moment, "code");
  assert.equal(spec.format, "1600x900");
  assert.equal(spec.mode, "dark");
  assert.equal(spec.texture, "none");
  assert.equal(spec.safeArea, true);
  assert.deepEqual(spec.copy, {});
  assert.deepEqual(spec.formats, {});
  assert.deepEqual(normalize({}), defaultSpec());
});

test("format keys are normalized and range-checked", () => {
  assert.equal(normalize({ format: "1800 × 700" }).format, "1800x700");
  assert.deepEqual(parseFormat("1584x396"), { key: "1584x396", w: 1584, h: 396 });
  assert.equal(parseFormat("200x900"), null);
  assert.equal(parseFormat("wide"), null);
});

test("invalid values are rejected with every message", () => {
  const bad = {
    format: "10x10",
    mode: "dim",
    composition: "collage",
    moment: "lunch",
    texture: "paper",
    copy: { headline: 3, title: "x" },
    text: { position: "middle", width: "wide" },
    devices: { phone: {}, iphone: { anchor: "side", scale: 9, shot: "06-nope", visible: "yes" } },
    formats: { banner: { text: { align: "justify" } } },
    colour: "red",
  };
  let err;
  try {
    normalize(bad);
  } catch (e) {
    err = e;
  }
  assert.ok(err instanceof SpecError);
  const text = err.messages.join("\n");
  for (const needle of [
    'unknown key "colour"', "format must be WxH", "mode must be one of", "composition must be one of", "moment must be one of",
    "texture must be one of", "copy.headline must be a string", "copy.title is not a copy field", "text.position must be one of",
    "text.width must be a number", "devices.phone is not a device", "devices.iphone.anchor must be one of", "devices.iphone.scale must be a number",
    "devices.iphone.shot must be one of", "devices.iphone.visible must be true or false", "formats.banner: key must be WxH", "formats.banner.text.align must be one of",
  ]) assert.match(text, new RegExp(needle.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), needle);
});

test("--set helpers parse values and create paths", () => {
  assert.equal(parseValue("12"), 12);
  assert.equal(parseValue("true"), true);
  assert.equal(parseValue("Ship from anywhere."), "Ship from anywhere.");
  assert.deepEqual(parseValue('{"a":1}'), { a: 1 });
  const s = setPath(setPath({}, "copy.headline", "Hi"), "formats.1500x500.devices.iphone.scale", 1.2);
  assert.deepEqual(s, { copy: { headline: "Hi" }, formats: { "1500x500": { devices: { iphone: { scale: 1.2 } } } } });
  assert.equal(normalize(s).formats["1500x500"].devices.iphone.scale, 1.2);
});

test("format groups expand", () => {
  assert.equal(expandFormats("all").length, FORMATS.length);
  assert.deepEqual(expandFormats("banners"), ["2100x900", "1500x500", "1584x396"]);
  assert.deepEqual(expandFormats("1600x900, 1800x700,1600x900"), ["1600x900", "1800x700"]);
  assert.throws(() => expandFormats("huge"), SpecError);
});

test("every catalog composition is implemented", () => {
  assert.deepEqual([...COMPOSITION_IDS].sort(), [...IMPLEMENTED].sort());
});

test("committed presets are valid specs", () => {
  const files = readdirSync(PRESETS).filter((f) => f.endsWith(".json"));
  assert.ok(files.length >= 3);
  for (const f of files) normalize(JSON.parse(readFileSync(join(PRESETS, f), "utf8")));
});
