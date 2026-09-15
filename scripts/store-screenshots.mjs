#!/usr/bin/env node
/**
 * Orchestrate store-screenshot captures against the fixture server.
 *
 * Starts the fixture server, boots the iPhone 16 Pro Max simulator and the
 * store Android AVDs, runs the platform UI tests, pulls PNGs, and checks
 * dimensions (IHDR) plus Play's long-side ≤ 2× short-side rule.
 */
import { spawn, execFileSync } from "node:child_process";
import {
  closeSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  openSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { homedir, tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { deflateSync } from "node:zlib";
import { IOS_EXPECTED, IOS_OUTPUT_TARGET, readPngSize } from "./store-screenshots/png.mjs";
import { writeGallery } from "./store-screenshots/write-gallery.mjs";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const FIXTURE_SERVER = join(REPO_ROOT, "scripts/store-screenshots/fixture-server.mjs");
const AVDS_JSON = join(REPO_ROOT, "scripts/store-screenshots/avds.json");
const IOS_PROJECT_DIR = join(REPO_ROOT, "ios/OpenCode Mobile");
const ANDROID_DIR = join(REPO_ROOT, "android");
const IOS_SIM_NAME = "iPhone 16 Pro Max";
const SERVER_USER = "opencode";
const SERVER_PASSWORD = "store-screenshots";
const DEVICE_SCREENSHOT_DIR = "/sdcard/Android/data/dev.opencode.mobile/files/store-screenshots";
const ANDROID_TEST_CLASS = "dev.opencode.mobile.storescreenshots.StoreScreenshotTest";
const IOS_TEST_CLASS = "OpenCode MobileUITests/StoreScreenshotUITests";
const HEALTH_TIMEOUT_MS = 20_000;
const BOOT_TIMEOUT_MS = 240_000;
const STORE_AVD_NAMES = new Set(["store_phone", "store_tab7", "store_tab10"]);

const SCENES = [
  { id: "home", file: "01-home.png", ios: "testHome", android: "home" },
  { id: "chat-code", file: "02-chat-code.png", ios: "testChatCode", android: "chatCode" },
  { id: "chat-permission", file: "03-chat-permission.png", ios: "testChatPermission", android: "chatPermission" },
  { id: "changes", file: "04-changes.png", ios: "testChanges", android: "changes" },
  { id: "settings", file: "05-settings.png", ios: "testSettings", android: "settings" },
];

const CLI_TARGETS = ["all", "ios", "android-phone", "android-tab7", "android-tab10"];

function printHelp() {
  console.log(`Capture store screenshots from the iOS and Android apps against the fixture server.

Usage:
  node scripts/store-screenshots.mjs [options]

Options:
  --target <name>       ${CLI_TARGETS.join(" | ")}
                        (default: all)
  --appearance <name>   light | dark | both  (default: both)
  --scene <name>        Repeatable. home, chat-code, chat-permission, changes, settings
  --skip-server         Do not start the fixture server
  --server-port <n>     Fixture server port (default: 4399)
  --keep-emulator       Leave the Android emulator running after the run
  --headless            Start the emulator with -no-window
  --out <dir>           Output root (default: store-screenshots)
  --no-gallery          Skip writing store-screenshots/index.html
  --avd-only            Create, boot, and configure Android AVDs; report wm size/density
  --sim-only            Boot the iPhone 16 Pro Max simulator and apply status-bar overrides
  --help, -h`);
}

function parseArgs(argv) {
  const args = {
    target: "all",
    appearance: "both",
    scenes: [],
    skipServer: false,
    serverPort: 4399,
    keepEmulator: false,
    headless: false,
    out: "store-screenshots",
    avdOnly: false,
    simOnly: false,
    selfTest: false,
    noGallery: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    const next = argv[i + 1];
    switch (token) {
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
      case "--skip-server":
        args.skipServer = true;
        break;
      case "--server-port":
        args.serverPort = Number(next);
        i += 1;
        break;
      case "--keep-emulator":
        args.keepEmulator = true;
        break;
      case "--headless":
        args.headless = true;
        break;
      case "--out":
        args.out = next;
        i += 1;
        break;
      case "--avd-only":
        args.avdOnly = true;
        break;
      case "--sim-only":
        args.simOnly = true;
        break;
      case "--self-test":
        args.selfTest = true;
        break;
      case "--no-gallery":
        args.noGallery = true;
        break;
      case "--help":
      case "-h":
        printHelp();
        process.exit(0);
        break;
      default:
        if (token.startsWith("-")) {
          console.error(`Unknown flag: ${token}`);
          printHelp();
          process.exit(1);
        }
        console.error(`Unexpected argument: ${token}`);
        printHelp();
        process.exit(1);
    }
  }

  if (!CLI_TARGETS.includes(args.target) && args.target !== "ios-6.9") {
    console.error(`--target must be one of: ${CLI_TARGETS.join(", ")}`);
    process.exit(1);
  }
  if (!["light", "dark", "both"].includes(args.appearance)) {
    console.error("--appearance must be light, dark, or both");
    process.exit(1);
  }
  if (!Number.isInteger(args.serverPort) || args.serverPort <= 0) {
    console.error("--server-port must be a positive integer");
    process.exit(1);
  }
  return args;
}

function fail(message) {
  throw new Error(message);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function basicAuthHeader(username, password) {
  return `Basic ${Buffer.from(`${username}:${password}`, "utf8").toString("base64")}`;
}

function loadAvds() {
  return JSON.parse(readFileSync(AVDS_JSON, "utf8"));
}

function appearancesFor(name) {
  return name === "both" ? ["light", "dark"] : [name];
}

function resolveRunTargets(target) {
  if (target === "all") return ["ios", "android-phone", "android-tab7", "android-tab10"];
  if (target === "ios" || target === "ios-6.9") return ["ios"];
  return [target];
}

function outputTargetName(target) {
  return target === "ios" ? IOS_OUTPUT_TARGET : target;
}

function normalizeSceneToken(raw) {
  return String(raw)
    .trim()
    .replace(/^test/i, "")
    .replace(/([a-z])([A-Z])/g, "$1-$2")
    .replaceAll("_", "-")
    .toLowerCase();
}

function resolveScenes(tokens) {
  if (!tokens.length) return SCENES;
  return tokens.map((token) => {
    const normalized = normalizeSceneToken(token);
    const scene = SCENES.find((entry) => {
      return (
        entry.id === normalized ||
        entry.file === token ||
        entry.file.replace(/\.png$/, "") === token ||
        entry.android.toLowerCase() === String(token).toLowerCase() ||
        entry.ios.toLowerCase() === String(token).toLowerCase()
      );
    });
    if (!scene) {
      fail(`Unknown --scene "${token}". Use: ${SCENES.map((s) => s.id).join(", ")}`);
    }
    return scene;
  });
}

function crc32(buf) {
  let crc = 0xffffffff;
  for (const byte of buf) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1;
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const typeBuf = Buffer.from(type, "ascii");
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])));
  return Buffer.concat([length, typeBuf, data, crc]);
}

