// Fault proxy in front of `opencode serve`. The apps connect here; the harness
// observer connects to opencode directly. Faults act on what the apps see:
// dropping or stalling SSE streams, rejecting auth, refusing connections, and
// adding latency. Every app request is logged for the web UI.

import http from "node:http";

const EVENT_PATHS = new Set(["/global/event", "/event", "/api/event"]);

export function createProxy({ getUpstream, emit }) {
  const streams = new Set(); // { req, res, upstream, path, stalled, startedAt, id }
  let faults = { latencyMs: 0, rejectAuthUntil: 0, rejectSseAuthUntil: 0, offlineUntil: 0, stall: false, heartbeatOnly: false };
  // Event-stream connection attempts, to see how hard an app retries during a fault.
  let sseAttempts = 0;
  let seq = 0;

  function faultState() {
    const now = Date.now();
    return {
      latencyMs: faults.latencyMs,
      rejectAuthFor: Math.max(0, faults.rejectAuthUntil - now),
      rejectSseAuthFor: Math.max(0, faults.rejectSseAuthUntil - now),
      sseAttempts,
      offlineFor: Math.max(0, faults.offlineUntil - now),
      stall: faults.stall,
      heartbeatOnly: faults.heartbeatOnly,
      streams: [...streams].map((s) => ({ id: s.id, path: s.path, ageMs: now - s.startedAt, ua: s.ua })),
    };
  }
  const changed = () => emit("proxy.state", faultState());

  const server = http.createServer(async (req, res) => {
    const id = ++seq;
    const startedAt = Date.now();
    const url = new URL(req.url, "http://proxy");
    const isEvent = req.method === "GET" && EVENT_PATHS.has(url.pathname);
    const ua = String(req.headers["user-agent"] ?? "").slice(0, 60);
    // Which credentials the app sent (user name only), to tell missing auth from a wrong password.
    const authHeader = String(req.headers.authorization ?? "");
    const auth = authHeader.startsWith("Basic ")
      ? `basic:${Buffer.from(authHeader.slice(6), "base64").toString().split(":")[0]}`
      : authHeader ? authHeader.split(" ")[0].toLowerCase() : "none";
    if (Date.now() < faults.offlineUntil) {
      req.socket.destroy();
      emit("app.request", { id, method: req.method, path: url.pathname, status: "refused (offline fault)", ua });
      return;
    }
    if (isEvent) sseAttempts += 1;
    if (faults.latencyMs > 0) await new Promise((resolve) => setTimeout(resolve, faults.latencyMs));
    const sseAuthFault = isEvent && Date.now() < faults.rejectSseAuthUntil;
    if ((Date.now() < faults.rejectAuthUntil && !url.pathname.endsWith("/health")) || sseAuthFault) {
      res.writeHead(401, { "content-type": "application/json", "www-authenticate": 'Basic realm="opencode"' });
      res.end(JSON.stringify({ error: "Unauthorized (harness fault)" }));
      emit("app.request", { id, method: req.method, path: url.pathname + url.search, status: 401, ms: Date.now() - startedAt, ua, fault: true });
      return;
    }
    const upstream = getUpstream();
    if (!upstream) {
      res.writeHead(503, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "harness: opencode is not running" }));
      return;
    }
    const headers = { ...req.headers, host: `127.0.0.1:${upstream.port}` };
    const proxied = http.request(
      { host: "127.0.0.1", port: upstream.port, method: req.method, path: req.url, headers },
      (up) => {
        res.writeHead(up.statusCode ?? 502, up.headers);
        if (isEvent && up.statusCode === 200) {
          const entry = { id, req, res, upstream: up, path: url.pathname + url.search, startedAt, ua };
          streams.add(entry);
          changed();
          emit("app.request", { id, method: req.method, path: url.pathname + url.search, status: "sse open", ua });
          up.on("data", (data) => {
            if (faults.stall) return;
            if (faults.heartbeatOnly) {
              const text = data.toString();
              if (!/heartbeat|server\.connected/.test(text)) return;
            }
            res.write(data);
          });
          const close = () => {
            if (!streams.has(entry)) return;
            streams.delete(entry);
            changed();
            emit("app.request", { id, method: req.method, path: url.pathname, status: "sse closed", ms: Date.now() - startedAt, ua });
          };
          up.on("end", () => {
            res.end();
            close();
          });
          up.on("error", close);
          res.on("close", () => {
            up.destroy();
            close();
          });
          return;
        }
        up.pipe(res);
        up.on("end", () =>
          emit("app.request", { id, method: req.method, path: url.pathname + url.search, status: up.statusCode, ms: Date.now() - startedAt, ua, auth }),
        );
      },
    );
    proxied.on("error", (error) => {
      if (!res.headersSent) res.writeHead(502, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: `harness proxy: ${error.message}` }));
      emit("app.request", { id, method: req.method, path: url.pathname, status: 502, ua });
    });
    req.pipe(proxied);
  });

  // WebSocket upgrades (PTY) pass straight through.
  server.on("upgrade", (req, socket, head) => {
    const upstream = getUpstream();
    if (!upstream) return socket.destroy();
    const proxied = http.request({ host: "127.0.0.1", port: upstream.port, method: req.method, path: req.url, headers: req.headers });
    proxied.on("upgrade", (res, upSocket, upHead) => {
      socket.write(
        `HTTP/1.1 101 Switching Protocols\r\n${Object.entries(res.headers)
          .map(([k, v]) => `${k}: ${v}`)
          .join("\r\n")}\r\n\r\n`,
      );
      if (upHead?.length) socket.write(upHead);
      if (head?.length) upSocket.write(head);
      upSocket.pipe(socket).pipe(upSocket);
    });
    proxied.on("error", () => socket.destroy());
    proxied.end();
  });

  return {
    listen: (port, host) => new Promise((resolve) => server.listen(port, host, resolve)),
    close: () => new Promise((resolve) => server.close(resolve)),
    state: faultState,
    resetCounters() {
      sseAttempts = 0;
      changed();
    },
    dropStreams() {
      for (const entry of [...streams]) {
        entry.upstream.destroy();
        entry.res.destroy();
      }
      changed();
    },
    setFaults(next) {
      const now = Date.now();
      if (next.latencyMs !== undefined) faults.latencyMs = Math.max(0, Number(next.latencyMs) || 0);
      if (next.rejectAuthMs !== undefined) faults.rejectAuthUntil = now + Math.max(0, Number(next.rejectAuthMs) || 0);
      if (next.rejectSseAuthMs !== undefined) {
        faults.rejectSseAuthUntil = now + Math.max(0, Number(next.rejectSseAuthMs) || 0);
        if (next.rejectSseAuthMs > 0) this.dropStreams();
      }
      if (next.offlineMs !== undefined) {
        faults.offlineUntil = now + Math.max(0, Number(next.offlineMs) || 0);
        if (next.offlineMs > 0) this.dropStreams();
      }
      if (next.stall !== undefined) faults.stall = Boolean(next.stall);
      if (next.heartbeatOnly !== undefined) faults.heartbeatOnly = Boolean(next.heartbeatOnly);
      changed();
    },
    clearFaults() {
      faults = { latencyMs: 0, rejectAuthUntil: 0, rejectSseAuthUntil: 0, offlineUntil: 0, stall: false, heartbeatOnly: false };
      changed();
    },
  };
}
