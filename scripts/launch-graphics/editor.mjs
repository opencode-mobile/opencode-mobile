#!/usr/bin/env node
/**
 * `npm run graphics:editor` — serve the launch graphics editor and open it.
 *   --port <n>      default 8960
 *   --shots <dir>   store-screenshots layout or flat <device>_<screen>_<mode>.png (default <repo>/store-screenshots)
 *   --preset <name> open a preset from scripts/launch-graphics/presets/
 *   --no-open       do not open a browser
 */
import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { parseArgs } from "node:util";
import { DEFAULT_SHOTS_DIR, startServer } from "./server.mjs";

const { values } = parseArgs({
  options: {
    port: { type: "string", default: "8960" },
    shots: { type: "string", default: DEFAULT_SHOTS_DIR },
    preset: { type: "string" },
    "no-open": { type: "boolean", default: false },
    help: { type: "boolean", short: "h", default: false },
  },
});
if (values.help) {
  console.log("Usage: npm run graphics:editor -- [--port 8960] [--shots <dir>] [--preset <name>] [--no-open]");
  process.exit(0);
}
const shotsDir = resolve(values.shots);
if (!existsSync(shotsDir)) console.warn(`warning: no screenshots at ${shotsDir} — run \`npm run store:screenshots\` or pass --shots <dir>. Devices will render empty.`);

const server = await startServer({ port: Number(values.port), shotsDir });
const url = `${server.url}/${values.preset ? `?preset=${encodeURIComponent(values.preset)}` : ""}`;
console.log(`Launch graphics editor: ${url}`);
console.log(`Screenshots: ${shotsDir}`);
console.log("Ctrl+C to stop.");
if (!values["no-open"] && process.platform === "darwin") execFile("open", [url], () => {});
const stop = async () => {
  await server.close();
  process.exit(0);
};
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
