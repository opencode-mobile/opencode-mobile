import assert from "node:assert/strict";
import { test } from "node:test";
import { DEVICE_IDS } from "./renderer/devices.js";
import { NEAR_FULL, computeLayout, contentColumn, metrics, screenRect } from "./renderer/layouts.js";
import { MOMENTS } from "./renderer/moments.js";
import { COMPOSITION_IDS, FORMATS, normalize } from "./renderer/spec.js";

const FORMAT_KEYS = [...FORMATS.map((f) => f.key), "1800x700", "900x1600", "1200x1000", "1440x1200"];
const MOMENT_IDS = Object.keys(MOMENTS);
const round = (v) => Math.round(v * 100) / 100;

function frameSignature(L) {
  return JSON.stringify({
    text: { ...L.text, box: Object.fromEntries(Object.entries(L.text.box).map(([k, v]) => [k, round(v)])) },
    area: L.area,
    composition: L.composition,
    crop: L.crop,
    devices: DEVICE_IDS.map((id) => {
      const G = L.devices[id];
      return [id, G.visible, G.anchor, round(G.x), round(G.y), round(G.w), round(G.h), G.tilt];
    }),
  });
}

test("switching moment never changes the layout (every composition × format)", () => {
  for (const format of FORMAT_KEYS) {
    for (const composition of COMPOSITION_IDS) {
      const sigs = MOMENT_IDS.map((moment) => frameSignature(computeLayout(normalize({ format, composition, moment }))));
      for (let i = 1; i < sigs.length; i++) assert.equal(sigs[i], sigs[0], `${composition} @ ${format}: ${MOMENT_IDS[i]} differs from ${MOMENT_IDS[0]}`);
    }
  }
});

test("text x/y move the text block and leave the devices alone", () => {
  const base = { format: "1600x900", composition: "full", moment: "code" };
  const a = computeLayout(normalize(base));
  const b = computeLayout(normalize({ ...base, text: { x: 120, y: -30 } }));
  assert.equal(b.text.box.x, a.text.box.x + 120);
  assert.equal(b.text.box.y, a.text.box.y - 30);
  assert.deepEqual(b.devices, a.devices);
});

test("text x/y on band text leave the device area alone", () => {
  for (const position of ["top", "bottom"]) {
    const base = { format: "1600x900", composition: "pair", moment: "code", text: { position } };
    const a = computeLayout(normalize(base));
    const b = computeLayout(normalize({ ...base, text: { position, x: 40, y: 60 } }));
    assert.ok(a.text.band, `${position} is band text`);
    assert.equal(b.text.box.y, a.text.box.y + 60);
    assert.deepEqual(b.area, a.area, `${position}: device area moved`);
    assert.deepEqual(b.devices, a.devices, `${position}: devices moved`);
  }
});

test("layout edits survive a moment switch", () => {
  const base = { format: "1600x900", composition: "pair", formats: { "1600x900": { devices: { iphone: { x: 40, scale: 0.9, anchor: "bottom" } } } } };
  const a = computeLayout(normalize({ ...base, moment: "approval" }));
  const b = computeLayout(normalize({ ...base, moment: "sessions" }));
  assert.equal(frameSignature(a), frameSignature(b));
  assert.equal(a.devices.iphone.anchor, "bottom");
});

test("no visible secondary device is a cut-off sliver", () => {
  for (const format of FORMAT_KEYS) {
    for (const composition of COMPOSITION_IDS) {
      const L = computeLayout(normalize({ format, composition }));
      for (const id of DEVICE_IDS) {
        const G = L.devices[id];
        if (!G.visible || G.z >= 3) continue;
        const s = screenRect(id, G);
        const [x0, x1] = contentColumn(G);
        const tol = s.w * 0.03 + 0.5;
        assert.ok(s.x + s.w * x0 >= -tol && s.x + s.w * x1 <= L.W + tol, `${composition} @ ${format}: ${id} content is cut by a canvas edge`);
      }
    }
  }
});