function encodeMinimalPng(width, height) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  const raw = Buffer.alloc((1 + width * 3) * height);
  const idat = deflateSync(raw);
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk("IHDR", ihdr),
    pngChunk("IDAT", idat),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

function inspectPng(filePath, { expectedWidth, expectedHeight, playAspectRule }) {
  if (!existsSync(filePath)) {
    return { status: "MISSING", label: "-", detail: "" };
  }
  const size = readPngSize(filePath);
  if (!size) {
    return { status: "MISMATCH", label: "-", detail: "not a PNG / missing IHDR" };
  }
  const { width, height } = size;
  const label = `${width}x${height}`;
  if (width !== expectedWidth || height !== expectedHeight) {
    return {
      status: "MISMATCH",
      label,
      detail: `expected ${expectedWidth}x${expectedHeight}`,
    };
  }
  if (playAspectRule) {
    const longSide = Math.max(width, height);
    const shortSide = Math.min(width, height);
    if (longSide > 2 * shortSide) {
      if (playAspectRule === "warn") {
        return {
          status: "WARN",
          label,
          detail: "long side > 2x short; frame before Play upload",
        };
      }
      return {
        status: "MISMATCH",
        label,
        detail: `long side ${longSide} > 2 × short ${shortSide}`,
      };
    }
  }
  return { status: "OK", label, detail: "" };
}

function formatValidationRow(target, appearance, file, result) {
  const dim = result.label.padEnd(10);
  const extra = result.detail ? ` (${result.detail})` : "";
  return `${target.padEnd(16)} ${appearance.padEnd(6)} ${file.padEnd(24)} ${dim} ${result.status}${extra}`;
}

function runSelfTest() {
  const dir = join(tmpdir(), `store-screenshots-self-test-${process.pid}`);
  mkdirSync(dir, { recursive: true });
  const okPath = join(dir, "ok-1080x1920.png");
  const badPath = join(dir, "bad-1080x2400.png");
  writeFileSync(okPath, encodeMinimalPng(1080, 1920));
  writeFileSync(badPath, encodeMinimalPng(1080, 2400));

  const ok = inspectPng(okPath, { expectedWidth: 1080, expectedHeight: 1920, playAspectRule: "error" });
  const bad = inspectPng(badPath, { expectedWidth: 1080, expectedHeight: 2400, playAspectRule: "error" });
  console.log(formatValidationRow("android-phone", "light", "ok-1080x1920.png", ok));
  console.log(formatValidationRow("android-phone", "light", "bad-1080x2400.png", bad));

  rmSync(dir, { recursive: true, force: true });
  if (ok.status !== "OK" || bad.status !== "MISMATCH" || !bad.detail.includes("long side")) {
    fail("self-test: validator did not accept 1080x1920 and reject 1080x2400 on the aspect rule");
  }
  console.log("self-test passed");
}

function sdkRoot() {
  return (
    process.env.ANDROID_HOME ||
    process.env.ANDROID_SDK_ROOT ||
    join(homedir(), "Library/Android/sdk")
  );
}

