import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, verify } from "node:crypto";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import {
  DISPLAY_TARGETS,
  createAscClient,
  createAuthToken,
  isAllowedWrite,
  parseUploadArgs,
  pickEditableVersion,
  planTarget,
  uploadAll,
} from "./upload-app-store.mjs";

const IPHONE = DISPLAY_TARGETS.find((target) => target.id === "iphone");
const IPAD = DISPLAY_TARGETS.find((target) => target.id === "ipad");
const API = "https://api.example.test";

// Signature + IHDR is all readPngHeader looks at.
function pngHeader(width, height, colorType = 2) {
  const buf = Buffer.alloc(33);
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(buf, 0);
  buf.writeUInt32BE(13, 8);
  buf.write("IHDR", 12, "ascii");
  buf.writeUInt32BE(width, 16);
  buf.writeUInt32BE(height, 20);
  buf[24] = 8;
  buf[25] = colorType;
  return buf;
}

async function withSlides(layout, run) {
  const dir = mkdtempSync(join(tmpdir(), "asc-upload-"));
  try {
    for (const [folder, files] of Object.entries(layout)) {
      mkdirSync(join(dir, folder), { recursive: true });
      for (const [name, bytes] of Object.entries(files)) {
        writeFileSync(join(dir, folder, name), bytes);
      }
    }
    return await run(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function json(status, body) {
  return new Response(body == null ? null : JSON.stringify(body), { status });
}

/** In-memory App Store Connect with one app, its versions and one localization. */
function fakeAsc({ versions, sets = [], screenshots = [] }) {
  const state = { sets: [...sets], screenshots: [...screenshots], uploads: [], order: {} };
  const calls = [];
  let nextId = 1;
  const fetchImpl = async (url, init = {}) => {
    const method = init.method ?? "GET";
    const parsed = new URL(url);
    const path = parsed.pathname;
    calls.push({ method, path, headers: init.headers ?? {} });

    if (parsed.origin === "https://upload.example.test") {
      state.uploads.push({ path, headers: init.headers, bytes: Buffer.from(init.body) });
      return new Response("", { status: 200 });
    }
    assert.equal(parsed.origin, API);
    assert.match(init.headers.Authorization, /^Bearer /);
    const body = init.body ? JSON.parse(init.body) : null;

    if (method === "GET" && path === "/v1/apps") {
      return json(200, { data: [{ id: "app1", attributes: { bundleId: "dev.opencode.mobile", name: "OpenCode Mobile" } }] });
    }
    if (method === "GET" && path === "/v1/apps/app1/appStoreVersions") {
      return json(200, { data: versions });
    }
    if (method === "GET" && /^\/v1\/appStoreVersions\/[^/]+\/appStoreVersionLocalizations$/.test(path)) {
      return json(200, { data: [{ id: "loc1", attributes: { locale: "en-US" } }] });
    }
    if (method === "GET" && path === "/v1/appStoreVersionLocalizations/loc1/appScreenshotSets") {
      const type = parsed.searchParams.get("filter[screenshotDisplayType]");
      return json(200, { data: state.sets.filter((set) => set.attributes.screenshotDisplayType === type) });
    }
    const setShots = path.match(/^\/v1\/appScreenshotSets\/([^/]+)\/appScreenshots$/);
    if (method === "GET" && setShots) {
      return json(200, { data: state.screenshots.filter((shot) => shot.setId === setShots[1]) });
    }
    if (method === "POST" && path === "/v1/appScreenshotSets") {
      const set = { id: `set${nextId++}`, attributes: body.data.attributes };
      state.sets.push(set);
      return json(201, { data: set });
    }
    if (method === "POST" && path === "/v1/appScreenshots") {
      const id = `shot${nextId++}`;
      const { fileName, fileSize } = body.data.attributes;
      state.screenshots.push({
        id,
        setId: body.data.relationships.appScreenshotSet.data.id,
        attributes: { fileName, fileSize, assetDeliveryState: { state: "AWAITING_UPLOAD" } },
      });
      return json(201, {
        data: {
          id,
          attributes: {
            uploadOperations: [
              {
                method: "PUT",
                url: `https://upload.example.test/${id}`,
                offset: 0,
                length: fileSize,
                requestHeaders: [{ name: "Content-Type", value: "image/png" }],
              },
            ],
          },
        },
      });
    }
    const shot = path.match(/^\/v1\/appScreenshots\/([^/]+)$/);
    if (shot) {
      const entry = state.screenshots.find((candidate) => candidate.id === shot[1]);
      if (method === "DELETE") {
        state.screenshots = state.screenshots.filter((candidate) => candidate !== entry);
        return json(204);
      }
      if (method === "PATCH") {
        entry.attributes.checksum = body.data.attributes.sourceFileChecksum;
        entry.attributes.assetDeliveryState = { state: "UPLOAD_COMPLETE" };
        return json(200, { data: entry });
      }
      if (method === "GET") {
        const response = json(200, { data: structuredClone(entry) });
        // Processing finishes on the poll after the commit.
        if (entry.attributes.assetDeliveryState.state === "UPLOAD_COMPLETE") {
          entry.attributes.assetDeliveryState = { state: "COMPLETE" };
        }
        return response;
      }
    }
    const reorder = path.match(/^\/v1\/appScreenshotSets\/([^/]+)\/relationships\/appScreenshots$/);
    if (method === "PATCH" && reorder) {
      state.order[reorder[1]] = body.data.map((entry) => entry.id);
      return json(204);
    }
    throw new Error(`Unexpected ${method} ${path}`);
  };
  return { state, calls, fetchImpl };
}

const EDITABLE = { id: "v2", attributes: { versionString: "1.1.0", appVersionState: "PREPARE_FOR_SUBMISSION" } };
const LIVE = { id: "v1", attributes: { versionString: "1.0.0", appVersionState: "READY_FOR_DISTRIBUTION" } };

function clientFor(fake, dryRun = false) {
  return createAscClient({ token: () => "token", dryRun, fetchImpl: fake.fetchImpl, apiRoot: API });
}

test("createAuthToken signs an ES256 token App Store Connect accepts", () => {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const pem = privateKey.export({ type: "pkcs8", format: "pem" });
  const token = createAuthToken({ keyId: "KEY123", issuerId: "issuer", privateKey: pem, now: 1_000_000 });
  const [header, payload, signature] = token.split(".");

  assert.deepEqual(JSON.parse(Buffer.from(header, "base64url")), { alg: "ES256", kid: "KEY123", typ: "JWT" });
  assert.deepEqual(JSON.parse(Buffer.from(payload, "base64url")), {
    iss: "issuer",
    iat: 1000,
    exp: 1000 + 20 * 60,
    aud: "appstoreconnect-v1",
  });
  const valid = verify(
    "sha256",
    Buffer.from(`${header}.${payload}`),
    { key: publicKey, dsaEncoding: "ieee-p1363" },
    Buffer.from(signature, "base64url"),
  );
  assert.equal(valid, true);
});

test("planTarget skips a missing or empty folder instead of clearing the set", async () => {
  await withSlides({ "app-store-iphone-6.9": {} }, (dir) => {
    assert.equal(planTarget(dir, IPHONE).status, "skip");
    assert.equal(planTarget(dir, IPAD).status, "skip");
  });
});

test("planTarget orders files by name, numerically", async () => {
  const png = pngHeader(1320, 2868);
  await withSlides({ "app-store-iphone-6.9": { "10-last.png": png, "02-b.png": png, "01-a.png": png, "notes.txt": "x" } }, (dir) => {
    const plan = planTarget(dir, IPHONE);
    assert.equal(plan.status, "ready");
    assert.deepEqual(plan.files.map((file) => file.fileName), ["01-a.png", "02-b.png", "10-last.png"]);
  });
});

test("planTarget refuses wrong sizes, alpha channels, and more than ten shots", async () => {
  await withSlides({ "app-store-ipad-13": { "01.png": pngHeader(1320, 2868), "02.png": pngHeader(2064, 2752, 6) } }, (dir) => {
    assert.throws(() => planTarget(dir, IPAD), (error) => {
      assert.match(error.message, /01\.png: 1320x2868, expected 2064x2752/);
      assert.match(error.message, /02\.png: has an alpha channel/);
      return true;
    });
  });
  const eleven = Object.fromEntries(
    Array.from({ length: 11 }, (_, i) => [`${String(i + 1).padStart(2, "0")}.png`, pngHeader(1320, 2868)]),
  );
  await withSlides({ "app-store-iphone-6.9": eleven }, (dir) => {
    assert.throws(() => planTarget(dir, IPHONE), /11 PNGs; App Store Connect allows at most 10/);
  });
});

test("only screenshot writes are allowed; review submission is refused", () => {
  assert.equal(isAllowedWrite("POST", "/v1/appScreenshots"), true);
  assert.equal(isAllowedWrite("DELETE", "/v1/appScreenshots/abc"), true);
  assert.equal(isAllowedWrite("PATCH", "/v1/appScreenshotSets/s1/relationships/appScreenshots"), true);
  assert.equal(isAllowedWrite("POST", "/v1/reviewSubmissions"), false);
  assert.equal(isAllowedWrite("POST", "/v1/reviewSubmissionItems"), false);
  assert.equal(isAllowedWrite("POST", "/v1/appStoreVersionSubmissions"), false);
  assert.equal(isAllowedWrite("PATCH", "/v1/reviewSubmissions/r1"), false);
  assert.equal(isAllowedWrite("PATCH", "/v1/appStoreVersions/v1"), false);
  assert.equal(isAllowedWrite("DELETE", "/v1/appScreenshotSets/s1"), false);
});

test("the client refuses forbidden writes and every dry-run write before the network", async () => {
  let fetched = 0;
  const fetchImpl = async () => {
    fetched += 1;
    return json(200, {});
  };
  const live = createAscClient({ token: () => "t", fetchImpl, apiRoot: API });
  await assert.rejects(live.post("/v1/reviewSubmissions", {}), /only screenshot writes are allowed/);
  const dry = createAscClient({ token: () => "t", dryRun: true, fetchImpl, apiRoot: API });
  await assert.rejects(dry.post("/v1/appScreenshots", {}), /Dry run: refusing POST/);
  await assert.rejects(dry.delete("/v1/appScreenshots/x"), /Dry run: refusing DELETE/);
  assert.equal(fetched, 0);
});

test("a 403 explains the API key role", async () => {
  const client = createAscClient({
    token: () => "t",
    fetchImpl: async () => json(403, { errors: [] }),
    apiRoot: API,
  });
  await assert.rejects(client.get("/v1/apps"), /App Manager or Admin role/);
});

test("pickEditableVersion picks the single editable version", () => {
  assert.equal(pickEditableVersion([LIVE, EDITABLE]).id, "v2");
  assert.throws(() => pickEditableVersion([LIVE]), /No editable version/);
  const other = { id: "v3", attributes: { versionString: "1.2.0", appStoreState: "DEVELOPER_REJECTED" } };
  assert.throws(() => pickEditableVersion([EDITABLE, other]), /Several editable versions; pass --version/);
  assert.equal(pickEditableVersion([EDITABLE, other], { versionString: "1.2.0" }).id, "v3");
});

test("uploadAll replaces the set in file order and never writes outside it", async () => {
  const first = Buffer.concat([pngHeader(1320, 2868), Buffer.from("first")]);
  const second = Buffer.concat([pngHeader(1320, 2868), Buffer.from("second")]);
  const fake = fakeAsc({
    versions: [LIVE, EDITABLE],
    sets: [{ id: "phoneSet", attributes: { screenshotDisplayType: "APP_IPHONE_67" } }],
    screenshots: [
      { id: "old1", setId: "phoneSet", attributes: { fileName: "old-1.png" } },
      { id: "old2", setId: "phoneSet", attributes: { fileName: "old-2.png" } },
    ],
  });
  const lines = [];
  await withSlides({ "app-store-iphone-6.9": { "02-second.png": second, "01-first.png": first } }, (dir) =>
    uploadAll({
      client: clientFor(fake),
      dir,
      targets: DISPLAY_TARGETS,
      fetchImpl: fake.fetchImpl,
      log: (line) => lines.push(line),
      sleep: async () => {},
    }),
  );

  const kept = fake.state.screenshots.filter((shot) => shot.setId === "phoneSet");
  assert.deepEqual(kept.map((shot) => shot.attributes.fileName), ["01-first.png", "02-second.png"]);
  assert.deepEqual(fake.state.order.phoneSet, kept.map((shot) => shot.id));
  assert.deepEqual(
    kept.map((shot) => shot.attributes.checksum),
    [first, second].map((bytes) => createHash("md5").update(bytes).digest("hex")),
  );
  assert.deepEqual(fake.state.uploads.map((upload) => upload.bytes), [first, second]);
  for (const upload of fake.state.uploads) {
    assert.equal(upload.headers.Authorization, undefined);
  }
  // iPad had no folder: its set was never looked up or touched.
  assert.ok(lines.some((line) => /skip {2}iPad 13"/.test(line)));
  const writes = fake.calls.filter((call) => call.method !== "GET" && !call.path.startsWith("/shot"));
  assert.deepEqual(
    writes.map((call) => `${call.method} ${call.path}`),
    [
      "DELETE /v1/appScreenshots/old1",
      "DELETE /v1/appScreenshots/old2",
      "POST /v1/appScreenshots",
      `PATCH /v1/appScreenshots/${kept[0].id}`,
      "POST /v1/appScreenshots",
      `PATCH /v1/appScreenshots/${kept[1].id}`,
      "PATCH /v1/appScreenshotSets/phoneSet/relationships/appScreenshots",
    ],
  );
});

test("uploadAll creates a missing set", async () => {
  const fake = fakeAsc({ versions: [EDITABLE] });
  await withSlides({ "app-store-ipad-13": { "01.png": pngHeader(2064, 2752) } }, (dir) =>
    uploadAll({
      client: clientFor(fake),
      dir,
      targets: [IPAD],
      fetchImpl: fake.fetchImpl,
      log: () => {},
      sleep: async () => {},
    }),
  );
  assert.equal(fake.state.sets.length, 1);
  assert.equal(fake.state.sets[0].attributes.screenshotDisplayType, "APP_IPAD_PRO_3GEN_129");
  assert.equal(fake.state.screenshots.length, 1);
});

test("dry run lists the replacement and only reads", async () => {
  const fake = fakeAsc({
    versions: [EDITABLE],
    sets: [{ id: "phoneSet", attributes: { screenshotDisplayType: "APP_IPHONE_67" } }],
    screenshots: [{ id: "old1", setId: "phoneSet", attributes: { fileName: "old-1.png" } }],
  });
  const lines = [];
  await withSlides({ "app-store-iphone-6.9": { "01-home.png": pngHeader(1320, 2868) } }, (dir) =>
    uploadAll({
      client: clientFor(fake, true),
      dir,
      targets: [IPHONE],
      dryRun: true,
      fetchImpl: fake.fetchImpl,
      log: (line) => lines.push(line),
    }),
  );
  assert.ok(fake.calls.every((call) => call.method === "GET"));
  assert.ok(lines.includes("  would delete  old-1.png"));
  assert.ok(lines.includes("  would upload  01-home.png"));
  assert.match(lines[0], /^Dry run: OpenCode Mobile 1\.1\.0 \(PREPARE_FOR_SUBMISSION\), en-US$/);
});

test("invalid slides fail before any App Store Connect call", async () => {
  const fake = fakeAsc({ versions: [EDITABLE] });
  await withSlides({ "app-store-iphone-6.9": { "01.png": pngHeader(100, 100) } }, (dir) =>
    assert.rejects(
      uploadAll({ client: clientFor(fake), dir, targets: DISPLAY_TARGETS, fetchImpl: fake.fetchImpl, log: () => {} }),
      /not uploadable/,
    ),
  );
  assert.equal(fake.calls.length, 0);
});

test("a failed processing state stops the upload with Apple's reason", async () => {
  const fake = fakeAsc({ versions: [EDITABLE] });
  const failing = async (url, init) => {
    const response = await fake.fetchImpl(url, init);
    if ((init?.method ?? "GET") === "GET" && /\/v1\/appScreenshots\/[^/]+$/.test(new URL(url).pathname)) {
      const body = await response.json();
      body.data.attributes.assetDeliveryState = { state: "FAILED", errors: [{ code: "IMAGE_INCORRECT_DIMENSIONS", description: "Bad size" }] };
      return json(200, body);
    }
    return response;
  };
  const client = createAscClient({ token: () => "t", fetchImpl: failing, apiRoot: API });
  await withSlides({ "app-store-iphone-6.9": { "01.png": pngHeader(1320, 2868) } }, (dir) =>
    assert.rejects(
      uploadAll({ client, dir, targets: [IPHONE], fetchImpl: failing, log: () => {}, sleep: async () => {} }),
      /rejected 01\.png: Bad size/,
    ),
  );
});

test("parseUploadArgs reads options and rejects unknown targets", () => {
  assert.deepEqual(parseUploadArgs(["--dry-run", "--target", "ipad", "--version", "1.1.0"]), {
    dir: "store-screenshots/spectrum",
    target: "ipad",
    locale: "en-US",
    versionString: "1.1.0",
    bundleId: "dev.opencode.mobile",
    dryRun: true,
    help: false,
  });
  assert.throws(() => parseUploadArgs(["--target", "watch"]), /--target must be one of/);
  assert.throws(() => parseUploadArgs(["--locale"]), /--locale requires a value/);
});
