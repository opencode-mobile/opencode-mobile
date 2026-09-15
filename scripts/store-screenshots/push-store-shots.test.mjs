import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import { test } from "node:test";
import {
  SCENES,
  TARGET_MAP,
  buildScreens,
  loadCopy,
  pushAll,
  pushSet,
  setNameFor,
} from "./push-store-shots.mjs";

const TINY_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
  "base64",
);

const THEME_DEFAULTS = {
  bg: "grain",
  gradient: "none",
  filmGrain: "none",
  frame: "pixel-9-pro-xl-obsidian",
  tabletFrame: "ipad-pro-13-space-black",
};

function randomId() {
  return randomBytes(6).toString("hex");
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function applySetDefaults(body) {
  const incoming = body.theme ?? {};
  const theme = {
    bg: incoming.bg || THEME_DEFAULTS.bg,
    gradient: incoming.gradient || THEME_DEFAULTS.gradient,
    filmGrain: incoming.filmGrain || THEME_DEFAULTS.filmGrain,
    frame: incoming.frame || THEME_DEFAULTS.frame,
    tabletFrame: incoming.tabletFrame || THEME_DEFAULTS.tabletFrame,
  };
  return {
    id: body.id,
    name: body.name || "Untitled set",
    app: body.app ?? "",
    target: body.target || "ios-6.9",
    locale: body.locale || "en-US",
    theme,
    screens: (body.screens ?? []).map((screen) => ({
      id: screen.id || "",
      headline: screen.headline ?? "",
      subtitle: screen.subtitle ?? "",
      layout: screen.layout || "top",
      device: screen.device || "fit",
      banner: screen.banner || "left",
      hasShot: false,
    })),
    updatedAt: new Date().toISOString(),
    renders: body.renders && typeof body.renders === "object" ? body.renders : {},
    hasIcon: Boolean(body.hasIcon),
  };
}

function writeJson(res, status, payload) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(payload));
}

function startMockStoreShots() {
  const state = {
    sets: new Map(),
    shots: new Map(),
    posts: 0,
    puts: 0,
    shotPuts: 0,
    mutations: [],
  };

  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://127.0.0.1");
      const body = await readBody(req);

      if (req.method === "GET" && url.pathname === "/healthz") {
        res.writeHead(200, { "Content-Type": "text/plain" });
        res.end("ok");
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/sets") {
        writeJson(res, 200, [...state.sets.values()]);
        return;
      }

      if (req.method === "POST" && url.pathname === "/api/sets") {
        state.posts += 1;
        state.mutations.push("POST /api/sets");
        const next = applySetDefaults(JSON.parse(body.toString("utf8") || "{}"));
        next.id = randomId();
        for (const screen of next.screens) {
          if (!screen.id) screen.id = randomId();
          screen.hasShot = false;
        }
        state.sets.set(next.id, next);
        writeJson(res, 201, next);
        return;
      }

      const shotMatch = url.pathname.match(/^\/api\/sets\/([^/]+)\/shots\/([^/]+)$/);
      if (req.method === "PUT" && shotMatch) {
        state.shotPuts += 1;
        state.mutations.push(`PUT /api/sets/${shotMatch[1]}/shots/${shotMatch[2]}`);
        const set = state.sets.get(shotMatch[1]);
        if (!set) {
          res.writeHead(404);
          res.end("set not found");
          return;
        }
        const screen = set.screens.find((entry) => entry.id === shotMatch[2]);
        if (!screen) {
          res.writeHead(400);
          res.end("unknown screen");
          return;
        }
        if (req.headers["content-type"] !== "image/png") {
          res.writeHead(400);
          res.end("Content-Type must be image/png");
          return;
        }
        state.shots.set(`${shotMatch[1]}/${shotMatch[2]}`, body);
        screen.hasShot = true;
        res.writeHead(204);
        res.end();
        return;
      }

      const setMatch = url.pathname.match(/^\/api\/sets\/([^/]+)$/);
      if (req.method === "GET" && setMatch) {
        const set = state.sets.get(setMatch[1]);
        if (!set) {
          res.writeHead(404);
          res.end("set not found");
          return;
        }
        writeJson(res, 200, set);
        return;
      }

      if (req.method === "PUT" && setMatch) {
        state.puts += 1;
        state.mutations.push(`PUT /api/sets/${setMatch[1]}`);
        if (!state.sets.has(setMatch[1])) {
          res.writeHead(404);
          res.end("set not found");
          return;
        }
        const incoming = JSON.parse(body.toString("utf8") || "{}");
        const next = applySetDefaults({ ...incoming, id: setMatch[1] });
        const keep = new Set(next.screens.map((screen) => screen.id));
        for (const key of [...state.shots.keys()]) {
          if (key.startsWith(`${setMatch[1]}/`) && !keep.has(key.slice(setMatch[1].length + 1))) {
            state.shots.delete(key);
          }
        }
        for (const screen of next.screens) {
          if (!screen.id) screen.id = randomId();
          screen.hasShot = state.shots.has(`${setMatch[1]}/${screen.id}`);
        }
        state.sets.set(setMatch[1], next);
        writeJson(res, 200, next);
        return;
      }

      res.writeHead(404);
      res.end("not found");
    } catch (error) {
      res.writeHead(500);
      res.end(error.message ?? String(error));
    }
  });

  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      resolve({
        baseUrl: `http://127.0.0.1:${port}`,
        state,
        close() {
          return new Promise((done, fail) => {
            server.close((error) => (error ? fail(error) : done()));
          });
        },
      });
    });
  });
}