function sdkTools(sdk) {
  const cmdline = join(sdk, "cmdline-tools/latest/bin");
  return {
    sdk,
    avdmanager: join(cmdline, "avdmanager"),
    sdkmanager: join(cmdline, "sdkmanager"),
    emulator: join(sdk, "emulator/emulator"),
    adb: join(sdk, "platform-tools/adb"),
  };
}

function hostArch() {
  return process.arch === "arm64" ? "arm64-v8a" : "x86_64";
}

function tagRank(tag) {
  if (tag === "google_apis") return 0;
  if (tag === "google_apis_playstore") return 1;
  return 2;
}

function parseApiLevel(apiDir) {
  const match = apiDir.match(/^android-(\d+)/);
  return match ? Number(match[1]) : 0;
}

function listInstalledImages(sdk, arch) {
  const root = join(sdk, "system-images");
  if (!existsSync(root)) return [];
  const found = [];
  for (const apiDir of readdirSafe(root)) {
    const apiLevel = parseApiLevel(apiDir);
    if (apiLevel < 34) continue;
    const apiPath = join(root, apiDir);
    for (const tag of readdirSafe(apiPath)) {
      const archPath = join(apiPath, tag, arch);
      if (!existsSync(archPath)) continue;
      found.push({
        apiDir,
        apiLevel,
        tag,
        arch,
        path: archPath,
        package: `system-images;${apiDir};${tag};${arch}`,
        rank: tagRank(tag),
      });
    }
  }
  found.sort((a, b) => {
    if (a.rank !== b.rank) return a.rank - b.rank;
    if (a.apiLevel !== b.apiLevel) return b.apiLevel - a.apiLevel;
    return a.package.localeCompare(b.package);
  });
  return found;
}

function readdirSafe(dir) {
  try {
    return readdirSync(dir);
  } catch {
    return [];
  }
}

function pickSystemImage(sdk) {
  const arch = hostArch();
  const images = listInstalledImages(sdk, arch);
  if (!images.length) {
    const pkg = `system-images;android-35;google_apis;${arch}`;
    fail(
      `No installed system image for ${arch} with API 34+.\n` +
        `Install one with:\n  "${join(sdk, "cmdline-tools/latest/bin/sdkmanager")}" "${pkg}"`,
    );
  }
  const chosen = images[0];
  console.log(`system image: ${chosen.package}`);
  return chosen;
}

function avdHome() {
  return process.env.ANDROID_AVD_HOME || join(homedir(), ".android/avd");
}

function avdConfigPath(avdName) {
  return join(avdHome(), `${avdName}.avd`, "config.ini");
}

function avdExists(avdName) {
  return existsSync(join(avdHome(), `${avdName}.ini`)) && existsSync(avdConfigPath(avdName));
}

function execOk(file, argv, opts = {}) {
  return execFileSync(file, argv, {
    encoding: "utf8",
    stdio: ["pipe", "pipe", "pipe"],
    ...opts,
  });
}

function createAvd(tools, avdName, imagePackage, device) {
  console.log(`creating AVD ${avdName} (${imagePackage}, device ${device})`);
  try {
    execOk(
      tools.avdmanager,
      ["create", "avd", "-n", avdName, "-k", imagePackage, "-d", device, "--force"],
      {
        input: "no\n",
        env: { ...process.env, ANDROID_HOME: tools.sdk, ANDROID_SDK_ROOT: tools.sdk },
      },
    );
  } catch (error) {
    const stderr = error.stderr?.toString?.() ?? "";
    const stdout = error.stdout?.toString?.() ?? "";
    throw new Error(`avdmanager failed for ${avdName}:\n${stdout}${stderr}`);
  }
}

function patchAvdConfig(avdName, spec) {
  const path = avdConfigPath(avdName);
  if (!existsSync(path)) {
    throw new Error(`missing AVD config: ${path}`);
  }
  const overrides = {
    "hw.lcd.width": String(spec.width),
    "hw.lcd.height": String(spec.height),
    "hw.lcd.density": String(spec.density),
    "skin.name": `${spec.width}x${spec.height}`,
    "skin.path": "_no_skin",
    "hw.keyboard": "yes",
    "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "auto",
    "disk.dataPartition.size": "4G",
    "hw.mainKeys": "no",
    "hw.initialOrientation": "portrait",
    "showDeviceFrame": "no",
    "skin.dynamic": "no",
  };
  const drop = new Set(["hw.device.hash2"]);
  const seen = new Set();
  const lines = readFileSync(path, "utf8").split(/\r?\n/);
  const next = [];
  for (const line of lines) {
    if (!line || line.startsWith("#")) {
      next.push(line);
      continue;
    }
    const eq = line.indexOf("=");
    if (eq < 0) {
      next.push(line);
      continue;
    }
    const key = line.slice(0, eq).trim();
    if (drop.has(key)) continue;
    if (Object.hasOwn(overrides, key)) {
      next.push(`${key}=${overrides[key]}`);
      seen.add(key);
      continue;
    }
    next.push(line);
  }
  for (const [key, value] of Object.entries(overrides)) {
    if (!seen.has(key)) next.push(`${key}=${value}`);
  }
  writeFileSync(path, `${next.filter((line, i, arr) => line !== "" || arr[i - 1] !== "").join("\n").replace(/\n+$/, "")}\n`);
}

