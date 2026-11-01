#!/usr/bin/env node
/**
 * Replace the App Store screenshot sets on the editable App Store version with
 * the rendered store slides, through the App Store Connect API.
 *
 * Usage:
 *   ASC_KEY_ID=… ASC_ISSUER_ID=… ASC_API_KEY_PATH=…/AuthKey_….p8 \
 *     node scripts/store-screenshots/upload-app-store.mjs [--dry-run] [options]
 *
 * Never submits anything for review: the API client only allows the screenshot
 * endpoints below to mutate, and refuses every other write.
 */
import { createHash, createPrivateKey, sign } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, "../..");
const DEFAULT_DIR = "store-screenshots/spectrum";
const DEFAULT_BUNDLE_ID = "dev.opencode.mobile";
const DEFAULT_LOCALE = "en-US";
const API_ROOT = "https://api.appstoreconnect.apple.com";
const TOKEN_LIFETIME_S = 20 * 60;
const TOKEN_REFRESH_S = 10 * 60;
const POLL_INTERVAL_MS = 3000;
const PROCESSING_TIMEOUT_MS = 5 * 60 * 1000;

export const MAX_SCREENSHOTS = 10;

// Portrait sizes App Store Connect accepts for each display type.
export const DISPLAY_TARGETS = [
  {
    id: "iphone",
    folder: "app-store-iphone-6.9",
    label: 'iPhone 6.9"',
    displayType: "APP_IPHONE_67",
    sizes: [
      { width: 1320, height: 2868 },
      { width: 1290, height: 2796 },
      { width: 1260, height: 2736 },
    ],
  },
  {
    id: "ipad",
    folder: "app-store-ipad-13",
    label: 'iPad 13"',
    displayType: "APP_IPAD_PRO_3GEN_129",
    sizes: [
      { width: 2064, height: 2752 },
      { width: 2048, height: 2732 },
    ],
  },
];

// Version states whose metadata (screenshots included) is still editable.
export const EDITABLE_VERSION_STATES = [
  "PREPARE_FOR_SUBMISSION",
  "DEVELOPER_REJECTED",
  "REJECTED",
  "METADATA_REJECTED",
  "INVALID_BINARY",
];

// The only writes this script may make. Anything else, review submissions
// included, is refused before it reaches the network.
const ALLOWED_WRITES = [
  { method: "POST", path: /^\/v1\/appScreenshotSets$/ },
  { method: "POST", path: /^\/v1\/appScreenshots$/ },
  { method: "PATCH", path: /^\/v1\/appScreenshots\/[^/]+$/ },
  { method: "DELETE", path: /^\/v1\/appScreenshots\/[^/]+$/ },
  { method: "PATCH", path: /^\/v1\/appScreenshotSets\/[^/]+\/relationships\/appScreenshots$/ },
];

const PNG_SIG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
// PNG colour types 4 (grey + alpha) and 6 (RGBA); App Store Connect rejects alpha.
const PNG_ALPHA_COLOR_TYPES = new Set([4, 6]);

export function readPngHeader(filePath) {
  const buf = readFileSync(filePath);
  if (buf.length < 26) return null;
  if (!buf.subarray(0, 8).equals(PNG_SIG)) return null;
  if (buf.subarray(12, 16).toString("ascii") !== "IHDR") return null;
  return {
    width: buf.readUInt32BE(16),
    height: buf.readUInt32BE(20),
    hasAlpha: PNG_ALPHA_COLOR_TYPES.has(buf[25]),
  };
}

/**
 * Read one target's folder. Returns `{ status: "skip", reason }` when there is
 * nothing to upload, so a missing folder never clears an existing set.
 */