function seedPngs(dir, captureTarget, appearance, scenes = SCENES) {
  const dest = join(dir, captureTarget, appearance);
  mkdirSync(dest, { recursive: true });
  for (const scene of scenes) {
    writeFileSync(join(dest, scene.file), TINY_PNG);
  }
}

async function withHarness(fn) {
  const mock = await startMockStoreShots();
  const dir = mkdtempSync(join(tmpdir(), "push-store-shots-"));
  const lines = [];
  try {
    return await fn({
      mock,
      dir,
      log: (line) => lines.push(line),
      lines,
      copy: loadCopy(),
    });
  } finally {
    rmSync(dir, { recursive: true, force: true });
    await mock.close();
  }
}

test("buildScreens fills copy and defaults for new scenes", () => {
  const copy = loadCopy();
  const screens = buildScreens({ scenes: SCENES, copy });
  assert.deepEqual(
    screens.map((screen) => screen.id),
    SCENES.map((scene) => scene.id),
  );
  assert.equal(screens[0].headline, copy.home.headline);
  assert.equal(screens[0].subtitle, copy.home.subtitle);
  assert.equal(screens[0].layout, "top");
  assert.equal(screens[0].device, "fit");
  assert.equal(screens[0].banner, "left");
});

test("buildScreens keeps edited copy and chrome unless forceCopy", () => {
  const copy = loadCopy();
  const existingScreens = [
    {
      id: "home",
      headline: "Edited Home",
      subtitle: "Edited subtitle",
      layout: "bottom",
      device: "bleed",
      banner: "center",
      hasShot: true,
    },
    { id: "extra", headline: "Keep me", subtitle: "Extra", layout: "top", device: "fit", banner: "left" },
  ];
  const kept = buildScreens({ scenes: SCENES, copy, existingScreens });
  assert.equal(kept[0].headline, "Edited Home");
  assert.equal(kept[0].subtitle, "Edited subtitle");
  assert.equal(kept[0].layout, "bottom");
  assert.equal(kept[0].device, "bleed");
  assert.equal(kept[0].banner, "center");
  assert.equal(kept.at(-1).id, "extra");

  const forced = buildScreens({ scenes: SCENES, copy, existingScreens, forceCopy: true });
  assert.equal(forced[0].headline, copy.home.headline);
  assert.equal(forced[0].subtitle, copy.home.subtitle);
  assert.equal(forced[0].layout, "bottom");
  assert.equal(forced.at(-1).id, "extra");

  const pruned = buildScreens({
    scenes: SCENES,
    copy,
    existingScreens,
    pruneExtra: true,
  });
  assert.equal(pruned.some((screen) => screen.id === "extra"), false);
  assert.equal(pruned.length, SCENES.length);
});