function readAvdIni(avdName) {
  const path = avdConfigPath(avdName);
  const values = {};
  if (!existsSync(path)) return values;
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const eq = line.indexOf("=");
    if (eq < 0 || line.startsWith("#")) continue;
    values[line.slice(0, eq).trim()] = line.slice(eq + 1).trim();
  }
  return values;
}

function avdConfigDrifted(avdName, spec) {
  const ini = readAvdIni(avdName);
  return (
    ini["hw.lcd.width"] !== String(spec.width) ||
    ini["hw.lcd.height"] !== String(spec.height) ||
    ini["hw.lcd.density"] !== String(spec.density) ||
    ini["hw.device.name"] !== spec.device
  );
}

function deleteAvd(tools, avdName) {
  try {
    execOk(tools.avdmanager, ["delete", "avd", "-n", avdName], {
      env: { ...process.env, ANDROID_HOME: tools.sdk, ANDROID_SDK_ROOT: tools.sdk },
    });
  } catch (error) {
    const stderr = error.stderr?.toString?.() ?? "";
    const stdout = error.stdout?.toString?.() ?? "";
    throw new Error(`avdmanager delete failed for ${avdName}:\n${stdout}${stderr}`);
  }
}

function ensureAvd(tools, image, spec) {
  if (avdExists(spec.avd) && avdConfigDrifted(spec.avd, spec)) {
    console.log(`recreating AVD ${spec.avd} (config changed)`);
    deleteAvd(tools, spec.avd);
    createAvd(tools, spec.avd, image.package, spec.device);
  } else if (!avdExists(spec.avd)) {
    createAvd(tools, spec.avd, image.package, spec.device);
  }
  patchAvdConfig(spec.avd, spec);
}

function adb(tools, argv, serial) {
  const prefix = serial ? ["-s", serial] : [];
  return execOk(tools.adb, [...prefix, ...argv], {
    env: { ...process.env, ANDROID_HOME: tools.sdk, ANDROID_SDK_ROOT: tools.sdk },
  });
}

function listAdbDevices(tools) {
  const text = adb(tools, ["devices"]);
  const rows = [];
  for (const line of text.split("\n")) {
    const match = line.match(/^(\S+)\s+(\S+)/);
    if (!match || match[1] === "List") continue;
    rows.push({ serial: match[1], state: match[2] });
  }
  return rows;
}

function emulatorAvdName(tools, serial) {
  try {
    const out = adb(tools, ["emu", "avd", "name"], serial);
    return out.trim().split("\n")[0].trim();
  } catch {
    return null;
  }
}

function killEmulatorSerial(tools, serial) {
  try {
    adb(tools, ["emu", "kill"], serial);
  } catch {
    // already gone
  }
}

function killLeftoverStoreEmulators(tools) {
  for (const device of listAdbDevices(tools)) {
    if (!device.serial.startsWith("emulator-")) continue;
    const name = emulatorAvdName(tools, device.serial);
    if (name && STORE_AVD_NAMES.has(name)) {
      console.log(`killing leftover emulator ${device.serial} (${name})`);
      killEmulatorSerial(tools, device.serial);
    }
  }
}

function assertSingleDeviceOrNone(tools, { allowNone = false } = {}) {
  const devices = listAdbDevices(tools);
  if (devices.length === 0 && allowNone) return;
  if (devices.length <= 1) return;
  const labels = devices.map((device) => {
    const name = device.serial.startsWith("emulator-")
      ? emulatorAvdName(tools, device.serial) ?? "unknown-avd"
      : "device";
    return `${device.serial} (${name})`;
  });
  fail(
    `Refusing to run: more than one device is attached (${labels.join(", ")}).\n` +
      "AGP connectedDebugAndroidTest runs on every connected device and does not honor ANDROID_SERIAL.\n" +
      "Quit other emulators and disconnect extra devices, then retry.",
  );
}

function tailFile(path, lines) {
  if (!existsSync(path)) return "(no log file)";
  const text = readFileSync(path, "utf8");
  return text.split("\n").slice(-lines).join("\n");
}

function spawnLogged(command, argv, { cwd, env, logPath, detached = false }) {
  mkdirSync(dirname(logPath), { recursive: true });
  const fd = openSync(logPath, "w");
  const child = spawn(command, argv, {
    cwd,
    env,
    detached,
    stdio: ["ignore", fd, fd],
  });
  child.on("close", () => {
    try {
      closeSync(fd);
    } catch {
      // already closed
    }
  });
  return child;
}

function waitForExit(child) {
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code, signal) => resolve({ code, signal }));
  });
}

async function runLogged(command, argv, { cwd, env, logPath, label }) {
  console.log(`${label} → ${logPath}`);
  const started = Date.now();
  const child = spawnLogged(command, argv, { cwd, env, logPath });
  const { code } = await waitForExit(child);
  const secs = ((Date.now() - started) / 1000).toFixed(1);
  if (code === 0) {
    console.log(`${label} ok (${secs}s)`);
    return;
  }
  console.error(`${label} failed exit=${code} (${secs}s)`);
  console.error(tailFile(logPath, 40));
  throw new Error(`${label} exited ${code}`);
}

