// Scripted fake model: an OpenAI-compatible streaming /v1/chat/completions
// endpoint whose answers come from the armed scenario. Modelled on the official
// v1 helper (opencode origin/v1 packages/opencode/test/lib/llm-server.ts):
// title and summary requests are answered automatically, unscripted calls get
// "ok", and calls are routed to a run by session (or first user message), so
// subagent child sessions and extra sessions get their own script lanes.

import { randomUUID } from "node:crypto";

const TITLE_MARKERS = ["You are a title generator", "Generate a title for this conversation"];
const SUMMARY_MARKERS = [
  "Summarize what was done in this conversation",
  "You are a context summarization agent",
  // v2 compaction sends its instructions as a user message, plus a nudge when the template is missed.
  "You MUST summarize the conversation above",
  "did not fill in the required summary template",
];
// v2 only accepts a compaction summary that uses its template headings (core/src/session/compaction.ts).
export const SUMMARY_TEXT =
  "## Objective\nKeep formatMoney correct for every currency.\n\n## Work State\n### Completed\n- The harness ran scripted turns.\n\n## Next Move\n- Continue with the next request.";
const SCENARIO_MARKER = /\bscenario[:\s]+([a-z0-9][a-z0-9-]*)/i;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function messageText(message) {
  const content = message?.content;
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => (typeof part === "string" ? part : part?.text ?? ""))
      .filter(Boolean)
      .join("\n");
  }
  return "";
}

export function classify(body, headers = {}) {
  const messages = Array.isArray(body?.messages) ? body.messages : [];
  const system = messages.filter((m) => m.role === "system").map(messageText).join("\n");
  const users = messages.filter((m) => m.role === "user");
  const raw = JSON.stringify(body ?? {});
  let kind = "turn";
  if (TITLE_MARKERS.some((marker) => system.includes(marker) || raw.includes(marker))) kind = "title";
  else if (SUMMARY_MARKERS.some((marker) => system.includes(marker) || users.some((u) => messageText(u).includes(marker)))) kind = "summary";
  const firstUser = users.length ? messageText(users[0]) : "";
  const lastMessage = messages[messages.length - 1];
  const lastUser = users.length ? messageText(users[users.length - 1]) : "";
  const sessionID = headers["x-opencode-session-id"] ?? headers["x-opencode-session"] ?? headers["x-session-id"] ?? null;
  const firstText = firstUser.trim().slice(0, 400);
  return {
    kind,
    // Route by session when the server says which one (both protocols do); otherwise by the first user message.
    key: sessionID ? `session:${sessionID}` : firstText,
    sessionID,
    firstText,
    userCount: users.length,
    lastIsUser: lastMessage?.role === "user",
    lastUser,
    raw,
    toolResults: messages.filter((m) => m.role === "tool").length,
  };
}

// Resolves {v1, v2} variants and ${PROJECT} / ${HARNESS_URL} placeholders for the active protocol.
export function resolveValue(value, ctx) {
  if (Array.isArray(value)) return value.map((item) => resolveValue(item, ctx));
  if (value && typeof value === "object") {
    const keys = Object.keys(value);
    if (keys.length > 0 && keys.every((key) => key === "v1" || key === "v2")) {
      return resolveValue(value[ctx.protocol] ?? value.v1 ?? value.v2, ctx);
    }
    return Object.fromEntries(keys.map((key) => [key, resolveValue(value[key], ctx)]));
  }
  if (typeof value === "string") return value.replaceAll("${PROJECT}", ctx.project).replaceAll("${HARNESS_URL}", ctx.harnessURL ?? "");
  return value;
}

function chunk(delta, finish, usage) {
  return {
    id: "chatcmpl-harness",
    object: "chat.completion.chunk",
    created: Math.floor(Date.now() / 1000),
    model: "scripted",
    choices: [{ index: 0, delta, ...(finish ? { finish_reason: finish } : { finish_reason: null }) }],
    ...(usage ? { usage } : {}),
  };
}

// Splits by code point (never inside a surrogate pair), so grapheme clusters such as ZWJ emoji
// and flags can still land across chunks, as they do with real providers.
function pieces(text, size) {
  const points = Array.from(text);
  const out = [];
  const step = Math.max(1, size);
  for (let i = 0; i < points.length; i += step) out.push(points.slice(i, i + step).join(""));
  return out.length ? out : [""];
}