test("buildScreens uses defaults.device for new screens only", () => {
  const copy = loadCopy();
  const screens = buildScreens({
    scenes: SCENES,
    copy,
    defaults: { device: "bleed" },
    existingScreens: [
      { id: "home", headline: "Kept", subtitle: "Kept", layout: "top", device: "fit", banner: "left" },
    ],
  });
  assert.equal(screens[0].device, "fit");
  assert.equal(screens.slice(1).every((screen) => screen.device === "bleed"), true);
});

test("buildScreens fills blank headline/subtitle from copy", () => {
  const copy = loadCopy();
  const screens = buildScreens({
    scenes: SCENES,
    copy,
    existingScreens: [
      { id: "home", headline: "", subtitle: "  ", layout: "top", device: "fit", banner: "right" },
    ],
  });
  assert.equal(screens[0].headline, copy.home.headline);
  assert.equal(screens[0].subtitle, copy.home.subtitle);
  assert.equal(screens[0].banner, "right");
});

test("first push creates a set and uploads five scene-id shots", async () => {
  await withHarness(async ({ mock, dir, copy, log }) => {
    seedPngs(dir, "ios-6.9", "light");
    const result = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "ios-6.9",
      appearance: "light",
      scenes: SCENES,
      dir,
      copy,
      log,
    });
    assert.equal(mock.state.posts, 1);
    assert.equal(mock.state.puts, 0);
    assert.equal(mock.state.shotPuts, 5);
    assert.equal(result.created, true);
    assert.equal(result.uploaded, 5);
    assert.equal(result.url, `${mock.baseUrl}/?set=${result.setId}`);

    const set = mock.state.sets.get(result.setId);
    assert.equal(set.name, setNameFor("ios-6.9", "light"));
    assert.equal(set.app, "OpenCode Mobile");
    assert.equal(set.target, TARGET_MAP["ios-6.9"].storeTarget);
    assert.equal(set.theme.frame, TARGET_MAP["ios-6.9"].frame);
    assert.deepEqual(
      set.screens.map((screen) => screen.id),
      SCENES.map((scene) => scene.id),
    );
    assert.equal(set.screens.every((screen) => screen.hasShot), true);
    assert.equal(set.screens.every((screen) => screen.device === "fit"), true);
    assert.equal(set.screens[0].headline, copy.home.headline);
  });
});

test("second push updates in place and preserves user theme and headline", async () => {
  await withHarness(async ({ mock, dir, copy, log }) => {
    seedPngs(dir, "ios-6.9", "light");
    const first = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "ios-6.9",
      appearance: "light",
      scenes: SCENES,
      dir,
      copy,
      log,
    });
    const stored = mock.state.sets.get(first.setId);
    stored.theme.bg = "solid";
    stored.screens[0].headline = "Edited Home";

    const second = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "ios-6.9",
      appearance: "light",
      scenes: SCENES,
      dir,
      copy,
      log,
    });
    assert.equal(second.created, false);
    assert.equal(second.setId, first.setId);
    assert.equal(mock.state.posts, 1);
    assert.equal(mock.state.puts, 1);
    assert.equal(mock.state.shotPuts, 10);
    const set = mock.state.sets.get(second.setId);
    assert.equal(set.theme.bg, "solid");
    assert.equal(set.screens[0].headline, "Edited Home");
    assert.equal(set.screens[0].subtitle, copy.home.subtitle);
  });
});

test("forceCopy overwrites edited headline and subtitle", async () => {
  await withHarness(async ({ mock, dir, copy, log }) => {
    seedPngs(dir, "ios-6.9", "dark");
    const first = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "ios-6.9",
      appearance: "dark",
      scenes: SCENES,
      dir,
      copy,
      log,
    });
    mock.state.sets.get(first.setId).screens[0].headline = "Edited Home";
    mock.state.sets.get(first.setId).screens[0].subtitle = "Edited subtitle";

    await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "ios-6.9",
      appearance: "dark",
      scenes: SCENES,
      dir,
      copy,
      forceCopy: true,
      log,
    });
    const set = mock.state.sets.get(first.setId);
    assert.equal(set.screens[0].headline, copy.home.headline);
    assert.equal(set.screens[0].subtitle, copy.home.subtitle);
  });
});

