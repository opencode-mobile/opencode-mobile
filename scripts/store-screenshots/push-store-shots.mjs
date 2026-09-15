#!/usr/bin/env node
/**
 * Push captured store screenshots to a store-shots instance.
 *
 * Usage:
 *   node scripts/store-screenshots/push-store-shots.mjs [options]
 */
import { existsSync, readFileSync } from "node:fs";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, "../..");
const COPY_PATH = join(SCRIPT_DIR, "copy.json");
const DEFAULT_DIR = "store-screenshots";
const DEFAULT_URL = "http://ivans-mac-mini-1:8790";
const APP_NAME = "OpenCode Mobile";
const HEALTH_TIMEOUT_MS = 5000;

export const SCENES = [
  { id: "home", file: "01-home.png" },
  { id: "chat-code", file: "02-chat-code.png" },
  { id: "chat-permission", file: "03-chat-permission.png" },
  { id: "changes", file: "04-changes.png" },
  { id: "settings", file: "05-settings.png" },
];

export const TARGET_MAP = {
  "ios-6.9": { storeTarget: "ios-6.9", frame: "iphone-17-pro-deep-blue", device: "fit" },
  "android-phone": { storeTarget: "play-phone", frame: "pixel-9-pro-obsidian", device: "fit" },
  "android-tab7": { storeTarget: "play-tablet", tabletFrame: "pixel-tablet-hazel", device: "bleed" },
  "android-tab10": { storeTarget: "play-tablet", tabletFrame: "pixel-tablet-hazel", device: "bleed" },
};

const CLI_TARGETS = ["all", "ios", "android-phone", "android-tab7", "android-tab10"];
const CAPTURE_TARGETS = Object.keys(TARGET_MAP);

export function defaultStoreShotsUrl() {
  const fromEnv = process.env.STORE_SHOTS_URL?.trim();
  if (fromEnv) return normalizeBaseUrl(fromEnv);
  return DEFAULT_URL;
}

export function normalizeBaseUrl(url) {
  return String(url).trim().replace(/\/+$/, "");
}

export function setNameFor(captureTarget, appearance) {
  return `${APP_NAME} · ${captureTarget} · ${appearance}`;
}

export function loadCopy(path = COPY_PATH) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function copyForScene(copy, sceneId) {
  const entry = copy?.[sceneId];
  return {
    headline: entry?.headline ?? "",
    subtitle: entry?.subtitle ?? "",
  };
}

function isBlank(value) {
  return value == null || String(value).trim() === "";
}

export function buildScreens({
  scenes,
  copy,
  existingScreens = [],
  forceCopy = false,
  pruneExtra = false,
  defaults = {},
}) {
  const deviceDefault = defaults.device ?? "fit";
  const existingById = new Map(existingScreens.map((screen) => [screen.id, screen]));
  const screens = scenes.map((scene) => {
    const fromCopy = copyForScene(copy, scene.id);
    const existing = existingById.get(scene.id);
    if (!existing) {
      return {
        id: scene.id,
        headline: fromCopy.headline,
        subtitle: fromCopy.subtitle,
        layout: "top",
        device: deviceDefault,
        banner: "left",
      };
    }
    return {
      ...existing,
      id: scene.id,
      headline: forceCopy || isBlank(existing.headline) ? fromCopy.headline : existing.headline,
      subtitle: forceCopy || isBlank(existing.subtitle) ? fromCopy.subtitle : existing.subtitle,
      layout: existing.layout ?? "top",
      device: existing.device ?? "fit",
      banner: existing.banner ?? "left",
    };
  });

  if (!pruneExtra) {
    const sceneIds = new Set(scenes.map((scene) => scene.id));
    for (const extra of existingScreens) {
      if (!sceneIds.has(extra.id)) {
        screens.push({ ...extra });
      }
    }
  }
  return screens;
}

function mappingFor(captureTarget) {
  const mapping = TARGET_MAP[captureTarget];
  if (!mapping) {
    throw new Error(
      `Unknown capture target "${captureTarget}". Expected: ${CAPTURE_TARGETS.join(", ")}`,
    );
  }
  return mapping;
}

function themeForCreate(mapping) {
  return {
    bg: "",
    gradient: "",
    filmGrain: "",
    frame: mapping.frame ?? "",
    tabletFrame: mapping.tabletFrame ?? "",
  };
}

function shotPath(dir, captureTarget, appearance, scene) {
  return join(dir, captureTarget, appearance, scene.file);
}

function missingShotFiles(dir, captureTarget, appearance, scenes) {
  return scenes
    .map((scene) => shotPath(dir, captureTarget, appearance, scene))
    .filter((filePath) => !existsSync(filePath));
}

