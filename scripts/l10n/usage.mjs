// Where each string key is used in the app code: file name and trimmed line.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./strings.mjs";

// key → up to `max` "File.kt › Enclosing: line" entries, Android first. The enclosing function
// or view name says more than the line alone ("title = stringResource(…)").
export function usageIndex(units, max = 1) {
  const androidNames = new Map(units.filter((u) => u.android).map((u) => [u.android.name, u.key]));
  const iosKeys = new Set(units.filter((u) => u.ios).map((u) => u.key));
  const index = new Map();
  const add = (key, file, owner, line) => {
    const list = index.get(key) ?? [];
    if (list.length < max) list.push(`${file}${owner ? ` › ${owner}` : ""}: ${line.trim().slice(0, 140)}`);
    index.set(key, list);
  };
  // The latest function, computed view or type declared above a line.
  const declaration = /\b(?:fun|func|struct|class|enum|object)\s+(?:[\w.<>]+\.)?(\w+)|\b(?:var|let|val)\s+(\w+)\s*:\s*some\s+View/;
  const walk = (dir, ext, visit) => {
    for (const name of readdirSync(dir)) {
      if (name === "build" || name.startsWith(".")) continue;
      const path = join(dir, name);
      if (statSync(path).isDirectory()) walk(path, ext, visit);
      else if (name.endsWith(ext)) visit(path);
    }
  };
  walk(join(ROOT, "android/app/src/main/java"), ".kt", (path) => {
    const file = path.split("/").pop();
    let owner = null;
    for (const line of readFileSync(path, "utf8").split("\n")) {
      const d = line.match(declaration);
      if (d) owner = d[1] ?? d[2];
      for (const m of line.matchAll(/R\.(?:string|plurals)\.(\w+)/g)) {
        const key = androidNames.get(m[1]);
        if (key) add(key, file, owner, line);
      }
    }
  });
  for (const dir of ["ios/OpenCode Mobile/OpenCode Mobile", "ios/OpenCodeKit"]) {
    walk(join(ROOT, dir), ".swift", (path) => {
      const file = path.split("/").pop();
      let owner = null;
      for (const line of readFileSync(path, "utf8").split("\n")) {
        const d = line.match(declaration);
        if (d) owner = d[1] ?? d[2];
        for (const m of line.matchAll(/"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)"/g)) {
          if (iosKeys.has(m[1])) add(m[1], file, owner, line);
        }
      }
    });
  }
  return index;
}