test("missing PNG throws before any POST or PUT", async () => {
  await withHarness(async ({ mock, dir, copy, log }) => {
    seedPngs(dir, "android-phone", "light");
    rmSync(join(dir, "android-phone", "light", "03-chat-permission.png"));
    await assert.rejects(
      () =>
        pushSet({
          baseUrl: mock.baseUrl,
          captureTarget: "android-phone",
          appearance: "light",
          scenes: SCENES,
          dir,
          copy,
          log,
        }),
      /Missing store-screenshot PNG/,
    );
    assert.equal(mock.state.posts, 0);
    assert.equal(mock.state.puts, 0);
    assert.equal(mock.state.shotPuts, 0);
    assert.deepEqual(mock.state.mutations, []);
  });
});

test("dryRun performs no mutations", async () => {
  await withHarness(async ({ mock, dir, copy, log, lines }) => {
    seedPngs(dir, "android-tab7", "dark");
    const result = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "android-tab7",
      appearance: "dark",
      scenes: SCENES,
      dir,
      copy,
      dryRun: true,
      log,
    });
    assert.equal(result.created, true);
    assert.equal(result.uploaded, 0);
    assert.equal(result.setId, null);
    assert.equal(mock.state.posts, 0);
    assert.equal(mock.state.puts, 0);
    assert.equal(mock.state.shotPuts, 0);
    assert.deepEqual(mock.state.mutations, []);
    assert.match(lines.join("\n"), /would create/);
    assert.equal(mock.state.sets.size, 0);
  });
});

test("tablet create uses play-tablet, tabletFrame, and bleed screens", async () => {
  await withHarness(async ({ mock, dir, copy, log }) => {
    seedPngs(dir, "android-tab10", "light");
    const result = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "android-tab10",
      appearance: "light",
      scenes: SCENES,
      dir,
      copy,
      log,
    });
    const set = mock.state.sets.get(result.setId);
    assert.equal(set.target, "play-tablet");
    assert.equal(set.theme.tabletFrame, "pixel-tablet-hazel");
    assert.equal(set.screens.every((screen) => screen.device === "bleed"), true);
  });
});

test("update preserves an existing screen device", async () => {
  await withHarness(async ({ mock, dir, copy, log }) => {
    seedPngs(dir, "android-tab7", "light");
    const first = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "android-tab7",
      appearance: "light",
      scenes: SCENES,
      dir,
      copy,
      log,
    });
    assert.equal(mock.state.sets.get(first.setId).screens[0].device, "bleed");
    mock.state.sets.get(first.setId).screens[0].device = "fit";

    const second = await pushSet({
      baseUrl: mock.baseUrl,
      captureTarget: "android-tab7",
      appearance: "light",
      scenes: SCENES,
      dir,
      copy,
      log,
    });
    const set = mock.state.sets.get(second.setId);
    assert.equal(set.screens[0].device, "fit");
    assert.equal(set.screens.slice(1).every((screen) => screen.device === "bleed"), true);
  });
});

test("pushAll continues after one failure and throws AggregateError", async () => {
  await withHarness(async ({ mock, dir, copy, log }) => {
    seedPngs(dir, "ios-6.9", "light");
    seedPngs(dir, "ios-6.9", "dark");
    rmSync(join(dir, "ios-6.9", "light", "01-home.png"));
    await assert.rejects(
      () =>
        pushAll({
          baseUrl: mock.baseUrl,
          targets: ["ios-6.9"],
          appearances: ["light", "dark"],
          scenes: SCENES,
          dir,
          copy,
          log,
        }),
      (error) => {
        assert.ok(error instanceof AggregateError);
        assert.equal(error.errors.length, 1);
        assert.match(error.errors[0].message, /Missing store-screenshot PNG/);
        return true;
      },
    );
    assert.equal(mock.state.posts, 1);
    assert.equal(mock.state.shotPuts, 5);
    assert.equal([...mock.state.sets.values()][0].name, setNameFor("ios-6.9", "dark"));
  });
});
