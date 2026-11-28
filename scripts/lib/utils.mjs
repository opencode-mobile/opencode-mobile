import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parse as parseYaml } from "yaml";

export const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "../..");

/** Catalog stroke width in the 24×24 Lucide viewBox. */
export const ICON_STROKE_WIDTH = 1.5;

function snakeToKebab(name) {
  return name.replaceAll("_", "-");
}

export function loadManifest() {
  const raw = readFileSync(`${REPO_ROOT}/icons/manifest.yaml`, "utf8");
  const doc = parseYaml(raw);
  if (!doc?.icons?.length) {
    throw new Error("icons/manifest.yaml must define a non-empty icons list");
  }
  return doc.icons.map((entry) => {
    const name = entry.name;
    if (!name) throw new Error("Each manifest icon requires name");
    const lucide = entry.lucide ?? snakeToKebab(name);
    return {
      name,
      lucide,
      source: entry.source ?? "lucide",
      iosAssetName: entry.ios_asset_name ?? `lucide_${name}`,
      notes: entry.notes ?? null,
      mirrorsInRtl: parseMirrorsInRtl(entry),
    };
  });
}

function parseMirrorsInRtl(entry) {
  const value = entry.mirrors_in_rtl ?? false;
  if (typeof value !== "boolean") {
    throw new Error(`mirrors_in_rtl must be true or false (catalog name ${entry.name})`);
  }
  return value;
}

/** One line per icon in icons/.manifest-hash; any generated-output input belongs here. */
export function manifestHash(icons) {
  return (
    icons
      .map((i) => `${i.source}/${i.lucide}/${i.name}:${i.iosAssetName}${i.mirrorsInRtl ? ":mirrors-in-rtl" : ""}`)
      .join("\n") + "\n"
  );
}

export function toPascalCase(snake) {
  return snake
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join("");
}

export function lucideSvgSourcePath(svgPackageRoot, lucideName) {
  return join(svgPackageRoot, "icons", `${lucideName}.svg`);
}