export function planTarget(dir, target) {
  const folder = join(dir, target.folder);
  if (!existsSync(folder)) {
    return { status: "skip", reason: `no ${folder}` };
  }
  const names = readdirSync(folder)
    .filter((name) => name.toLowerCase().endsWith(".png"))
    .sort((a, b) => a.localeCompare(b, "en", { numeric: true }));
  if (!names.length) {
    return { status: "skip", reason: `no PNGs in ${folder}` };
  }

  const problems = [];
  if (names.length > MAX_SCREENSHOTS) {
    problems.push(`${names.length} PNGs; App Store Connect allows at most ${MAX_SCREENSHOTS}`);
  }
  const files = names.map((name) => {
    const path = join(folder, name);
    const header = readPngHeader(path);
    if (!header) {
      problems.push(`${name}: not a PNG`);
    } else {
      const sizeOk = target.sizes.some(
        (size) => size.width === header.width && size.height === header.height,
      );
      if (!sizeOk) {
        const accepted = target.sizes.map((size) => `${size.width}x${size.height}`).join(", ");
        problems.push(`${name}: ${header.width}x${header.height}, expected ${accepted}`);
      }
      if (header.hasAlpha) {
        problems.push(`${name}: has an alpha channel`);
      }
    }
    return { path, fileName: name, fileSize: statSync(path).size };
  });
  if (problems.length) {
    throw new Error(
      `${target.label} screenshots in ${folder} are not uploadable:\n${problems.map((p) => `  ${p}`).join("\n")}`,
    );
  }
  return { status: "ready", files };
}

function base64Url(value) {
  return Buffer.from(value).toString("base64url");
}

/** ES256 JWT for the App Store Connect API. */
export function createAuthToken({ keyId, issuerId, privateKey, now = Date.now() }) {
  const iat = Math.floor(now / 1000);
  const header = base64Url(JSON.stringify({ alg: "ES256", kid: keyId, typ: "JWT" }));
  const payload = base64Url(
    JSON.stringify({ iss: issuerId, iat, exp: iat + TOKEN_LIFETIME_S, aud: "appstoreconnect-v1" }),
  );
  const signingInput = `${header}.${payload}`;
  const signature = sign("sha256", Buffer.from(signingInput), {
    key: createPrivateKey(privateKey),
    dsaEncoding: "ieee-p1363",
  });
  return `${signingInput}.${signature.toString("base64url")}`;
}

export function tokenProvider(credentials, clock = Date.now) {
  let cached = null;
  let issuedAt = 0;
  return () => {
    const now = clock();
    if (!cached || now - issuedAt > TOKEN_REFRESH_S * 1000) {
      cached = createAuthToken({ ...credentials, now });
      issuedAt = now;
    }
    return cached;
  };
}

export function isAllowedWrite(method, path) {
  const pathname = path.split("?")[0];
  return ALLOWED_WRITES.some((rule) => rule.method === method && rule.path.test(pathname));
}

/**
 * Authenticated App Store Connect client. In dry-run mode every write throws,
 * so a planning bug cannot change the listing.
 */
