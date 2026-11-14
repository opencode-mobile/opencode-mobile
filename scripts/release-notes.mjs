#!/usr/bin/env node
// Release notes for the in-app "What's new" sheet. Source: release-notes/release-notes.json.
//
// A release is a successful release-workflow run, not a version bump. The release workflow
// tags the commit it shipped as <platform>-v<version>; those tags are the record this script
// reads to find what changed and when each version shipped.
//
//   node scripts/release-notes.mjs validate
//       Check the file's shape. Runs on every pull request that touches it.
//   node scripts/release-notes.mjs check --platform android|ios --version 1.5.0
//       Fail unless that version has notes for that platform and every older release has a
//       date. The release workflows run this before building.
//   node scripts/release-notes.mjs draft --platform android|ios
//       List the pull requests merged since the last release tag, to write the next notes from.
//   node scripts/release-notes.mjs sync-dates
//       Fill missing `released` dates from the release tags.

import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const FILE = join(ROOT, "release-notes", "release-notes.json");
const PLATFORMS = ["android", "ios"];
const KINDS = ["new", "improved", "fixed"];
const VERSION = /^\d+\.\d+\.\d+$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

const fail = (msg) => {
  console.error(`release-notes: ${msg}`);
  process.exit(1);
};
const arg = (name) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : undefined;
};
const git = (...args) => execFileSync("git", args, { cwd: ROOT, encoding: "utf8" }).trim();
const cmp = (a, b) => {
  const x = a.split(".").map(Number);
  const y = b.split(".").map(Number);
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] - y[i];
  return 0;
};
// Prerelease builds (1.5.0-rc1) use the notes of their release version.
const baseVersion = (v) => v.replace(/-.*$/, "");

function load() {
  let data;
  try {
    data = JSON.parse(readFileSync(FILE, "utf8"));
  } catch (e) {
    fail(`cannot read ${FILE}: ${e.message}`);
  }
  return data;
}

function validate(data) {
  const errors = [];
  if (!Array.isArray(data?.releases)) return ["`releases` must be an array"];
  const last = {};
  data.releases.forEach((r, i) => {
    const at = `releases[${i}]`;
    const present = PLATFORMS.filter((p) => r[p]);
    if (!present.length) errors.push(`${at} needs an android or ios version`);
    for (const p of present) {
      const { version, released } = r[p];
      if (!VERSION.test(version ?? "")) errors.push(`${at}.${p}.version must be MAJOR.MINOR.PATCH`);
      if (released !== null && !DATE.test(released ?? "")) errors.push(`${at}.${p}.released must be YYYY-MM-DD or null`);
      if (last[p] && VERSION.test(version) && cmp(version, last[p]) >= 0)
        errors.push(`${at}.${p}.version ${version} must be lower than the release above it (${last[p]}); list newest first`);
      if (VERSION.test(version ?? "")) last[p] = version;
    }
    if (!Array.isArray(r.items) || !r.items.length) errors.push(`${at}.items must not be empty`);
    (r.items ?? []).forEach((it, j) => {
      const iat = `${at}.items[${j}]`;
      if (!KINDS.includes(it.kind)) errors.push(`${iat}.kind must be one of ${KINDS.join(", ")}`);
      for (const k of ["icon", "title", "body"])
        if (typeof it[k] !== "string" || !it[k].trim()) errors.push(`${iat}.${k} is required`);
      if (it.platforms !== undefined) {
        if (!Array.isArray(it.platforms) || !it.platforms.length || it.platforms.some((p) => !present.includes(p)))
          errors.push(`${iat}.platforms must list platforms this release has (${present.join(", ")})`);
      }
    });
    for (const p of present)
      if (!(r.items ?? []).some((it) => !it.platforms || it.platforms.includes(p)))
        errors.push(`${at} has no items for ${p}`);
  });
  return errors;
}

function tagDate(platform, version) {
  try {
    return git("log", "-1", "--format=%cs", `refs/tags/${platform}-v${version}`) || null;
  } catch {
    return null;
  }
}

const cmd = process.argv[2];
const data = load();
const errors = validate(data);
if (errors.length) fail(`${FILE} is invalid:\n  ${errors.join("\n  ")}`);

if (cmd === "validate") {
  console.log(`release-notes: ${data.releases.length} releases OK`);
} else if (cmd === "check") {
  const platform = arg("platform");
  const raw = arg("version");
  if (!PLATFORMS.includes(platform) || !raw) fail("usage: check --platform android|ios --version X.Y.Z");
  const version = baseVersion(raw);
  const entry = data.releases.find((r) => r[platform]?.version === version);
  if (!entry)
    fail(
      `no release notes for ${platform} ${version}. Add an entry to release-notes/release-notes.json ` +
        `(run \`node scripts/release-notes.mjs draft --platform ${platform}\` for what changed) before releasing.`,
    );
  const undated = data.releases.filter((r) => r[platform] && r !== entry && cmp(r[platform].version, version) < 0 && !r[platform].released);
  if (undated.length)
    fail(
      `older ${platform} releases have no date: ${undated.map((r) => r[platform].version).join(", ")}. ` +
        "Run `node scripts/release-notes.mjs sync-dates` and commit the result.",
    );
  console.log(`release-notes: ${platform} ${version} has notes`);
} else if (cmd === "draft") {
  const platform = arg("platform");
  if (!PLATFORMS.includes(platform)) fail("usage: draft --platform android|ios");
  const tags = git("tag", "--list", `${platform}-v*`, "--sort=-v:refname").split("\n").filter(Boolean);
  if (!tags.length) fail(`no ${platform}-v* tags; fetch tags first (git fetch --tags)`);
  console.log(`Merged since ${tags[0]}:\n`);
  console.log(git("log", "--reverse", "--format=- %s", `${tags[0]}..HEAD`));
} else if (cmd === "sync-dates") {
  let filled = 0;
  for (const r of data.releases)
    for (const p of PLATFORMS)
      if (r[p] && !r[p].released) {
        const d = tagDate(p, r[p].version);
        if (d) {
          r[p].released = d;
          filled++;
          console.log(`${p} ${r[p].version}: ${d}`);
        }
      }
  writeFileSync(FILE, `${JSON.stringify(data, null, 2)}\n`);
  console.log(`release-notes: filled ${filled} date(s)`);
} else {
  fail("usage: release-notes.mjs validate | check --platform P --version V | draft --platform P | sync-dates");
}