async function waitForHealth(port, timeoutMs) {
  const url = `http://127.0.0.1:${port}/api/health`;
  const headers = { Authorization: basicAuthHeader(SERVER_USER, SERVER_PASSWORD) };
  const deadline = Date.now() + timeoutMs;
  let lastError = "no attempt";
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { headers, signal: AbortSignal.timeout(1500) });
      if (response.ok) return;
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = error.message;
    }
    await sleep(250);
  }
  throw new Error(`fixture server did not become healthy at ${url} within ${timeoutMs / 1000}s (${lastError})`);
}

function startFixtureServer(port, logPath) {
  if (!existsSync(FIXTURE_SERVER)) {
    throw new Error(`fixture server missing: ${FIXTURE_SERVER}`);
  }
  console.log(`starting fixture server on :${port}`);
  return spawnLogged(process.execPath, [FIXTURE_SERVER, "--port", String(port), "--host", "0.0.0.0"], {
    cwd: REPO_ROOT,
    logPath,
  });
}

function killProcess(child) {
  if (!child || child.killed || child.exitCode != null) return;
  try {
    child.kill("SIGTERM");
  } catch {
    // ignore
  }
}

function parseSimctlDevices(jsonText) {
  const data = JSON.parse(jsonText);
  const found = [];
  for (const [runtime, devices] of Object.entries(data.devices ?? {})) {
    const ios = runtime.replace(/^com\.apple\.CoreSimulator\.SimRuntime\.iOS-/, "iOS ").replaceAll("-", ".");
    for (const device of devices) {
      found.push({
        name: device.name,
        udid: device.udid,
        state: device.state,
        runtime,
        ios,
      });
    }
  }
  return found;
}

function findIosSimulator() {
  const json = execOk("xcrun", ["simctl", "list", "devices", "available", "-j"]);
  const devices = parseSimctlDevices(json);
  const matches = devices.filter((device) => device.name === IOS_SIM_NAME);
  if (!matches.length) {
    const iphones = devices.filter((device) => device.name.includes("iPhone"));
    const listing = iphones.length
      ? iphones.map((d) => `  ${d.name} (${d.ios}) ${d.udid}`).join("\n")
      : "  (none)";
    fail(`No available simulator named "${IOS_SIM_NAME}". Available iPhones:\n${listing}`);
  }
  matches.sort((a, b) => a.runtime.localeCompare(b.runtime));
  return matches[matches.length - 1];
}

function bootIosSimulator(udid, state) {
  if (state !== "Booted") {
    console.log(`booting iOS simulator ${udid}`);
    execOk("xcrun", ["simctl", "boot", udid]);
  }
  execOk("xcrun", ["simctl", "bootstatus", udid, "-b"]);
}

function setIosAppearance(udid, appearance) {
  execOk("xcrun", ["simctl", "ui", udid, "appearance", appearance]);
}

function overrideIosStatusBar(udid) {
  execOk("xcrun", [
    "simctl",
    "status_bar",
    udid,
    "override",
    "--time",
    "9:41",
    "--dataNetwork",
    "wifi",
    "--wifiMode",
    "active",
    "--wifiBars",
    "3",
    "--cellularMode",
    "active",
    "--cellularBars",
    "4",
    "--batteryState",
    "charged",
    "--batteryLevel",
    "100",
  ]);
}

function clearIosStatusBar(udid) {
  execOk("xcrun", ["simctl", "status_bar", udid, "clear"]);
}

function collectPngs(dir) {
  const found = [];
  if (!existsSync(dir)) return found;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...collectPngs(path));
    } else if (entry.name.endsWith(".png")) {
      found.push(path);
    }
  }
  return found;
}

function moveFile(from, to) {
  try {
    renameSync(from, to);
  } catch {
    copyFileSync(from, to);
    rmSync(from);
  }
}

async function runIosCapture({ udid, appearance, scenes, outDir, port, logPath }) {
  const dest = resolve(outDir, IOS_OUTPUT_TARGET, appearance);
  rmSync(dest, { recursive: true, force: true });
  mkdirSync(dest, { recursive: true });
  setIosAppearance(udid, appearance);
  overrideIosStatusBar(udid);

  const onlyTesting = scenes.length === SCENES.length
    ? [`-only-testing:${IOS_TEST_CLASS}`]
    : scenes.map((scene) => `-only-testing:${IOS_TEST_CLASS}/${scene.ios}`);

  const serverUrl = `http://127.0.0.1:${port}`;
  const argv = [
    "test",
    "-project",
    "OpenCode Mobile.xcodeproj",
    "-scheme",
    "OpenCode Mobile",
    "-destination",
    `platform=iOS Simulator,id=${udid}`,
    "-parallel-testing-enabled",
    "NO",
    "-maximum-parallel-testing-workers",
    "1",
    ...onlyTesting,
    `TEST_RUNNER_STORE_SCREENSHOT_OUT=${dest}`,
    `TEST_RUNNER_STORE_SCREENSHOT_SERVER_URL=${serverUrl}`,
    `TEST_RUNNER_STORE_SCREENSHOT_SERVER_PASSWORD=${SERVER_PASSWORD}`,
    "CODE_SIGNING_ALLOWED=NO",
  ];
  try {
    await runLogged("xcodebuild", argv, {
      cwd: IOS_PROJECT_DIR,
      env: {
        ...process.env,
        STORE_SCREENSHOT_OUT: dest,
        STORE_SCREENSHOT_SERVER_URL: serverUrl,
        STORE_SCREENSHOT_SERVER_PASSWORD: SERVER_PASSWORD,
        TEST_RUNNER_STORE_SCREENSHOT_OUT: dest,
        TEST_RUNNER_STORE_SCREENSHOT_SERVER_URL: serverUrl,
        TEST_RUNNER_STORE_SCREENSHOT_SERVER_PASSWORD: SERVER_PASSWORD,
      },
      logPath,
      label: `iOS ${appearance}`,
    });
  } finally {
    try {
      clearIosStatusBar(udid);
    } catch (error) {
      console.error(`status_bar clear failed: ${error.message}`);
    }
  }
}

