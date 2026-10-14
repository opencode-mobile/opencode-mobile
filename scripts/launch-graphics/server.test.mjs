import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { resolveShot } from "./server.mjs";

function shotsDir(files) {
  const root = mkdtempSync(join(tmpdir(), "launch-graphics-shots-"));
  for (const f of files) {
    mkdirSync(join(root, f, ".."), { recursive: true });
    writeFileSync(join(root, f), "");
  }
  return root;
}

test("resolves the nested store-screenshots layout", () => {
  const root = shotsDir(["ios-6.9/dark/02-chat-code.png"]);
  try {
    assert.equal(resolveShot(root, "ios-6.9/dark/02-chat-code.png"), join(root, "ios-6.9/dark/02-chat-code.png"));
  } finally {
    rmSync(root, { recursive: true });
  }
});

test("falls back to flat <device>_<screen>_<mode>.png names", () => {
  const root = shotsDir([
    "iphone-16-pro-max_02-chat-code_dark.png",
    "android-phone-pixel-9_01-home_light.png",
    "android-tablet-pixel-tablet-10in_01-home_light.png",
    "ipad-pro-13-m4_04-changes_dark.png",
  ]);
  try {
    assert.equal(resolveShot(root, "ios-6.9/dark/02-chat-code.png"), join(root, "iphone-16-pro-max_02-chat-code_dark.png"));
    assert.equal(resolveShot(root, "android-phone/light/01-home.png"), join(root, "android-phone-pixel-9_01-home_light.png"));
    assert.equal(resolveShot(root, "android-tab10/light/01-home.png"), join(root, "android-tablet-pixel-tablet-10in_01-home_light.png"));
    assert.equal(resolveShot(root, "ipad-13/dark/04-changes.png"), join(root, "ipad-pro-13-m4_04-changes_dark.png"));
  } finally {
    rmSync(root, { recursive: true });
  }
});

test("prefers nested files and refuses paths outside the root", () => {
  const root = shotsDir(["ios-6.9/dark/01-home.png", "iphone-16-pro-max_01-home_dark.png"]);
  try {
    assert.equal(resolveShot(root, "ios-6.9/dark/01-home.png"), join(root, "ios-6.9/dark/01-home.png"));
    assert.equal(resolveShot(root, "ios-6.9/light/01-home.png"), join(root, "ios-6.9/light/01-home.png"));
    assert.equal(resolveShot(root, "../../etc/passwd"), null);
  } finally {
    rmSync(root, { recursive: true });
  }
});