test("a cropped device is either fully on the canvas or clearly cropped, never almost whole", () => {
  for (const format of FORMAT_KEYS) {
    for (const composition of COMPOSITION_IDS) {
      for (const text of [{}, { position: "left" }]) {
        const L = computeLayout(normalize({ format, composition, text }));
        for (const id of DEVICE_IDS) {
          const G = L.devices[id];
          if (!G.visible || (G.anchor !== "top" && G.anchor !== "bottom")) continue;
          const shown = (Math.min(L.H, G.y + G.h) - Math.max(0, G.y)) / G.h;
          assert.ok(shown >= 0.999 || shown < NEAR_FULL, `${composition} @ ${format} (text ${text.position || "default"}): ${id} shows ${round(shown * 100)}% of its frame`);
        }
      }
    }
  }
});

test("devices never cover the text block", () => {
  for (const format of FORMAT_KEYS) {
    for (const composition of COMPOSITION_IDS) {
      const L = computeLayout(normalize({ format, composition }));
      const t = L.text.box;
      for (const id of DEVICE_IDS) {
        const G = L.devices[id];
        if (!G.visible) continue;
        const overlapX = Math.min(G.x + G.w, t.x + t.w) - Math.max(G.x, t.x);
        const overlapY = Math.min(G.y + G.h, t.y + t.h) - Math.max(G.y, t.y);
        assert.ok(overlapX <= 1 || overlapY <= 1, `${composition} @ ${format}: ${id} overlaps the text block (${round(overlapX)}×${round(overlapY)})`);
      }
    }
  }
});

test("every device sits at least partly on the canvas and the hero is visible", () => {
  for (const format of FORMAT_KEYS) {
    for (const composition of COMPOSITION_IDS) {
      const L = computeLayout(normalize({ format, composition }));
      const visible = DEVICE_IDS.filter((id) => L.devices[id].visible);
      assert.ok(visible.some((id) => L.devices[id].z >= 3), `${composition} @ ${format}: no hero`);
      for (const id of visible) {
        const G = L.devices[id];
        assert.ok(G.x < L.W && G.x + G.w > 0 && G.y < L.H && G.y + G.h > 0, `${composition} @ ${format}: ${id} is off canvas`);
      }
    }
  }
});

test("banners fall back where a composition cannot work, with a note", () => {
  const L = computeLayout(normalize({ format: "1584x396", composition: "full" }));
  assert.equal(L.composition, "top");
  assert.match(L.notes.join(" "), /Full device doesn't fit/);
  assert.equal(computeLayout(normalize({ format: "1500x500", composition: "triple" })).composition, "phonetab");
});

test("banner safe areas push default text and devices past the avatar", () => {
  for (const format of ["1500x500", "1584x396"]) {
    const L = computeLayout(normalize({ format, composition: "pair" }));
    const left = L.W * 0.25;
    assert.ok(L.text.box.x >= left, `${format}: text starts inside the avatar zone`);
    for (const id of DEVICE_IDS) if (L.devices[id].visible) assert.ok(L.devices[id].x >= left, `${format}: ${id} inside the avatar zone`);
  }
});

test("a custom format parses and lays out", () => {
  const L = computeLayout(normalize({ format: "1800x700", composition: "closeup" }));
  assert.equal(L.W, 1800);
  assert.equal(L.H, 700);
  assert.equal(metrics("1800x700").cls, "land");
  assert.equal(metrics("900x1600").cls, "port");
  assert.ok(L.devices.iphone.visible);
});

test("forcing a hidden device on keeps it", () => {
  const auto = computeLayout(normalize({ format: "1600x900", composition: "closeup" }));
  assert.equal(auto.devices.android.visible, false);
  const forced = computeLayout(normalize({ format: "1600x900", composition: "closeup", devices: { android: { visible: true } } }));
  assert.equal(forced.devices.android.visible, true);
});