async function bootEmulator(tools, spec, { headless, logPath }) {
  const before = new Set(listAdbDevices(tools).map((d) => d.serial));
  const argv = ["-avd", spec.avd, "-no-snapshot", "-no-boot-anim", "-no-audio", "-gpu", "auto"];
  if (headless) argv.push("-no-window");
  console.log(`booting emulator ${spec.avd}`);
  const child = spawnLogged(tools.emulator, argv, {
    logPath,
    env: { ...process.env, ANDROID_HOME: tools.sdk, ANDROID_SDK_ROOT: tools.sdk },
  });

  let earlyExit = null;
  child.once("close", (code, signal) => {
    earlyExit = { code, signal };
  });

  const deadline = Date.now() + BOOT_TIMEOUT_MS;
  let serial = null;
  while (Date.now() < deadline) {
    if (earlyExit) {
      throw new Error(
        `emulator ${spec.avd} exited before boot (code=${earlyExit.code} signal=${earlyExit.signal})\n${tailFile(logPath, 50)}`,
      );
    }
    const devices = listAdbDevices(tools);
    const added = devices.filter((d) => !before.has(d.serial) && d.serial.startsWith("emulator-"));
    const ready = added.find((d) => d.state === "device") ?? devices.find((d) => {
      return d.state === "device" && d.serial.startsWith("emulator-") && emulatorAvdName(tools, d.serial) === spec.avd;
    });
    if (ready) {
      serial = ready.serial;
      break;
    }
    await sleep(1000);
  }
  if (!serial) {
    throw new Error(`emulator ${spec.avd} did not appear in adb within ${BOOT_TIMEOUT_MS / 1000}s\n${tailFile(logPath, 50)}`);
  }

  while (Date.now() < deadline) {
    if (earlyExit) {
      throw new Error(
        `emulator ${spec.avd} exited during boot (code=${earlyExit.code})\n${tailFile(logPath, 50)}`,
      );
    }
    try {
      const prop = adb(tools, ["shell", "getprop", "sys.boot_completed"], serial).trim();
      if (prop === "1") {
        return { serial, child };
      }
    } catch {
      // adb not ready
    }
    await sleep(2000);
  }
  throw new Error(`emulator ${spec.avd} (${serial}) did not reach boot_completed=1\n${tailFile(logPath, 50)}`);
}

function adbShell(tools, serial, command) {
  return adb(tools, ["shell", command], serial);
}

function demoBroadcast(tools, serial, extras) {
  adbShell(tools, serial, `am broadcast -a com.android.systemui.demo ${extras}`);
}

async function configureAndroidDevice(tools, serial, appearance) {
  adbShell(tools, serial, "settings put global window_animation_scale 0");
  adbShell(tools, serial, "settings put global transition_animation_scale 0");
  adbShell(tools, serial, "settings put global animator_duration_scale 0");
  adbShell(tools, serial, `cmd uimode night ${appearance === "dark" ? "yes" : "no"}`);
  adbShell(tools, serial, "settings put global sysui_demo_allowed 1");
  adbShell(tools, serial, "settings put global airplane_mode_radios cell");
  adbShell(tools, serial, "settings put global airplane_mode_on 1");
  adbShell(tools, serial, "settings put global captive_portal_mode 0");
  adbShell(tools, serial, "settings put global captive_portal_detection_enabled 0");
  adbShell(tools, serial, "svc wifi enable");
  adbShell(tools, serial, "settings put secure icon_blacklist wifi,satellite");
  await sleep(1000);
  demoBroadcast(tools, serial, "-e command exit");
  await sleep(300);
  demoBroadcast(tools, serial, "-e command enter");
  demoBroadcast(tools, serial, "-e command clock -e hhmm 0941");
  demoBroadcast(tools, serial, "-e command battery -e level 100 -e plugged false");
  demoBroadcast(tools, serial, "-e command network -e mobile hide");
  demoBroadcast(tools, serial, "-e command network -e airplane hide -e nosim hide");
  demoBroadcast(
    tools,
    serial,
    "-e command status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e sync hide -e tty hide -e eri hide -e mute hide -e speakerphone hide",
  );
  demoBroadcast(tools, serial, "-e command notifications -e visible false");
  adbShell(tools, serial, "settings put secure show_ime_with_hard_keyboard 0");
}