export async function checkHealth(baseUrl) {
  const url = `${normalizeBaseUrl(baseUrl)}/healthz`;
  let response;
  try {
    response = await fetch(url, { signal: AbortSignal.timeout(HEALTH_TIMEOUT_MS) });
  } catch (error) {
    throw new Error(`store-shots is unreachable at ${url} (${error.message})`);
  }
  const text = (await response.text()).trim();
  if (!response.ok || text !== "ok") {
    throw new Error(
      `store-shots health check failed at ${url}: HTTP ${response.status} ${JSON.stringify(text)}`,
    );
  }
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  if (!response.ok) {
    const method = options.method ?? "GET";
    throw new Error(`${method} ${url} failed: HTTP ${response.status}${text ? `: ${text}` : ""}`);
  }
  return text ? JSON.parse(text) : null;
}

async function listSets(baseUrl) {
  const sets = await fetchJson(`${baseUrl}/api/sets`);
  if (!Array.isArray(sets)) {
    throw new Error(`GET ${baseUrl}/api/sets did not return an array`);
  }
  return sets;
}

function createBody({ captureTarget, appearance, screens }) {
  const mapping = mappingFor(captureTarget);
  return {
    name: setNameFor(captureTarget, appearance),
    app: APP_NAME,
    target: mapping.storeTarget,
    theme: themeForCreate(mapping),
    screens,
  };
}

async function uploadShots({ baseUrl, setId, dir, captureTarget, appearance, scenes }) {
  let uploaded = 0;
  for (const scene of scenes) {
    const filePath = shotPath(dir, captureTarget, appearance, scene);
    const body = readFileSync(filePath);
    const url = `${baseUrl}/api/sets/${setId}/shots/${encodeURIComponent(scene.id)}`;
    const response = await fetch(url, {
      method: "PUT",
      headers: { "Content-Type": "image/png" },
      body,
    });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(
        `PUT ${url} failed: HTTP ${response.status}${text ? `: ${text}` : ""}`,
      );
    }
    uploaded += 1;
  }
  return uploaded;
}

