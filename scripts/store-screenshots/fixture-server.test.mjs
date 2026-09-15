import assert from "node:assert/strict";
import { after, test } from "node:test";
import { startFixtureServer, AUTH_USERNAME, AUTH_PASSWORD } from "./fixture-server.mjs";

const NOW = "2026-09-14T09:41:00.000Z";
const NOW_MS = Date.parse(NOW);

const started = await startFixtureServer({
  host: "127.0.0.1",
  port: 0,
  nowMs: NOW_MS,
});
const base = `http://127.0.0.1:${started.port}`;
const auth = Buffer.from(`${AUTH_USERNAME}:${AUTH_PASSWORD}`).toString("base64");

after(async () => {
  await started.close();
});

async function request(path, { headers = {}, raw = false } = {}) {
  const response = await fetch(`${base}${path}`, { headers });
  if (raw) {
    return response;
  }
  const text = await response.text();
  return {
    status: response.status,
    headers: response.headers,
    text,
    json: text ? JSON.parse(text) : null,
  };
}

function authorized(path, options) {
  return request(path, {
    ...options,
    headers: { authorization: `Basic ${auth}`, ...(options?.headers ?? {}) },
  });
}

test("rejects requests without credentials", async () => {
  const response = await request("/api/health");
  assert.equal(response.status, 401);
  assert.match(response.headers.get("www-authenticate") ?? "", /Basic/i);
});

test("serves v2 health shape", async () => {
  const response = await authorized("/api/health");
  assert.equal(response.status, 200);
  assert.equal(response.json.healthy, true);
  assert.equal(typeof response.json.version, "string");
  assert.equal(typeof response.json.pid, "number");
  assert.ok(Number.isInteger(response.json.pid));
});

test("global health is 404 so the classifier picks v2", async () => {
  const response = await authorized("/global/health");
  assert.equal(response.status, 404);
});

test("lists sessions newest first with required fields", async () => {
  const response = await authorized("/api/session");
  assert.equal(response.status, 200);
  assert.ok(Array.isArray(response.json.data));
  const ids = response.json.data.map((session) => session.id);
  assert.deepEqual(ids, [
    "ses_code",
    "ses_permission",
    "ses_git",
    "ses_proto",
    "ses_info",
    "ses_prs",
    "ses_openprs",
  ]);
  const first = response.json.data[0];
  assert.equal(first.id, "ses_code");
  assert.equal(first.title, "Code samples request");
  assert.equal(typeof first.id, "string");
  assert.equal(first.time.created, NOW_MS - 10_800_000);
  assert.equal(first.time.updated, NOW_MS - 300_000);
});

test("lists messages for a session", async () => {
  const response = await authorized("/api/session/ses_code/message?order=asc");
  assert.equal(response.status, 200);
  assert.ok(Array.isArray(response.json.data));
  assert.equal(response.json.data.length, 4);
  const [user, assistant] = response.json.data;
  assert.equal(user.id, "msg_code_user_1");
  assert.equal(user.type, "user");
  assert.equal(typeof user.text, "string");
  assert.equal(assistant.type, "assistant");
  assert.equal(assistant.agent, "build");
  assert.equal(assistant.model.id, "grok-4.5");
  assert.equal(assistant.model.providerID, "xai");
  assert.equal(typeof assistant.cost, "number");
  assert.ok(assistant.content.some((part) => part.type === "text" && part.text.includes("OpenCodeButtonVariant")));
});

test("SSE first bytes are server.connected", async () => {
  const response = await fetch(`${base}/api/event`, {
    headers: { authorization: `Basic ${auth}`, accept: "text/event-stream" },
  });
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (!buffer.includes("\n\n")) {
    const { value, done } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
  }
  await reader.cancel();
  const firstLines = buffer.split("\n").slice(0, 5);
  assert.match(firstLines.join("\n"), /server\.connected/);
  assert.match(buffer, /^data: /);
});

test("same --now yields byte-identical session JSON", async () => {
  const other = await startFixtureServer({ host: "127.0.0.1", port: 0, nowMs: NOW_MS });
  try {
    const [a, b] = await Promise.all([
      fetch(`${base}/api/session`, { headers: { authorization: `Basic ${auth}` } }).then((r) => r.text()),
      fetch(`http://127.0.0.1:${other.port}/api/session`, {
        headers: { authorization: `Basic ${auth}` },
      }).then((r) => r.text()),
    ]);
    assert.equal(a, b);
  } finally {
    await other.close();
  }
});
