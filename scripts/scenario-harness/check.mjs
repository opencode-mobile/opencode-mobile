#!/usr/bin/env node
// Headless check against a running harness: plays each scenario as "another
// client" (Run), answers asks the scenario does not automate, stops hangs, and
// prints the model calls and server event types each scenario produced.
//
//   node scripts/scenario-harness/check.mjs [--ui http://127.0.0.1:4500] [--priority P1] [--variants] [scenario-id ...]

const argv = process.argv.slice(2);
let ui = "http://127.0.0.1:4500";
let withVariants = false;
const priorities = [];
const ids = [];
for (let i = 0; i < argv.length; i += 1) {
  if (argv[i] === "--ui") ui = argv[++i];
  else if (argv[i] === "--variants") withVariants = true;
  else if (argv[i] === "--priority") priorities.push(argv[++i]);
  else if (argv[i] === "--help" || argv[i] === "-h") {
    console.log(`Usage: check.mjs [--ui <harness url>] [--priority P0|P1|P2] [--variants] [scenario-id ...]

Plays scenarios against the harness's running server and reports what happened.
A scenario's checkVariant (when set) is what runs by default; --variants also
runs every other variant. appOnly scenarios still run but need a device to judge.`);
    process.exit(0);
  } else ids.push(argv[i]);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function api(path, body) {
  const res = await fetch(ui + path, {
    method: body === undefined ? "GET" : "POST",
    headers: { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error ?? res.statusText);
  return data;
}

const NOISE = /message\.part\.delta|session\.(text|reasoning|tool\.input)\.delta|heartbeat|^sync$/;
const KEEP = /^(message|session|permission|question|form|todo|shell)\./;

// Named server-side assertions a scenario lists in serverChecks: (history, run session) => true | reason.
const serverEvents = (history, type) =>
  history.filter((e) => e.type === "server.event" && e.data.type === type && !e.data.source.includes("/global/event")).map((e) => ({ seq: e.seq, ...e.data.props }));
const okCalls = (history, pattern) => history.filter((e) => e.type === "client.call" && e.data.method === "GET" && pattern.test(e.data.path) && e.data.status === 200);
const runningShells = (call, sessionID) => (call.data.response?.data ?? []).filter((x) => x.status === "running" && (!sessionID || x.metadata?.sessionID === sessionID));
const bashParts = (history) => serverEvents(history, "message.part.updated").map((p) => p.part).filter((part) => part?.type === "tool" && part.tool === "bash");
const SERVER_CHECKS = {
  // v1: a running bash part whose metadata.output grows through message.part.updated.
  bashOutputGrows(history) {
    const sizes = bashParts(history).filter((p) => p.state?.status === "running").map((p) => p.state.metadata?.output?.length ?? 0);
    const distinct = [...new Set(sizes)].filter((n) => n > 0);
    if (distinct.length < 5) return `only ${distinct.length} distinct running output sizes`;
    return distinct.every((n, i) => i === 0 || n > distinct[i - 1]) || `output sizes not increasing: ${distinct.slice(0, 8).join(",")}`;
  },
  bashCompleted(history) {
    return bashParts(history).some((p) => p.state?.status === "completed") || "no completed bash part";
  },
  // v2: GET /api/shell listed a running shell of the run's session.
  shellListed(history, sessionID) {
    return okCalls(history, /^\/api\/shell$/).some((c) => runningShells(c, sessionID).length > 0) || `no running shell of ${sessionID} in GET /api/shell`;
  },
  twoShellsListed(history, sessionID) {
    const most = Math.max(0, ...okCalls(history, /^\/api\/shell$/).map((c) => runningShells(c, sessionID).length));
    return most >= 2 || `GET /api/shell listed at most ${most} running shell(s) of the session`;
  },
  // Two output reads of the same shell, the later one larger.
  shellOutputGrows(history) {
    const sizes = new Map();
    for (const c of okCalls(history, /^\/api\/shell\/[^/]+\/output$/)) {
      const id = c.data.path.split("/")[3];
      sizes.set(id, [...(sizes.get(id) ?? []), c.data.response?.data?.size ?? 0]);
    }
    for (const list of sizes.values()) if (list.length >= 2 && list.at(-1) > list[0]) return true;
    return `output did not grow: ${JSON.stringify([...sizes.entries()])}`;
  },
  shellExited(history) {
    return serverEvents(history, "shell.exited").some((p) => p.status === "exited") || "no shell.exited {status: exited}";
  },
  // DELETE /api/shell/:id gives shell.exited {status: killed}, then shell.deleted for the same id.
  shellKilled(history) {
    const deleted = new Set(serverEvents(history, "shell.deleted").map((p) => p.id));
    const killed = serverEvents(history, "shell.exited").filter((p) => p.status === "killed");
    if (!killed.length) return "no shell.exited {status: killed}";
    return killed.every((p) => deleted.has(p.id)) || "a killed shell was not followed by shell.deleted";
  },
  // The turn ended (session idle) while its shell was still running.
  idleWhileShellRuns(history, sessionID) {
    const idle = serverEvents(history, "session.execution.succeeded").find((p) => p.sessionID === sessionID);
    if (!idle) return "the session never went idle";
    const exited = serverEvents(history, "shell.exited")[0];
    return !exited || idle.seq < exited.seq || "the shell exited before the session went idle";
  },
};

const state = await api("/harness/state");
const protocol = state.server?.protocol;
if (state.server?.status !== "ready") throw new Error("opencode is not ready in the harness");
const list = state.scenarios.filter((s) => (ids.length ? ids.includes(s.id) : true) && (priorities.length ? priorities.includes(s.priority) : true));
const plays = list.flatMap((s) => {
  const base = [{ scenario: s, variant: s.checkVariant ?? undefined }];
  if (!withVariants) return base;
  return [...base, ...s.variants.filter((v) => v !== s.checkVariant).map((variant) => ({ scenario: s, variant }))];
});
console.log(`Checking ${plays.length} play(s) on ${protocol} (${state.server.health?.version ?? "?"})\n`);

const results = [];
for (const { scenario, variant } of plays) {
  const name = variant ? `${scenario.id} [${variant}]` : scenario.id;
  if (!scenario.protocols.includes(protocol)) {
    results.push({ name, outcome: `skipped (${scenario.protocols.join("/")} only)` });
    console.log(`■ ${name}: skipped (${scenario.protocols.join("/")} only)\n`);
    continue;
  }
  const before = (await api("/harness/history")).at(-1)?.seq ?? 0;
  const startedAt = Date.now();
  await api("/harness/run", { scenario: scenario.id, variant, drive: Boolean(scenario.prompt) });
  let outcome = "timeout";
  let quietSince = null;
  let gateSince = null;
  const actions = [];
  const answered = new Set();
  while (Date.now() - startedAt < (scenario.checkTimeoutMs ?? 75_000)) {
    await sleep(500);
    const s = await api("/harness/state");
    const autoSteps = s.automation?.steps ?? [];
    const pendingAuto = autoSteps.filter((step) => !step.done).length;
    if (autoSteps.every((step) => step.do !== "replyPermission")) {
      for (const p of s.pending.permissions) {
        if (answered.has(p.id)) continue;
        answered.add(p.id);
        await api("/harness/action", { name: "replyPermission", sessionID: p.sessionID, requestID: p.id, reply: "once" }).catch(() => {});
        actions.push(`allowed ${p.title}`);
      }
    }
    if (autoSteps.every((step) => !/Question$/.test(step.do))) {
      for (const q of s.pending.questions) {
        if (answered.has(q.id)) continue;
        answered.add(q.id);
        const questions = Array.isArray(q.questions) ? q.questions : [];
        const answers = questions.length ? questions.map((item) => [item.options?.[0]?.label ?? "ok"]) : [["ok"]];
        await api("/harness/action", { name: "replyQuestion", sessionID: q.sessionID, requestID: q.id, answers }).catch((e) => actions.push(`question reply failed: ${e.message}`));
        actions.push(`answered ${answers.map((a) => a[0]).join(" + ")}`);
      }
    }
    // A gate nothing will release (hang, pending tool): stop the turn like a user tapping Stop.
    if (s.model.gates.length && pendingAuto === 0) {
      gateSince ??= Date.now();
      if (Date.now() - gateSince > 2000) {
        const run = s.model.runs.find((r) => r.scenario === scenario.id && r.startedAt >= startedAt - 100);
        const sessionID = run?.sessionID ?? (await activeSession(before));
        if (sessionID) await api("/harness/action", { name: "abort", sessionID }).catch((e) => actions.push(`abort failed: ${e.message}`));
        actions.push("aborted held turn");
        gateSince = null;
      }
    } else gateSince = null;
    const run = s.model.runs.find((r) => r.scenario === scenario.id && r.startedAt >= startedAt - 100);
    const pending = s.pending.permissions.length + s.pending.questions.length;
    const settled = !pending && !s.model.gates.length && pendingAuto === 0 && !s.busySessions.length;
    if (!settled) {
      quietSince = null;
      continue;
    }
    quietSince ??= Date.now();
    const quietFor = Date.now() - quietSince;
    if (!scenario.scripted && quietFor > 3000) {
      outcome = "settled";
      break;
    }
    if (run?.status === "finished" && quietFor > 2500) {
      outcome = "finished";
      break;
    }
    if (quietFor > 6000) {
      const unserved = run ? run.mainCalls - run.mainCall + run.children.reduce((n, c) => n + c.calls - c.call, 0) : null;
      const declared = (variant && scenario.variantOptionalCalls?.[variant]) ?? scenario.optionalCalls;
      const optional = typeof declared === "object" ? declared[protocol] ?? 0 : declared;
      if (run && unserved <= optional) outcome = `finished (server ended the turn; ${unserved} optional call(s) unused)`;
      else outcome = run ? `idle with ${unserved} scripted call(s) unserved` : "idle, scenario never started";
      break;
    }
  }
  const history = (await api("/harness/history")).filter((e) => e.seq > before);
  const calls = history.filter((e) => e.type === "model.done").map((e) => `${e.data.label.replace(scenario.id + " ", "")} → ${e.data.outcome}`);
  const counts = new Map();
  for (const e of history) {
    if (e.type !== "server.event" || (protocol === "v1" && e.data.source.includes("/global/event"))) continue;
    if (NOISE.test(e.data.type) || !KEEP.test(e.data.type)) continue;
    counts.set(e.data.type, (counts.get(e.data.type) ?? 0) + 1);
  }
  const errors = history
    .filter((e) => (e.type === "client.call" && e.data.status >= 400) || e.type === "automation.error" || e.type === "model.miss")
    .map((e) => (e.type === "client.call" ? `${e.data.method} ${e.data.path} ${e.data.status}: ${JSON.stringify(e.data.response).slice(0, 200)}` : JSON.stringify(e.data).slice(0, 220)));
  const tools = [
    ...new Set(
      history
        .filter((e) => e.type === "server.event" && !(protocol === "v1" && e.data.source.includes("/global/event")))
        .map((e) => toolLabel(e.data))
        .filter(Boolean),
    ),
  ];
  const harnessSteps = history.filter((e) => e.type === "automation.step" || e.type === "harness.setup").map((e) => e.data.do);
  const sessionID = (await api("/harness/state")).model.runs.find((r) => r.scenario === scenario.id && r.startedAt >= startedAt - 100)?.sessionID;
  const failedChecks = [];
  const passedChecks = [];
  for (const check of scenario.serverChecks?.[protocol] ?? []) {
    const verdict = SERVER_CHECKS[check]?.(history, sessionID) ?? "unknown check";
    if (verdict === true) passedChecks.push(check);
    else failedChecks.push(`${check}: ${verdict}`);
  }
  if (failedChecks.length && (outcome === "settled" || outcome.startsWith("finished"))) outcome = `${outcome}, server checks failed`;
  const ok = (outcome === "settled" || outcome.startsWith("finished")) && !failedChecks.length;
  results.push({ name, outcome, ok, appOnly: scenario.appOnly });
  console.log(`■ ${name}: ${outcome}${scenario.appOnly ? " (needs a device to judge)" : ""}`);
  console.log(`  model: ${calls.join(" | ") || "(no calls)"}`);
  if (harnessSteps.length) console.log(`  harness: ${harnessSteps.join(", ")}`);
  if (actions.length) console.log(`  check: ${[...new Set(actions)].join(", ")}`);
  if (tools.length) console.log(`  tools: ${tools.join(", ")}`);
  if (passedChecks.length) console.log(`  server checks ok: ${passedChecks.join(", ")}`);
  if (failedChecks.length) console.log(`  server checks failed:\n    ${failedChecks.join("\n    ")}`);
  console.log(`  events: ${[...counts.entries()].map(([k, v]) => `${k}×${v}`).join(" ") || "-"}`);
  if (errors.length) console.log(`  errors:\n    ${[...new Set(errors)].join("\n    ")}`);
  console.log("");
}

console.log("Summary:");
for (const r of results) console.log(`  ${r.ok ? "ok  " : r.outcome.startsWith("skipped") ? "skip" : "!!  "} ${r.name}: ${r.outcome}${r.appOnly ? " (app check)" : ""}`);
process.exitCode = results.some((r) => !r.ok && !r.outcome.startsWith("skipped")) ? 1 : 0;

function toolLabel(d) {
  const p = d.props ?? {};
  if (d.type === "message.part.updated" && p.part?.type === "tool") return `${p.part.tool}:${p.part.state?.status}`;
  if (/^session\.tool\.(called|success|failed|error|interrupted|cancelled)$/.test(d.type)) return `${p.name ?? p.tool ?? p.call?.name ?? "tool"}:${d.type.split(".").pop()}`;
  return null;
}

async function activeSession(sinceSeq) {
  const history = (await api("/harness/history")).filter((e) => e.seq > sinceSeq && e.type === "server.event");
  for (const e of history.reverse()) {
    const id = e.data.props?.sessionID;
    if (id) return id;
  }
  return null;
}
