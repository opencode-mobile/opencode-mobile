// Launches a real `opencode serve` (classic v1 binary or v2 from an opencode
// checkout) against a throwaway project, with isolated data/config/state dirs
// and the harness fake model as the only configured provider.

import { spawn, execFileSync } from "node:child_process";
import { mkdirSync, rmSync, writeFileSync, existsSync, readdirSync, cpSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TEMPLATE = path.join(HERE, "project-template");

const MODEL = {
  id: "scripted",
  name: "Scripted (harness)",
};
// Both servers only offer the patch tool to "gpt-*" model ids, so the patch scenario uses this one.
const PATCH_MODEL = {
  id: "gpt-5-scripted",
  name: "Scripted, patch tool (harness)",
};

function v1Model(model) {
  return {
    id: model.id,
    name: model.name,
    attachment: false,
    reasoning: true,
    temperature: false,
    tool_call: true,
    release_date: "2026-01-01",
    limit: { context: 200000, output: 32000 },
    cost: { input: 0, output: 0 },
    options: {},
  };
}

function v1Config(modelURL) {
  return {
    $schema: "https://opencode.ai/config.json",
    model: `harness/${MODEL.id}`,
    small_model: `harness/${MODEL.id}`,
    enabled_providers: ["harness"],
    autoupdate: false,
    share: "disabled",
    formatter: false,
    lsp: false,
    provider: {
      harness: {
        name: "Harness",
        npm: "@ai-sdk/openai-compatible",
        env: [],
        models: {
          [MODEL.id]: v1Model(MODEL),
          [PATCH_MODEL.id]: v1Model(PATCH_MODEL),
        },
        options: { apiKey: "harness", baseURL: modelURL },
      },
    },
    permission: {
      bash: "ask",
      edit: "allow",
      webfetch: "allow",
      external_directory: "ask",
    },
  };
}

function v2Config(modelURL) {
  return {
    $schema: "https://opencode.ai/config.json",
    model: `harness/${MODEL.id}`,
    providers: {
      harness: {
        name: "Harness",
        env: ["HARNESS_API_KEY"],
        package: "@opencode/ai/providers/openai-compatible",
        settings: { baseURL: modelURL },
        compatibility: { reasoningField: "reasoning_content" },
        models: {
          [MODEL.id]: {
            name: MODEL.name,
            capabilities: { reasoning: { supported: true } },
            limit: { context: 200000, output: 32000 },
          },
          [PATCH_MODEL.id]: {
            name: PATCH_MODEL.name,
            capabilities: { reasoning: { supported: true } },
            limit: { context: 200000, output: 32000 },
          },
        },
      },
    },
    permissions: [{ action: "shell", resource: "*", effect: "ask" }],
  };
}

export function seedProject(dir) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
  if (existsSync(TEMPLATE)) cpSync(TEMPLATE, dir, { recursive: true });
  const git = (...args) => execFileSync("git", args, { cwd: dir, stdio: "ignore" });
  git("init", "-q", "-b", "main");
  git("-c", "user.email=harness@example.invalid", "-c", "user.name=Harness", "add", "-A");
  git("-c", "user.email=harness@example.invalid", "-c", "user.name=Harness", "commit", "-q", "-m", "Initial import", "--allow-empty");
}