export async function pushSet({
  baseUrl,
  captureTarget,
  appearance,
  scenes,
  dir,
  copy,
  forceCopy = false,
  pruneExtra = false,
  dryRun = false,
  skipHealth = false,
  log = console.log,
}) {
  const root = normalizeBaseUrl(baseUrl);
  const name = setNameFor(captureTarget, appearance);
  const mapping = mappingFor(captureTarget);
  if (!skipHealth) {
    await checkHealth(root);
  }

  const missing = missingShotFiles(dir, captureTarget, appearance, scenes);
  if (missing.length) {
    throw new Error(
      `Missing store-screenshot PNG(s) for ${captureTarget}/${appearance}:\n${missing.map((path) => `  ${path}`).join("\n")}`,
    );
  }

  const listed = await listSets(root);
  const listedMatch = listed.find((set) => set.name === name) ?? null;
  const existing = listedMatch
    ? await fetchJson(`${root}/api/sets/${encodeURIComponent(listedMatch.id)}`)
    : null;

  const screens = buildScreens({
    scenes,
    copy,
    existingScreens: existing?.screens ?? [],
    forceCopy,
    pruneExtra,
    defaults: { device: mapping.device },
  });

  if (dryRun) {
    const verb = existing ? "would update" : "would create";
    const setId = existing?.id ?? "-";
    log(`${verb}  ${setId}  ${scenes.length} shots  ${name}`);
    return {
      setId: existing?.id ?? null,
      created: !existing,
      uploaded: 0,
      url: existing ? `${root}/?set=${existing.id}` : `${root}/`,
    };
  }

  let set;
  let created = false;
  if (!existing) {
    set = await fetchJson(`${root}/api/sets`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(createBody({ captureTarget, appearance, screens })),
    });
    created = true;
  } else {
    set = await fetchJson(`${root}/api/sets/${encodeURIComponent(existing.id)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...existing, screens }),
    });
  }

  const setId = set?.id;
  if (!setId) {
    throw new Error(`store-shots did not return an id for ${name}`);
  }

  const uploaded = await uploadShots({
    baseUrl: root,
    setId,
    dir,
    captureTarget,
    appearance,
    scenes,
  });
  log(`${created ? "created" : "updated"}  ${setId}  ${uploaded} shots  ${name}`);
  return {
    setId,
    created,
    uploaded,
    url: `${root}/?set=${setId}`,
  };
}

export async function pushAll({
  baseUrl,
  targets,
  appearances,
  scenes,
  dir,
  copy,
  forceCopy = false,
  pruneExtra = false,
  dryRun = false,
  log = console.log,
}) {
  const root = normalizeBaseUrl(baseUrl);
  await checkHealth(root);

  const results = [];
  const errors = [];
  for (const captureTarget of targets) {
    for (const appearance of appearances) {
      try {
        results.push(
          await pushSet({
            baseUrl: root,
            captureTarget,
            appearance,
            scenes,
            dir,
            copy,
            forceCopy,
            pruneExtra,
            dryRun,
            skipHealth: true,
            log,
          }),
        );
      } catch (error) {
        errors.push(error);
        log(`failed  ${captureTarget}  ${appearance}  ${error.message ?? error}`);
      }
    }
  }
  if (errors.length) {
    throw new AggregateError(errors, `${errors.length} store-shots push(es) failed`);
  }
  return results;
}

function printHelp() {
  console.log(`Push captured store screenshots to a store-shots instance.

Usage:
  node scripts/store-screenshots/push-store-shots.mjs [options]

Options:
  --url <base>          store-shots base URL
                        (default: $STORE_SHOTS_URL or ${DEFAULT_URL})
  --dir <out-dir>       Capture root (default: ${DEFAULT_DIR})
  --target <name>       ${CLI_TARGETS.join(" | ")}
                        (default: all; ios is an alias for ios-6.9)
  --appearance <name>   light | dark | both  (default: both)
  --scene <id>          Repeatable. home, chat-code, chat-permission, changes, settings
  --force-copy          Overwrite headlines/subtitles from copy.json
  --prune-extra         Drop existing screens that are not in this scene list
  --dry-run             Print planned create/update/upload; no POST or PUT
  --help, -h`);
}

function resolveTargets(target) {
  if (target === "all") return [...CAPTURE_TARGETS];
  if (target === "ios" || target === "ios-6.9") return ["ios-6.9"];
  return [target];
}

function appearancesFor(name) {
  return name === "both" ? ["light", "dark"] : [name];
}

function resolveScenes(tokens) {
  if (!tokens.length) return SCENES;
  return tokens.map((token) => {
    const scene = SCENES.find((entry) => {
      return entry.id === token || entry.file === token || entry.file.replace(/\.png$/, "") === token;
    });
    if (!scene) {
      throw new Error(`Unknown --scene "${token}". Use: ${SCENES.map((s) => s.id).join(", ")}`);
    }
    return scene;
  });
}

export function parsePushArgs(argv = process.argv.slice(2)) {
  const args = {
    url: defaultStoreShotsUrl(),
    dir: DEFAULT_DIR,
    target: "all",
    appearance: "both",
    scenes: [],
    forceCopy: false,
    pruneExtra: false,
    dryRun: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    const next = argv[i + 1];
    switch (token) {
      case "--url":
        if (!next) throw new Error("--url requires a base URL");
        args.url = next;
        i += 1;
        break;
      case "--dir":
        if (!next) throw new Error("--dir requires a directory");
        args.dir = next;
        i += 1;
        break;
      case "--target":
        args.target = next;
        i += 1;
        break;
      case "--appearance":
        args.appearance = next;
        i += 1;
        break;
      case "--scene":
        args.scenes.push(next);
        i += 1;
        break;
      case "--force-copy":
        args.forceCopy = true;
        break;
      case "--prune-extra":
        args.pruneExtra = true;
        break;
      case "--dry-run":
        args.dryRun = true;
        break;
      case "--help":
      case "-h":
        printHelp();
        process.exit(0);
        break;
      default:
        throw new Error(`Unknown argument: ${token}`);
    }
  }

  if (!CLI_TARGETS.includes(args.target) && args.target !== "ios-6.9") {
    throw new Error(`--target must be one of: ${CLI_TARGETS.join(", ")}`);
  }
  if (!["light", "dark", "both"].includes(args.appearance)) {
    throw new Error("--appearance must be light, dark, or both");
  }
  return args;
}

async function main() {
  const args = parsePushArgs();
  const dir = isAbsolute(args.dir) ? args.dir : resolve(REPO_ROOT, args.dir);
  const copy = loadCopy();
  await pushAll({
    baseUrl: args.url,
    targets: resolveTargets(args.target),
    appearances: appearancesFor(args.appearance),
    scenes: resolveScenes(args.scenes),
    dir,
    copy,
    forceCopy: args.forceCopy,
    pruneExtra: args.pruneExtra,
    dryRun: args.dryRun,
  });
}

if (
  import.meta.url === `file://${process.argv[1]}` ||
  (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1]))
) {
  main().catch((error) => {
    if (error instanceof AggregateError) {
      for (const inner of error.errors) {
        console.error(inner.message ?? inner);
      }
    }
    console.error(error.message ?? error);
    process.exit(1);
  });
}