function exitAndroidDemoMode(tools, serial) {
  try {
    adbShell(tools, serial, "am broadcast -a com.android.systemui.demo -e command exit");
  } catch {
    // ignore
  }
}

function reportWindowMetrics(tools, serial, spec) {
  const size = adbShell(tools, serial, "wm size").trim();
  const density = adbShell(tools, serial, "wm density").trim();
  console.log(`${spec.avd} wm size:\n${size}`);
  console.log(`${spec.avd} wm density:\n${density}`);
  return { size, density };
}

async function runAndroidCapture({
  tools,
  spec,
  target,
  appearance,
  scenes,
  outDir,
  port,
  logPath,
}) {
  const dest = resolve(outDir, target, appearance);
  rmSync(dest, { recursive: true, force: true });
  mkdirSync(dest, { recursive: true });

  try {
    adb(tools, ["shell", `rm -rf ${DEVICE_SCREENSHOT_DIR}`], spec.serial);
  } catch {
    // ignore missing dir
  }

  const classArg = scenes.length === SCENES.length
    ? ANDROID_TEST_CLASS
    : scenes.map((scene) => `${ANDROID_TEST_CLASS}#${scene.android}`).join(",");

  const argv = [
    ":app:connectedDebugAndroidTest",
    "-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true",
    `-Pandroid.testInstrumentationRunnerArguments.class=${classArg}`,
    `-Pandroid.testInstrumentationRunnerArguments.storeServerUrl=http://10.0.2.2:${port}`,
    `-Pandroid.testInstrumentationRunnerArguments.storeServerPassword=${SERVER_PASSWORD}`,
  ];
  await runLogged(join(ANDROID_DIR, "gradlew"), argv, {
    cwd: ANDROID_DIR,
    env: { ...process.env, ANDROID_HOME: tools.sdk, ANDROID_SDK_ROOT: tools.sdk },
    logPath,
    label: `Android ${target} ${appearance}`,
  });

  const pullDir = join(tmpdir(), `store-screenshots-pull-${process.pid}-${Date.now()}`);
  mkdirSync(pullDir, { recursive: true });
  try {
    adb(tools, ["pull", DEVICE_SCREENSHOT_DIR, pullDir], spec.serial);
    for (const png of collectPngs(pullDir)) {
      moveFile(png, join(dest, basename(png)));
    }
  } catch (error) {
    const stderr = error.stderr?.toString?.() ?? error.message;
    throw new Error(`adb pull failed from ${DEVICE_SCREENSHOT_DIR}: ${stderr}`);
  } finally {
    rmSync(pullDir, { recursive: true, force: true });
  }
  try {
    adb(tools, ["shell", `rm -rf ${DEVICE_SCREENSHOT_DIR}`], spec.serial);
  } catch {
    // ignore missing dir
  }
}

function expectedSizeFor(target) {
  if (target === "ios" || target === IOS_OUTPUT_TARGET) return IOS_EXPECTED;
  const avds = loadAvds();
  const spec = avds[target];
  return { width: spec.width, height: spec.height };
}

function playAspectRuleFor(target) {
  if (target === "ios" || target === IOS_OUTPUT_TARGET) return undefined;
  const spec = loadAvds()[target];
  return spec?.playAspectRule ?? "error";
}