export function createServer({ stateDir, emit }) {
  let child = null;
  let current = null;

  function dirs(protocol) {
    const root = path.join(stateDir, protocol);
    return {
      root,
      home: path.join(root, "home"),
      data: path.join(root, "xdg-data"),
      config: path.join(root, "xdg-config"),
      state: path.join(root, "xdg-state"),
      project: path.join(stateDir, "projects", "acme-invoices"),
    };
  }

  async function start({ protocol, port, password, modelURL, v1Bin, v2Repo, freshProject }) {
    await stop();
    const d = dirs(protocol);
    for (const dir of [d.data, d.config, d.state, d.home]) mkdirSync(dir, { recursive: true });
    if (freshProject || !existsSync(path.join(d.project, ".git"))) seedProject(d.project);
    // Project config so both protocols pick up the scripted provider.
    const config = protocol === "v1" ? v1Config(modelURL) : v2Config(modelURL);
    const configName = protocol === "v1" ? "opencode.json" : "opencode.jsonc";
    for (const name of ["opencode.json", "opencode.jsonc"]) rmSync(path.join(d.project, name), { force: true });
    writeFileSync(path.join(d.project, configName), JSON.stringify(config, null, 2) + "\n");
    // Keep the harness config out of the session's git status and diffs.
    writeFileSync(path.join(d.project, ".git", "info", "exclude"), "opencode.json\nopencode.jsonc\n");

    const env = {
      ...process.env,
      OPENCODE_SERVER_PASSWORD: password,
      OPENCODE_SERVER_USERNAME: "opencode",
      XDG_DATA_HOME: d.data,
      XDG_CONFIG_HOME: d.config,
      XDG_STATE_HOME: d.state,
      HARNESS_API_KEY: "harness",
      OPENCODE_DISABLE_AUTOUPDATE: "1",
      // v1 gates background subagents behind this flag.
      OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS: "true",
      OPENCODE_DISABLE_MODELS_FETCH: protocol === "v2" ? "1" : "",
    };
    delete env.OPENCODE_PASSWORD;
    let command;
    let args;
    let cwd = d.project;
    if (protocol === "v1") {
      command = v1Bin;
      args = ["serve", "--port", String(port), "--hostname", "127.0.0.1"];
    } else {
      command = "bun";
      // Run from the project so the server's default location is the project, not the opencode checkout.
      // The server graph needs packages/cli's Solid JSX preload (its bunfig.toml), which only
      // resolves from that package, so resolve it there and pass it as an absolute path.
      const cli = path.join(v2Repo, "packages/cli");
      const preload = execFileSync("bun", ["-e", 'console.log(require.resolve("@opentui/solid/preload"))'], { cwd: cli }).toString().trim();
      args = [`--preload=${preload}`, path.join(cli, "src/index.ts"), "serve", "--port", String(port), "--hostname", "127.0.0.1"];
    }
    emit("server.log", { line: `$ ${command} ${args.join(" ")}  (cwd ${cwd})` });
    const proc = spawn(command, args, { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    child = proc;
    current = { protocol, port, project: d.project, pid: proc.pid, startedAt: Date.now(), status: "starting" };
    emit("server.state", current);
    const relay = (stream) => {
      let buffer = "";
      stream.on("data", (data) => {
        buffer += data.toString();
        const lines = buffer.split("\n");
        buffer = lines.pop();
        for (const line of lines) if (line.trim()) emit("server.log", { line: line.slice(0, 2000) });
      });
    };
    relay(proc.stdout);
    relay(proc.stderr);
    proc.on("exit", (code, signal) => {
      if (child === proc) {
        child = null;
        current = { ...current, status: "exited", code, signal };
        emit("server.state", current);
      }
    });
    const auth = "Basic " + Buffer.from(`opencode:${password}`).toString("base64");
    const healthPath = protocol === "v1" ? "/global/health" : "/api/info";
    const deadline = Date.now() + 90_000;
    while (Date.now() < deadline) {
      if (child !== proc) throw new Error("opencode exited during startup; see server log");
      try {
        const res = await fetch(`http://127.0.0.1:${port}${healthPath}`, { headers: { authorization: auth } });
        if (res.ok) {
          current = { ...current, status: "ready", health: await res.json().catch(() => null) };
          emit("server.state", current);
          return current;
        }
      } catch {
        // not listening yet
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
    throw new Error(`opencode did not become healthy on ${healthPath} within 90s`);
  }

  async function stop() {
    const proc = child;
    if (!proc) return;
    child = null;
    proc.kill("SIGTERM");
    await new Promise((resolve) => {
      const timer = setTimeout(() => {
        proc.kill("SIGKILL");
        resolve();
      }, 5000);
      proc.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });
    current = { ...current, status: "stopped" };
    emit("server.state", current);
  }

  return {
    start,
    stop,
    state: () => current,
    dirs,
    projects: () => {
      const dir = path.join(stateDir, "projects");
      return existsSync(dir) ? readdirSync(dir) : [];
    },
  };
}