export function createAscClient({ token, dryRun = false, fetchImpl = fetch, apiRoot = API_ROOT }) {
  async function request(method, path, body) {
    if (method !== "GET") {
      if (dryRun) {
        throw new Error(`Dry run: refusing ${method} ${path}`);
      }
      if (!isAllowedWrite(method, path)) {
        throw new Error(`Refusing ${method} ${path}: only screenshot writes are allowed`);
      }
    }
    const response = await fetchImpl(`${apiRoot}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token()}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await response.text();
    if (!response.ok) {
      const hint =
        response.status === 403
          ? " (the API key needs the App Manager or Admin role to change screenshots)"
          : response.status === 401
            ? " (check ASC_KEY_ID, ASC_ISSUER_ID and the .p8 key)"
            : "";
      throw new Error(`${method} ${path} failed: HTTP ${response.status}${hint}${text ? `: ${text}` : ""}`);
    }
    return text ? JSON.parse(text) : null;
  }
  return {
    get: (path) => request("GET", path),
    post: (path, body) => request("POST", path, body),
    patch: (path, body) => request("PATCH", path, body),
    delete: (path) => request("DELETE", path),
  };
}

function versionState(version) {
  return version.attributes.appVersionState ?? version.attributes.appStoreState;
}

export function pickEditableVersion(versions, { versionString } = {}) {
  const editable = versions.filter((version) => EDITABLE_VERSION_STATES.includes(versionState(version)));
  const matches = versionString
    ? editable.filter((version) => version.attributes.versionString === versionString)
    : editable;
  if (matches.length === 1) return matches[0];

  const listed = versions
    .map((version) => `  ${version.attributes.versionString} ${versionState(version)}`)
    .join("\n");
  if (!matches.length) {
    const wanted = versionString ? `editable version ${versionString}` : "editable version";
    throw new Error(
      `No ${wanted}. Create the version in App Store Connect first.${listed ? `\nVersions:\n${listed}` : ""}`,
    );
  }
  throw new Error(`Several editable versions; pass --version.\nVersions:\n${listed}`);
}

async function findApp(client, bundleId) {
  const response = await client.get(
    `/v1/apps?filter[bundleId]=${encodeURIComponent(bundleId)}&fields[apps]=bundleId,name`,
  );
  const app = response.data.find((entry) => entry.attributes.bundleId === bundleId);
  if (!app) throw new Error(`No App Store Connect app with bundle ID ${bundleId}`);
  return app;
}

async function findLocalization(client, versionId, locale) {
  const response = await client.get(
    `/v1/appStoreVersions/${versionId}/appStoreVersionLocalizations?limit=50`,
  );
  const localization = response.data.find((entry) => entry.attributes.locale === locale);
  if (!localization) {
    const locales = response.data.map((entry) => entry.attributes.locale).join(", ");
    throw new Error(`The version has no ${locale} localization (has: ${locales || "none"})`);
  }
  return localization;
}

async function findScreenshotSet(client, localizationId, displayType) {
  const response = await client.get(
    `/v1/appStoreVersionLocalizations/${localizationId}/appScreenshotSets?filter[screenshotDisplayType]=${displayType}`,
  );
  return response.data.find((set) => set.attributes.screenshotDisplayType === displayType) ?? null;
}

async function listScreenshots(client, setId) {
  const response = await client.get(`/v1/appScreenshotSets/${setId}/appScreenshots?limit=50`);
  return response.data;
}

async function uploadParts(operations, bytes, fetchImpl) {
  for (const operation of operations) {
    const headers = Object.fromEntries(
      (operation.requestHeaders ?? []).map((header) => [header.name, header.value]),
    );
    // Upload URLs are pre-signed; the JWT must not be sent to them.
    const response = await fetchImpl(operation.url, {
      method: operation.method,
      headers,
      body: bytes.subarray(operation.offset, operation.offset + operation.length),
    });
    if (!response.ok) {
      throw new Error(`Upload part at offset ${operation.offset} failed: HTTP ${response.status}`);
    }
  }
}

async function uploadScreenshot(client, setId, file, fetchImpl) {
  const bytes = readFileSync(file.path);
  const reserved = await client.post("/v1/appScreenshots", {
    data: {
      type: "appScreenshots",
      attributes: { fileName: file.fileName, fileSize: bytes.length },
      relationships: { appScreenshotSet: { data: { type: "appScreenshotSets", id: setId } } },
    },
  });
  const id = reserved.data.id;
  await uploadParts(reserved.data.attributes.uploadOperations ?? [], bytes, fetchImpl);
  await client.patch(`/v1/appScreenshots/${id}`, {
    data: {
      type: "appScreenshots",
      id,
      attributes: {
        uploaded: true,
        sourceFileChecksum: createHash("md5").update(bytes).digest("hex"),
      },
    },
  });
  return id;
}

async function waitForProcessing(client, ids, { pollIntervalMs, timeoutMs, sleep }) {
  const pending = new Map(ids.map((id) => [id, null]));
  const deadline = Date.now() + timeoutMs;
  while (pending.size) {
    for (const id of [...pending.keys()]) {
      const response = await client.get(`/v1/appScreenshots/${id}`);
      const { fileName, assetDeliveryState } = response.data.attributes;
      const state = assetDeliveryState?.state;
      if (state === "COMPLETE") {
        pending.delete(id);
      } else if (state === "FAILED") {
        const errors = (assetDeliveryState.errors ?? [])
          .map((error) => error.description ?? error.code)
          .join("; ");
        throw new Error(`App Store Connect rejected ${fileName}: ${errors || "processing failed"}`);
      } else {
        pending.set(id, fileName);
      }
    }
    if (!pending.size) break;
    if (Date.now() > deadline) {
      throw new Error(`Still processing after ${timeoutMs / 1000}s: ${[...pending.values()].join(", ")}`);
    }
    await sleep(pollIntervalMs);
  }
}

/**
 * Replace one display type's screenshots: delete the old ones, upload the new
 * ones in file order, pin that order, and wait until processing finishes.
 */
export async function replaceScreenshotSet({
  client,
  localizationId,
  target,
  files,
  dryRun = false,
  fetchImpl = fetch,
  pollIntervalMs = POLL_INTERVAL_MS,
  timeoutMs = PROCESSING_TIMEOUT_MS,
  sleep = (ms) => new Promise((done) => setTimeout(done, ms)),
  log = console.log,
}) {
  const existingSet = await findScreenshotSet(client, localizationId, target.displayType);
  const existing = existingSet ? await listScreenshots(client, existingSet.id) : [];

  log(`${target.label} (${target.displayType})`);
  for (const screenshot of existing) {
    log(`  ${dryRun ? "would delete" : "delete"}  ${screenshot.attributes.fileName}`);
  }
  for (const file of files) {
    log(`  ${dryRun ? "would upload" : "upload"}  ${file.fileName}`);
  }
  if (dryRun) {
    return { deleted: 0, uploaded: 0 };
  }

  const set =
    existingSet ??
    (
      await client.post("/v1/appScreenshotSets", {
        data: {
          type: "appScreenshotSets",
          attributes: { screenshotDisplayType: target.displayType },
          relationships: {
            appStoreVersionLocalization: {
              data: { type: "appStoreVersionLocalizations", id: localizationId },
            },
          },
        },
      })
    ).data;

  for (const screenshot of existing) {
    await client.delete(`/v1/appScreenshots/${screenshot.id}`);
  }
  const ids = [];
  for (const file of files) {
    ids.push(await uploadScreenshot(client, set.id, file, fetchImpl));
  }
  await client.patch(`/v1/appScreenshotSets/${set.id}/relationships/appScreenshots`, {
    data: ids.map((id) => ({ type: "appScreenshots", id })),
  });
  await waitForProcessing(client, ids, { pollIntervalMs, timeoutMs, sleep });
  log(`  done: ${existing.length} deleted, ${ids.length} uploaded`);
  return { deleted: existing.length, uploaded: ids.length };
}

export async function uploadAll({
  client,
  dir,
  targets,
  bundleId = DEFAULT_BUNDLE_ID,
  locale = DEFAULT_LOCALE,
  versionString,
  dryRun = false,
  fetchImpl = fetch,
  log = console.log,
  ...timing
}) {
  // Validate every folder before touching App Store Connect.
  const plans = targets.map((target) => ({ target, plan: planTarget(dir, target) }));
  const ready = plans.filter(({ plan }) => plan.status === "ready");
  for (const { target, plan } of plans) {
    if (plan.status === "skip") log(`skip  ${target.label}: ${plan.reason}`);
  }
  if (!ready.length) {
    throw new Error(`Nothing to upload from ${dir}`);
  }

  const app = await findApp(client, bundleId);
  const versions = await client.get(
    `/v1/apps/${app.id}/appStoreVersions?filter[platform]=IOS&limit=50`,
  );
  const version = pickEditableVersion(versions.data, { versionString });
  const localization = await findLocalization(client, version.id, locale);
  log(
    `${dryRun ? "Dry run: " : ""}${app.attributes.name} ${version.attributes.versionString} (${versionState(version)}), ${locale}`,
  );

  const results = [];
  for (const { target, plan } of ready) {
    results.push(
      await replaceScreenshotSet({
        client,
        localizationId: localization.id,
        target,
        files: plan.files,
        dryRun,
        fetchImpl,
        log,
        ...timing,
      }),
    );
  }
  if (!dryRun) {
    log("Screenshots replaced. Nothing was submitted for review.");
  }
  return results;
}

function printHelp() {
  console.log(`Replace App Store screenshots on the editable version (never submits for review).

Usage:
  node scripts/store-screenshots/upload-app-store.mjs [options]

Environment:
  ASC_KEY_ID            App Store Connect API key ID (key needs App Manager or Admin)
  ASC_ISSUER_ID         Issuer ID
  ASC_API_KEY_PATH      Path to the .p8 key
                        (default: ~/.appstoreconnect/private_keys/AuthKey_<ASC_KEY_ID>.p8)

Options:
  --dir <dir>           Rendered slides root (default: ${DEFAULT_DIR});
                        reads ${DISPLAY_TARGETS.map((t) => `${t.folder}/`).join(" and ")}
  --target <name>       all | ${DISPLAY_TARGETS.map((t) => t.id).join(" | ")}  (default: all)
  --locale <locale>     Version localization (default: ${DEFAULT_LOCALE})
  --version <x.y.z>     Pick this editable version when there are several
  --bundle-id <id>      App bundle ID (default: ${DEFAULT_BUNDLE_ID})
  --dry-run             List what would be deleted and uploaded; no writes
  --help, -h`);
}

export function parseUploadArgs(argv = process.argv.slice(2)) {
  const args = {
    dir: DEFAULT_DIR,
    target: "all",
    locale: DEFAULT_LOCALE,
    versionString: undefined,
    bundleId: DEFAULT_BUNDLE_ID,
    dryRun: false,
    help: false,
  };
  const valueFor = (token, next) => {
    if (!next || next.startsWith("--")) throw new Error(`${token} requires a value`);
    return next;
  };
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    const next = argv[i + 1];
    switch (token) {
      case "--dir":
        args.dir = valueFor(token, next);
        i += 1;
        break;
      case "--target":
        args.target = valueFor(token, next);
        i += 1;
        break;
      case "--locale":
        args.locale = valueFor(token, next);
        i += 1;
        break;
      case "--version":
        args.versionString = valueFor(token, next);
        i += 1;
        break;
      case "--bundle-id":
        args.bundleId = valueFor(token, next);
        i += 1;
        break;
      case "--dry-run":
        args.dryRun = true;
        break;
      case "--help":
      case "-h":
        args.help = true;
        break;
      default:
        throw new Error(`Unknown argument: ${token}`);
    }
  }
  const targetIds = DISPLAY_TARGETS.map((target) => target.id);
  if (args.target !== "all" && !targetIds.includes(args.target)) {
    throw new Error(`--target must be one of: all, ${targetIds.join(", ")}`);
  }
  return args;
}

export function readCredentials(env = process.env) {
  const keyId = env.ASC_KEY_ID?.trim();
  const issuerId = env.ASC_ISSUER_ID?.trim();
  if (!keyId || !issuerId) {
    throw new Error("Set ASC_KEY_ID and ASC_ISSUER_ID (and ASC_API_KEY_PATH for the .p8 key)");
  }
  const keyPath =
    env.ASC_API_KEY_PATH?.trim() ||
    join(homedir(), ".appstoreconnect", "private_keys", `AuthKey_${keyId}.p8`);
  if (!existsSync(keyPath)) {
    throw new Error(`No API key at ${keyPath}; set ASC_API_KEY_PATH`);
  }
  return { keyId, issuerId, privateKey: readFileSync(keyPath, "utf8") };
}

async function main() {
  const args = parseUploadArgs();
  if (args.help) {
    printHelp();
    return;
  }
  const dir = isAbsolute(args.dir) ? args.dir : resolve(REPO_ROOT, args.dir);
  const client = createAscClient({ token: tokenProvider(readCredentials()), dryRun: args.dryRun });
  await uploadAll({
    client,
    dir,
    targets: DISPLAY_TARGETS.filter((target) => args.target === "all" || target.id === args.target),
    bundleId: args.bundleId,
    locale: args.locale,
    versionString: args.versionString,
    dryRun: args.dryRun,
  });
}

if (
  import.meta.url === `file://${process.argv[1]}` ||
  (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1]))
) {
  main().catch((error) => {
    console.error(error.message ?? error);
    process.exit(1);
  });
}