function validateOutputs(outDir, targets, appearances, scenes) {
  console.log("target           app    file                     WxH        result");
  let failed = 0;
  for (const target of targets) {
    const name = outputTargetName(target);
    const expected = expectedSizeFor(target);
    const playAspectRule = playAspectRuleFor(target);
    for (const appearance of appearances) {
      for (const scene of scenes) {
        const filePath = join(outDir, name, appearance, scene.file);
        const result = inspectPng(filePath, {
          expectedWidth: expected.width,
          expectedHeight: expected.height,
          playAspectRule,
        });
        console.log(formatValidationRow(name, appearance, scene.file, result));
        if (result.status !== "OK" && result.status !== "WARN") failed += 1;
      }
    }
  }
  if (failed) {
    fail(`${failed} screenshot(s) missing or mismatched`);
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.selfTest) {
    runSelfTest();
    return;
  }

  const outDir = isAbsolute(args.out) ? args.out : resolve(process.cwd(), args.out);
  const logDir = join(outDir, "logs");
  mkdirSync(logDir, { recursive: true });
  const scenes = resolveScenes(args.scenes);
  const appearances = appearancesFor(args.appearance);
  const requested = resolveRunTargets(args.target);
  const iosWanted = requested.includes("ios") && !args.avdOnly;
  const androidWanted = requested.filter((t) => t !== "ios");
  const androidNeeded = androidWanted.length > 0 && !args.simOnly;
  const startServer = !args.skipServer && !args.avdOnly && !args.simOnly;

  let serverProc = null;
  let emulator = null;
  let tools = null;
  let cleaned = false;

  const cleanup = async ({ keepEmulator = args.keepEmulator } = {}) => {
    if (cleaned) return;
    cleaned = true;
    if (emulator?.serial && tools) {
      exitAndroidDemoMode(tools, emulator.serial);
      if (!keepEmulator) {
        console.log(`killing emulator ${emulator.serial}`);
        killEmulatorSerial(tools, emulator.serial);
        if (emulator.child) {
          await sleep(1000);
          killProcess(emulator.child);
        }
      }
    } else if (emulator?.child && !keepEmulator) {
      killProcess(emulator.child);
    }
    killProcess(serverProc);
  };

  const onSignal = async () => {
    await cleanup({ keepEmulator: args.keepEmulator });
    process.exit(130);
  };
  process.on("SIGINT", onSignal);
  process.on("SIGTERM", onSignal);

  try {
    if (startServer) {
      serverProc = startFixtureServer(args.serverPort, join(logDir, "fixture-server.log"));
      await waitForHealth(args.serverPort, HEALTH_TIMEOUT_MS);
      console.log(`fixture server ready on :${args.serverPort}`);
    }

    if ((iosWanted || args.simOnly) && requested.includes("ios")) {
      const sim = findIosSimulator();
      console.log(`iOS simulator: ${sim.name} ${sim.ios} ${sim.udid} (${sim.state})`);
      bootIosSimulator(sim.udid, sim.state);
      if (args.simOnly) {
        for (const appearance of appearances) {
          setIosAppearance(sim.udid, appearance);
          overrideIosStatusBar(sim.udid);
          console.log(`applied appearance=${appearance} and status bar overrides`);
        }
        clearIosStatusBar(sim.udid);
        console.log("status_bar cleared; simulator left booted");
      } else {
        for (const appearance of appearances) {
          await runIosCapture({
            udid: sim.udid,
            appearance,
            scenes,
            outDir,
            port: args.serverPort,
            logPath: join(logDir, `ios-6.9-${appearance}.log`),
          });
        }
      }
    }

    if (androidNeeded) {
      tools = sdkTools(sdkRoot());
      if (!existsSync(tools.adb) || !existsSync(tools.emulator) || !existsSync(tools.avdmanager)) {
        fail(`Android SDK tools missing under ${tools.sdk}`);
      }
      const image = pickSystemImage(tools.sdk);
      const avds = loadAvds();
      killLeftoverStoreEmulators(tools);
      await sleep(1000);
      assertSingleDeviceOrNone(tools, { allowNone: true });

      const androidTargets = androidWanted;
      for (let i = 0; i < androidTargets.length; i += 1) {
        const target = androidTargets[i];
        const spec = avds[target];
        if (!spec) fail(`missing AVD spec for ${target} in ${AVDS_JSON}`);
        ensureAvd(tools, image, spec);
        const keepThis = args.keepEmulator && i === androidTargets.length - 1;
        try {
          assertSingleDeviceOrNone(tools, { allowNone: true });
          emulator = await bootEmulator(tools, spec, {
            headless: args.headless,
            logPath: join(logDir, `emulator-${spec.avd}.log`),
          });
          spec.serial = emulator.serial;
          console.log(`emulator ${spec.avd} serial=${emulator.serial}`);
          const devices = listAdbDevices(tools);
          if (devices.length > 1) {
            fail(
              `Refusing to run: more than one device is attached after booting ${spec.avd} (${devices.map((d) => d.serial).join(", ")}).\n` +
                "AGP connectedDebugAndroidTest runs on every connected device and does not honor ANDROID_SERIAL.",
            );
          }
          for (const appearance of appearances) {
            await configureAndroidDevice(tools, emulator.serial, appearance);
            if (args.avdOnly) {
              reportWindowMetrics(tools, emulator.serial, spec);
              break;
            }
            await runAndroidCapture({
              tools,
              spec,
              target,
              appearance,
              scenes,
              outDir,
              port: args.serverPort,
              logPath: join(logDir, `${target}-${appearance}.log`),
            });
          }
        } finally {
          if (emulator?.serial) {
            exitAndroidDemoMode(tools, emulator.serial);
            if (!keepThis) {
              console.log(`killing emulator ${emulator.serial}`);
              killEmulatorSerial(tools, emulator.serial);
              await sleep(2000);
              killProcess(emulator.child);
              emulator = null;
            }
          }
        }
      }
    }

    if (!args.avdOnly && !args.simOnly) {
      const validateTargets = args.simOnly ? [] : requested.filter((t) => (t === "ios" ? iosWanted : androidNeeded));
      validateOutputs(outDir, validateTargets, appearances, scenes);
    }
  } catch (error) {
    await cleanup({ keepEmulator: false });
    console.error(error.message ?? error);
    process.exitCode = 1;
  }

  if (!args.avdOnly && !args.simOnly && !args.noGallery) {
    try {
      const galleryPath = writeGallery(outDir);
      console.log(`gallery: ${galleryPath}`);
    } catch (error) {
      console.error(`gallery write failed: ${error.message ?? error}`);
    }
  }

  if (!cleaned) {
    cleaned = true;
    killProcess(serverProc);
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
