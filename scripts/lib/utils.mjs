import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parse as parseYaml } from "yaml";

export const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "../..");

export function loadManifest() {
  const raw = readFileSync(`${REPO_ROOT}/icons/manifest.yaml`, "utf8");
  const doc = parseYaml(raw);
  if (!doc?.icons?.length) {
    throw new Error("icons/manifest.yaml must define a non-empty icons list");
  }
  return doc.icons.map((entry) => {
    const name = entry.name;
    const style = entry.style ?? "filled";
    if (!name) throw new Error("Each manifest icon requires name");
    return {
      name,
      style,
      source: entry.source ?? "material_icons",
      iosAssetName: entry.ios_asset_name ?? `md_${name}`,
      notes: entry.notes ?? null,
    };
  });
}

export function toPascalCase(snake) {
  return snake
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join("");
}

export function svgSourcePath(svgPackageRoot, style, name) {
  return `${svgPackageRoot}/${style}/${name}.svg`;
}
