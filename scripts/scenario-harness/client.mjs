// "Another client": REST actions the web UI and scenarios run against
// opencode, plus the observer that taps the server's own SSE streams.
// v1 = classic unprefixed routes, directory-scoped; v2 = /api/*.

export function createClient({ getServer, password, emit }) {
  const auth = "Basic " + Buffer.from(`opencode:${password}`).toString("base64");
  let observers = [];
  const pending = { permissions: new Map(), questions: new Map() };
  // Busy/idle per session, from either protocol's events; used to wait for a turn to settle.
  const busy = new Map();
  const idleWaiters = new Set();

  function base() {
    const server = getServer();
    if (!server || server.status !== "ready") throw new Error("opencode is not running");
    return server;
  }

  async function call(method, path, body, { query } = {}) {
    const server = base();
    const url = new URL(`http://127.0.0.1:${server.port}${path}`);
    if (server.protocol === "v1") url.searchParams.set("directory", server.project);
    for (const [key, value] of Object.entries(query ?? {})) url.searchParams.set(key, value);
    const res = await fetch(url, {
      method,
      headers: {
        authorization: auth,
        "content-type": "application/json",
        ...(server.protocol === "v1" ? { "x-opencode-directory": server.project } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let data = text;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      // keep text
    }
    emit("client.call", { method, path, status: res.status, body, response: typeof data === "string" ? data.slice(0, 400) : data });
    if (!res.ok) throw new Error(`${method} ${path} -> ${res.status}: ${typeof data === "string" ? data : JSON.stringify(data)}`);
    return data;
  }

  const unwrap = (value) => (value && typeof value === "object" && "data" in value ? value.data : value);

  const actions = {
    async listSessions() {
      const { protocol, project } = base();
      if (protocol === "v1") return call("GET", "/session");
      const list = unwrap(await call("GET", "/api/session", undefined, { query: { directory: project } }));
      return list;
    },
    async createSession({ title, model } = {}) {
      const { protocol, project } = base();
      if (protocol === "v1") return call("POST", "/session", title ? { title } : {});
      return unwrap(
        await call("POST", "/api/session", {
          ...(title ? { title } : {}),
          ...(model ? { model: { providerID: "harness", id: model } } : {}),
          location: { directory: project },
        }),
      );
    },
    async prompt({ sessionID, text, model }) {
      const { protocol } = base();
      busy.set(sessionID, true);
      if (protocol === "v1") {
        return call("POST", `/session/${sessionID}/prompt_async`, {
          parts: [{ type: "text", text }],
          model: { providerID: "harness", modelID: model ?? "scripted" },
        });
      }
      if (model) await call("POST", `/api/session/${sessionID}/model`, { model: { providerID: "harness", id: model } });
      return unwrap(await call("POST", `/api/session/${sessionID}/prompt`, { text }));
    },
    async messages({ sessionID }) {
      const { protocol } = base();
      if (protocol === "v1") return call("GET", `/session/${sessionID}/message`);
      return unwrap(await call("GET", `/api/session/${sessionID}/message`));
    },
    // v1 compacts with POST /summarize; v2 with POST /compact.
    async compact({ sessionID, model }) {
      const { protocol } = base();
      busy.set(sessionID, true);
      if (protocol === "v1") return call("POST", `/session/${sessionID}/summarize`, { providerID: "harness", modelID: model ?? "scripted" });
      return unwrap(await call("POST", `/api/session/${sessionID}/compact`, {}));
    },
    // Reverts to the n-th user message (1-based). v1 applies it at once; v2 stages it.
    async revert({ sessionID, userTurn }) {
      const { protocol } = base();
      const list = await actions.messages({ sessionID });
      const users = (Array.isArray(list) ? list : list?.items ?? []).filter((m) => (m.info?.role ?? m.role ?? m.type) === "user");
      const target = users[(userTurn ?? 1) - 1];
      const messageID = target?.info?.id ?? target?.id;
      if (!messageID) throw new Error(`no user message #${userTurn} to revert to`);
      if (protocol === "v1") return call("POST", `/session/${sessionID}/revert`, { messageID });
      return unwrap(await call("POST", `/api/session/${sessionID}/revert/stage`, { messageID }));
    },
    async revertCommit({ sessionID }) {
      const { protocol } = base();
      if (protocol === "v1") return { skipped: "v1 has no commit step; the revert is applied when the next prompt is sent" };
      return call("POST", `/api/session/${sessionID}/revert/commit`, {});
    },
    async revertClear({ sessionID }) {
      const { protocol } = base();
      if (protocol === "v1") return call("POST", `/session/${sessionID}/unrevert`, {});
      return call("DELETE", `/api/session/${sessionID}/revert`);
    },
    // Resolves once the session is idle (or after timeoutMs).
    waitIdle({ sessionID, timeoutMs = 30000 }) {
      if (!busy.get(sessionID)) return Promise.resolve(true);
      return new Promise((resolve) => {
        const waiter = { sessionID, resolve };
        idleWaiters.add(waiter);
        setTimeout(() => {
          if (idleWaiters.delete(waiter)) resolve(false);
        }, timeoutMs);
      });
    },
    async abort({ sessionID }) {
      const { protocol } = base();
      if (protocol === "v1") return call("POST", `/session/${sessionID}/abort`, {});
      return call("POST", `/api/session/${sessionID}/interrupt`);
    },
    async rename({ sessionID, title }) {
      const { protocol } = base();
      if (protocol === "v1") return call("PATCH", `/session/${sessionID}`, { title });
      return call("PATCH", `/api/session/${sessionID}`, { title });
    },
    async remove({ sessionID }) {
      const { protocol } = base();
      if (protocol === "v1") return call("DELETE", `/session/${sessionID}`);
      return call("DELETE", `/api/session/${sessionID}`);
    },
    async replyPermission({ sessionID, requestID, reply }) {
      const { protocol } = base();
      if (protocol === "v1") return call("POST", `/permission/${requestID}/reply`, { reply });
      return call("POST", `/api/session/${sessionID}/permission/${requestID}/reply`, { decision: reply });
    },
    async replyQuestion({ sessionID, requestID, answers }) {
      const { protocol } = base();
      if (protocol === "v1") return call("POST", `/question/${requestID}/reply`, { answers });
      // v2 forms answer by field key: {q0: "Argument"}; options answer with their value.
      const fields = pending.questions.get(requestID)?.questions ?? [];
      const answer = {};
      answers.forEach((picked, index) => {
        const field = fields[index] ?? { key: `q${index}`, type: "string" };
        const values = picked.map((label) => field.options?.find((o) => o.label === label)?.value ?? label);
        answer[field.key] = field.type === "multiselect" ? values : values[0];
      });
      return call("POST", `/api/session/${sessionID}/form/${requestID}/reply`, { answer });
    },
    async rejectQuestion({ sessionID, requestID }) {
      const { protocol } = base();
      if (protocol === "v1") return call("POST", `/question/${requestID}/reject`, {});
      return call("DELETE", `/api/session/${sessionID}/form/${requestID}`);
    },
    async pendingPermissions() {
      const { protocol } = base();
      if (protocol === "v1") return call("GET", "/permission");
      return unwrap(await call("GET", "/api/permission/request"));
    },
    // Agent-run shells (v2 only; v1 shells are just running bash tool parts).
    // GET /api/shell lists running shells; keep the given session's when sessionID is set.
    async listShells({ sessionID } = {}) {
      const { protocol, project } = base();
      if (protocol === "v1") return { skipped: "v1 has no shell API; shells are running bash tool parts" };
      const list = unwrap(await call("GET", "/api/shell", undefined, { query: { "location[directory]": project } })) ?? [];
      return sessionID ? list.filter((shell) => shell.metadata?.sessionID === sessionID) : list;
    },
    async shellOutput({ id, cursor, limit }) {
      const { protocol, project } = base();
      if (protocol === "v1") return { skipped: "v1 has no shell API" };
      const query = { "location[directory]": project };
      if (cursor !== undefined) query.cursor = String(cursor);
      if (limit !== undefined) query.limit = String(limit);
      return unwrap(await call("GET", `/api/shell/${id}/output`, undefined, { query }));
    },
    async stopShell({ id }) {
      const { protocol, project } = base();
      if (protocol === "v1") return { skipped: "v1 cannot stop one shell; abort the session instead" };
      return call("DELETE", `/api/shell/${id}`, undefined, { query: { "location[directory]": project } });
    },
    // Reads each running shell of the session from the start (one page each).
    async readShellOutputs({ sessionID }) {
      const shells = await actions.listShells({ sessionID });
      if (!Array.isArray(shells)) return shells;
      return Promise.all(shells.map(async (shell) => ({ id: shell.id, ...(await actions.shellOutput({ id: shell.id })) })));
    },
    async stopShells({ sessionID }) {
      const shells = await actions.listShells({ sessionID });
      if (!Array.isArray(shells)) return shells;
      for (const shell of shells) await actions.stopShell({ id: shell.id });
      return { stopped: shells.map((shell) => shell.id) };
    },
    busySessions() {
      return [...busy.entries()].filter(([, value]) => value).map(([id]) => id);
    },
    pending() {
      return {
        permissions: [...pending.permissions.values()],
        questions: [...pending.questions.values()],
      };
    },
  };

  function markBusy(sessionID, value) {
    if (!sessionID) return;
    busy.set(sessionID, value);
    if (value) return;
    for (const waiter of [...idleWaiters]) {
      if (waiter.sessionID !== sessionID) continue;
      idleWaiters.delete(waiter);
      waiter.resolve(true);
    }
  }

  // Tracks pending asks and session busy state from the event stream.
  function track(type, props) {
    if (type === "session.status") markBusy(props.sessionID, (props.status?.type ?? props.status) !== "idle");
    else if (type === "session.idle") markBusy(props.sessionID, false);
    else if (type === "session.execution.started") markBusy(props.sessionID, true);
    else if (/^session\.execution\.(succeeded|failed|interrupted|cancelled)$/.test(type)) markBusy(props.sessionID, false);
    if (type === "permission.asked") {
      const id = props.id ?? props.requestID ?? props.permission?.id;
      if (id) pending.permissions.set(id, { id, sessionID: props.sessionID ?? props.permission?.sessionID, title: props.permission?.title ?? props.title ?? props.permission ?? props.action, patterns: props.patterns ?? props.resources, raw: props });
    } else if (type === "permission.replied") {
      pending.permissions.delete(props.requestID ?? props.id ?? props.permissionID);
    } else if (type === "question.asked" || type === "form.created") {
      const form = props.form ?? props;
      const id = form.id;
      if (id) pending.questions.set(id, { id, sessionID: form.sessionID, questions: form.questions ?? form.fields ?? form, raw: props });
    } else if (type === "question.replied" || type === "question.rejected" || type === "form.replied" || type === "form.cancelled") {
      pending.questions.delete(props.requestID ?? props.id);
    } else {
      return;
    }
    emit("client.pending", actions.pending());
  }

  async function observe(path, label, signal) {
    const server = base();
    while (!signal.aborted) {
      try {
        const res = await fetch(`http://127.0.0.1:${server.port}${path}`, {
          headers: { authorization: auth, accept: "text/event-stream" },
          signal,
        });
        if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
        emit("observer.state", { label, status: "open" });
        const decoder = new TextDecoder();
        let buffer = "";
        for await (const bytes of res.body) {
          buffer += decoder.decode(bytes, { stream: true });
          let index;
          while ((index = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, index);
            buffer = buffer.slice(index + 2);
            const data = frame
              .split("\n")
              .filter((line) => line.startsWith("data:"))
              .map((line) => line.slice(5).trimStart())
              .join("\n");
            if (!data) continue;
            let event;
            try {
              event = JSON.parse(data);
            } catch {
              continue;
            }
            // v1 /global/event wraps {directory, payload}; v2 and v1 /event are flat.
            const payload = event.payload ?? event;
            const type = payload.type ?? "unknown";
            const props = payload.properties ?? payload.data ?? payload;
            track(type, props);
            emit("server.event", { source: label, type, props, directory: event.directory });
          }
        }
        emit("observer.state", { label, status: "ended" });
      } catch (error) {
        if (signal.aborted) return;
        emit("observer.state", { label, status: `error: ${error.message}` });
      }
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
  }

  return {
    actions,
    startObservers() {
      stopObservers();
      const server = base();
      pending.permissions.clear();
      // A restarted (or protocol-switched) server never reports idle for the old sessions.
      busy.clear();
      for (const waiter of idleWaiters) waiter.resolve(false);
      idleWaiters.clear();
      pending.questions.clear();
      const controller = new AbortController();
      const paths =
        server.protocol === "v1"
          ? [
              ["/global/event", "v1 /global/event"],
              [`/event?directory=${encodeURIComponent(server.project)}`, "v1 /event"],
            ]
          : [["/api/event", "v2 /api/event"]];
      observers = [controller];
      for (const [path, label] of paths) observe(path, label, controller.signal);
    },
    stopObservers,
  };

  function stopObservers() {
    for (const controller of observers) controller.abort();
    observers = [];
  }
}