export function createModel({ getContext, scenarios, emit }) {
  const runs = new Map(); // run id -> run
  const keys = new Map(); // conversation key -> { run, role: "main" | "child", child? }
  const gates = new Map(); // gate id -> { label, run, resolve }
  const live = new Set(); // in-flight responses, for abort-all
  const latched = new Set(); // gate labels released before their gate opened
  let armed = null; // { scenarioId, armedAt, run }
  let pacing = 1;

  function publicRun(run) {
    return {
      id: run.id,
      scenario: run.scenario.id,
      title: run.scenario.title,
      protocol: run.protocol,
      status: run.status,
      mainCall: run.main.index,
      mainCalls: run.main.calls.length,
      children: [...run.children.values()].map((child) => ({
        id: child.id,
        prompt: child.prompt.slice(0, 80),
        bound: Boolean(child.key),
        call: child.index,
        calls: child.calls.length,
      })),
      startedAt: run.startedAt,
      sessionID: run.sessionID,
    };
  }

  function state() {
    return {
      armed: armed && { scenarioId: armed.scenarioId, armedAt: armed.armedAt },
      pacing,
      runs: [...runs.values()].filter((r) => r.status !== "armed").map(publicRun).reverse().slice(0, 20),
      gates: [...gates.entries()].map(([id, gate]) => ({ id, label: gate.label, run: gate.run })),
    };
  }

  const changed = () => emit("model.state", state());

  // A run exists from the moment it is armed, so lanes with static prompts (extra sessions)
  // can bind before the main conversation does.
  function createRun(scenarioId, override) {
    const base = scenarios.get(scenarioId);
    if (!base) return null;
    const scenario = {
      ...base,
      ...(override?.modelCalls ? { modelCalls: override.modelCalls } : {}),
      ...(override?.summary ? { summary: override.summary } : {}),
    };
    const ctx = getContext();
    const calls = scenario.modelCalls ?? [];
    const run = {
      id: `run_${randomUUID().slice(0, 8)}`,
      scenario,
      protocol: ctx.protocol,
      status: "armed",
      startedAt: null,
      userCount: 0,
      firstText: "",
      sessionID: null,
      main: { calls: calls.filter((call) => !call.child), index: 0 },
      children: new Map(),
      toolSeq: 0,
      inflight: 0,
    };
    for (const call of calls.filter((c) => c.child)) {
      const id = call.child;
      if (!run.children.has(id)) {
        const prompt = scenario.lanes?.[id]?.prompt ?? "";
        run.children.set(id, { id, prompt, key: null, calls: [], index: 0 });
      }
      run.children.get(id).calls.push(call);
    }
    runs.set(run.id, run);
    return run;
  }

  function begin(run) {
    if (run.status !== "armed") return;
    run.status = "running";
    run.startedAt = Date.now();
    if (armed?.run === run) armed = null;
    emit("model.run", { phase: "started", run: publicRun(run) });
    changed();
  }

  function bindMain(run, info) {
    run.userCount = info.userCount;
    run.firstText = info.firstText;
    run.sessionID = info.sessionID;
    keys.set(info.key, { run, role: "main" });
    begin(run);
    return { run, role: "main" };
  }

  function takeArmed() {
    if (!armed) return null;
    const run = armed.run;
    armed = null;
    // A primed run that already bound a lane stays running; the main lane binds to it.
    return run;
  }

  function route(info) {
    const bound = keys.get(info.key);
    const marker = info.lastIsUser ? info.lastUser.match(SCENARIO_MARKER) : null;
    const markerRun = () => (marker && scenarios.has(marker[1]) ? createRun(marker[1]) : null);
    if (bound?.role === "main") {
      // A new turn: more user messages than when bound, or a finished run whose prompt text a new session reuses.
      const isNewPrompt = info.lastIsUser && (info.userCount > bound.run.userCount || bound.run.status === "finished");
      if (isNewPrompt) {
        const run = markerRun() ?? (armed && armed.run.main.calls.length ? takeArmed() : null);
        if (run) return bindMain(run, info);
      }
      return bound;
    }
    if (bound?.role === "child") return bound;
    // A lane's first user message carries its prompt: a subagent prompt or a scenario's extra session.
    for (const run of runs.values()) {
      for (const child of run.children.values()) {
        if (!child.key && child.prompt && info.firstText.includes(child.prompt.slice(0, 120))) {
          child.key = info.key;
          const entry = { run, role: "child", child };
          keys.set(info.key, entry);
          begin(run);
          changed();
          return entry;
        }
      }
    }
    const run = markerRun() ?? (armed && armed.run.main.calls.length ? takeArmed() : null);
    if (run) return bindMain(run, info);
    return null;
  }

  function nextCall(entry) {
    const lane = entry.role === "main" ? entry.run.main : entry.child;
    const call = lane.calls[lane.index];
    if (!call) return null;
    lane.index += 1;
    return call;
  }

  // A run is finished once every scripted call has been served and none is still streaming.
  function settle(run) {
    const doneMain = run.main.index >= run.main.calls.length;
    const doneChildren = [...run.children.values()].every((c) => c.index >= c.calls.length);
    const doneSummary = !run.scenario.summary || run.summaryServed;
    if (doneMain && doneChildren && doneSummary && run.inflight === 0 && run.status === "running") {
      run.status = "finished";
      emit("model.run", { phase: "finished", run: publicRun(run) });
    }
  }

  function waitGate(label, run, signal) {
    if (latched.delete(label)) return Promise.resolve("released");
    const id = `gate_${randomUUID().slice(0, 6)}`;
    return new Promise((resolve) => {
      const finish = (how) => {
        gates.delete(id);
        changed();
        resolve(how);
      };
      gates.set(id, { label, run: run?.id, resolve: finish });
      signal.addEventListener("abort", () => gates.has(id) && finish("aborted"), { once: true });
      changed();
    });
  }

  async function stream(res, steps, ctx) {
    const { run, signal } = ctx;
    const send = (text, splitBytes) => {
      if (signal.aborted || res.writableEnded) return false;
      if (!splitBytes) {
        res.write(text);
        return true;
      }
      // Cut the frame mid-way through its bytes (inside a multi-byte character when there is one),
      // so the server's SSE decoder has to reassemble it.
      const bytes = Buffer.from(text);
      let cut = Math.floor(bytes.length / 2);
      for (let i = 0; i < bytes.length; i += 1) {
        if (bytes[i] >= 0xc0) {
          cut = i + 1;
          break;
        }
      }
      res.write(bytes.subarray(0, cut));
      res.write(bytes.subarray(cut));
      return true;
    };
    const write = (payload, splitBytes) => send(`data: ${JSON.stringify(payload)}\n\n`, splitBytes);
    const first = steps[0];
    if (first?.op === "httpError") {
      res.writeHead(first.status ?? 500, { "content-type": "application/json", ...(first.headers ?? {}) });
      res.end(JSON.stringify(first.body ?? { error: { message: "harness error", type: "server_error" } }));
      return `http-error ${first.status ?? 500}`;
    }
    res.writeHead(200, {
      "content-type": "text/event-stream",
      "cache-control": "no-cache",
      connection: "keep-alive",
    });
    write(chunk({ role: "assistant", content: "" }));
    let toolIndex = 0;
    let finish = null;
    let outputChars = 0;
    let usageOverride = null;
    const startTool = (step) => {
      run.toolSeq += 1;
      const id = `call_${run.id.slice(4)}_${run.toolSeq}`;
      const name = resolveValue(step.name, ctx.resolve);
      const input = resolveValue(step.input ?? {}, ctx.resolve);
      if (entryIsSubagent(name) && ctx.registerChild) ctx.registerChild(input, step.child);
      const index = toolIndex;
      toolIndex += 1;
      write(chunk({ tool_calls: [{ index, id, type: "function", function: { name, arguments: "" } }] }));
      return { index, args: JSON.stringify(input) };
    };
    const streamArgs = async (step, index, args) => {
      const delay = Math.round((step.argDelayMs ?? Math.min(step.delayMs ?? 25, 40)) * pacing);
      for (const piece of pieces(args, step.argChunkChars ?? Math.max(16, Math.ceil(args.length / 4)))) {
        if (!write(chunk({ tool_calls: [{ index, function: { arguments: piece } }] }))) return false;
        if (delay > 0) await sleep(delay);
      }
      return true;
    };
    for (const step of steps) {
      if (signal.aborted) return "aborted";
      const delay = Math.round((step.delayMs ?? 25) * pacing);
      switch (step.op) {
        case "text":
        case "reason": {
          const text = String(step.value ?? "");
          outputChars += text.length;
          for (const piece of pieces(text, step.chunkChars ?? 8)) {
            const delta = step.op === "text" ? { content: piece } : { reasoning_content: piece };
            if (!write(chunk(delta), step.splitBytes)) return "aborted";
            if (delay > 0) await sleep(delay);
          }
          break;
        }
        case "tool": {
          const { index, args } = startTool(step);
          if (!(await streamArgs(step, index, args))) return "aborted";
          finish = "tool_calls";
          break;
        }
        case "pendingTool": {
          // Streams half the arguments, then holds until released or aborted (official pendingTool + hang).
          const { index, args } = startTool(step);
          if (!(await streamArgs(step, index, args.slice(0, Math.max(1, Math.floor(args.length / 2)))))) return "aborted";
          const how = await waitGate(step.label ?? "pending tool (release to finish the arguments)", run, signal);
          if (how === "aborted") return "aborted";
          if (!(await streamArgs(step, index, args.slice(Math.max(1, Math.floor(args.length / 2)))))) return "aborted";
          finish = "tool_calls";
          break;
        }
        case "raw": {
          if (!send(String(step.chunk ?? ""))) return "aborted";
          break;
        }
        case "usage":
          usageOverride = { input: step.input ?? 1200, output: step.output ?? 0 };
          break;
        case "wait": {
          if (step.gate) {
            const how = await waitGate(step.gate, run, signal);
            if (how === "aborted") return "aborted";
          } else {
            await sleep(Math.round((step.ms ?? 1000) * pacing));
          }
          break;
        }
        case "hang": {
          const how = await waitGate(step.label ?? "hang (release continues the stream)", run, signal);
          if (how === "aborted") return "aborted";
          break;
        }
        case "streamError": {
          res.destroy(new Error("harness stream error"));
          return "stream-error";
        }
        case "finish":
          finish = step.reason;
          break;
        default:
          break;
      }
    }
    const promptTokens = usageOverride?.input ?? 1200;
    const completionTokens = usageOverride?.output || Math.max(1, Math.ceil(outputChars / 4));
    const usage = { prompt_tokens: promptTokens, completion_tokens: completionTokens, total_tokens: promptTokens + completionTokens };
    write(chunk({}, finish ?? "stop", usage));
    if (!signal.aborted && !res.writableEnded) {
      res.write("data: [DONE]\n\n");
      res.end();
    }
    return finish ?? "stop";
  }

  function entryIsSubagent(name) {
    return name === "task" || name === "subagent";
  }

  // The run a summary or compaction request belongs to: the session's own run, else an armed run that scripts one.
  function summaryRun(info) {
    const bound = keys.get(info.key)?.run;
    if (bound?.scenario.summary && !bound.summaryServed) return bound;
    if (armed?.run.scenario.summary) {
      const run = armed.run;
      run.sessionID = info.sessionID;
      keys.set(info.key, { run, role: "main" });
      begin(run);
      return run;
    }
    return null;
  }

  async function handle(req, res) {
    const url = new URL(req.url, "http://localhost");
    let text = "";
    for await (const part of req) text += part;
    let body = {};
    try {
      body = JSON.parse(text || "{}");
    } catch {
      body = {};
    }
    if (req.method === "GET" && url.pathname.endsWith("/models")) {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ object: "list", data: [{ id: "scripted", object: "model", owned_by: "harness" }] }));
      return;
    }
    if (!url.pathname.endsWith("/chat/completions")) {
      emit("model.miss", { path: url.pathname, method: req.method });
      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: { message: `harness model does not serve ${url.pathname}` } }));
      return;
    }

    const info = classify(body, req.headers);
    const controller = new AbortController();
    const onClose = () => !res.writableFinished && controller.abort();
    res.on("close", onClose);
    live.add(controller);
    const ctx = getContext();
    const startedAt = Date.now();
    let steps;
    let label;
    let entry = null;
    let summaryFor = null;
    if (info.kind === "title") {
      const match = [...runs.values()].reverse().find((run) => run.firstText && info.raw.includes(run.firstText.slice(0, 80)));
      // The title request can arrive before the turn binds a run, so fall back to the armed scenario.
      const scenario = match?.scenario ?? (armed ? armed.run.scenario : null);
      const title = scenario?.sessionTitle ?? scenario?.title ?? "Harness session";
      steps = [{ op: "text", value: title, chunkChars: 64, delayMs: 0 }];
      label = "auto title";
    } else if (info.kind === "summary") {
      summaryFor = summaryRun(info);
      if (summaryFor) {
        summaryFor.summaryServed = true;
        steps = summaryFor.scenario.summary;
        label = `${summaryFor.scenario.id} summary`;
      } else {
        steps = [{ op: "text", value: SUMMARY_TEXT, chunkChars: 40, delayMs: 5 }];
        label = "auto summary";
      }
    } else {
      entry = route(info);
      const call = entry ? nextCall(entry) : null;
      if (call) {
        steps = call.steps;
        label = `${entry.run.scenario.id} ${entry.role === "child" ? `child ${entry.child.id}` : "main"} call ${entry.role === "main" ? entry.run.main.index : entry.child.index}`;
      } else {
        steps = [{ op: "text", value: entry ? "Done." : "ok", chunkChars: 16, delayMs: 10 }];
        label = entry ? `${entry.run.scenario.id} (script exhausted)` : "unscripted";
      }
    }
    const owner = entry?.run ?? summaryFor;
    emit("model.call", {
      kind: info.kind,
      label,
      run: owner?.id,
      sessionID: info.sessionID,
      userCount: info.userCount,
      toolResults: info.toolResults,
      lastUser: info.lastIsUser ? info.lastUser.slice(0, 160) : null,
      tools: Array.isArray(body.tools) ? body.tools.map((t) => t.function?.name ?? t.name).filter(Boolean) : [],
      model: body.model,
      systemHead: (Array.isArray(body.messages) ? body.messages : [])
        .filter((m) => m.role === "system")
        .map(messageText)
        .join(" ")
        .slice(0, 100),
    });
    const run = owner ?? { id: "aux", toolSeq: 0 };
    const registerChild = (input, childId) => {
      if (!entry) return;
      const prompt = String(input.prompt ?? "");
      const target =
        (childId && entry.run.children.get(childId)) ||
        [...entry.run.children.values()].find((c) => !c.prompt);
      if (target) target.prompt = prompt;
      changed();
    };
    let outcome;
    if (owner) owner.inflight += 1;
    try {
      outcome = await stream(res, steps, {
        run,
        signal: controller.signal,
        resolve: { protocol: ctx.protocol, project: ctx.project, harnessURL: ctx.harnessURL },
        registerChild,
      });
    } catch (error) {
      outcome = `error: ${error.message}`;
      if (!res.headersSent) res.writeHead(500);
      if (!res.writableEnded) res.end();
    } finally {
      live.delete(controller);
      res.off("close", onClose);
      if (owner) {
        owner.inflight -= 1;
        settle(owner);
      }
    }
    emit("model.done", { label, run: owner?.id, outcome, ms: Date.now() - startedAt });
    changed();
  }

  return {
    handle,
    state,
    arm(scenarioId, override) {
      if (scenarioId && !scenarios.has(scenarioId)) throw new Error(`unknown scenario ${scenarioId}`);
      if (armed?.run.status === "armed") runs.delete(armed.run.id);
      latched.clear();
      armed = scenarioId ? { scenarioId, armedAt: Date.now(), run: createRun(scenarioId, override) } : null;
      changed();
    },
    // Releases one gate by id, every gate with a label, or all gates.
    release(target) {
      const ids = target
        ? [...gates.entries()].filter(([id, gate]) => id === target || gate.label === target).map(([id]) => id)
        : [...gates.keys()];
      for (const id of ids) gates.get(id)?.resolve("released");
      // A named release that arrives before its gate opens holds until that gate is reached.
      if (target && !ids.length && !target.startsWith("gate_")) latched.add(target);
      changed();
      return ids.length;
    },
    setPacing(value) {
      pacing = Math.max(0, Number(value) || 1);
      changed();
    },
    abortAll() {
      for (const controller of live) controller.abort();
    },
    reset() {
      armed = null;
      latched.clear();
      runs.clear();
      keys.clear();
      for (const gate of gates.values()) gate.resolve("aborted");
      gates.clear();
      changed();
    },
  };
}
