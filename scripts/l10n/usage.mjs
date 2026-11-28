// Where each string key is used in the app code: file name and trimmed line.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { ROOT } from "./strings.mjs";

// key → up to `max` "File.kt: line" entries, Android first.
export function usageIndex(units, max = 1) {
  const androidNames = new Map(units.filter((u) => u.android).map((u) => [u.android.name, u.key]));
  const iosKeys = new Set(units.filter((u) => u.ios).map((u) => u.key));
  const index = new Map();
  const add = (key, file, line) => {
    const list = index.get(key) ?? [];
    if (list.length < max) list.push(`${file}: ${line.trim().slice(0, 140)}`);
    index.set(key, list);
  };
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
    for (const line of readFileSync(path, "utf8").split("\n")) {
      for (const m of line.matchAll(/R\.(?:string|plurals)\.(\w+)/g)) {
        const key = androidNames.get(m[1]);
        if (key) add(key, file, line);
      }
    }
  });
  for (const dir of ["ios/OpenCode Mobile/OpenCode Mobile", "ios/OpenCodeKit"]) {
    walk(join(ROOT, dir), ".swift", (path) => {
      const file = path.split("/").pop();
      for (const line of readFileSync(path, "utf8").split("\n")) {
        for (const m of line.matchAll(/"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)"/g)) {
          if (iosKeys.has(m[1])) add(m[1], file, line);
        }
      }
    });
  }
  return index;
}
