#!/usr/bin/env node
// Generates the app launcher icon set for both platforms from
// app-icon/source.svg. Emits Android adaptive-icon vectors, iOS AppIcon
// PNGs (any/dark/tinted), and the iOS 26 AppIcon.icon Liquid Glass package.
// See docs/design-system/app-icon.md.
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync, mkdirSync, rmSync, unlinkSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { tmpdir } from "node:os";
import { randomBytes } from "node:crypto";
import { applyCircularAlpha, decodePng, encodePng } from "./lib/png-bytes.mjs";
import {
  GLASS_FILL,
  SOURCE_SVG,
  buildFlattenedSvg,
  buildSilhouetteSvg,
  buildSquareSvg,
  deriveInsetSquareTransform,
  deriveSquareTransform,
  ensureRsvg,
  parseSource,
  renderPng,
  svgTransformToAndroid,
} from "./lib/app-icon-source.mjs";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const IOS_APPICONSET = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/Assets.xcassets/AppIcon.appiconset");
const IOS_ICON_COMPOSER = join(REPO_ROOT, "ios/OpenCode Mobile/OpenCode Mobile/AppIcon.icon");
const ANDROID_RES = join(REPO_ROOT, "android/app/src/main/res");

function fail(msg) {
  console.error(`app-icon: ${msg}`);
  process.exit(1);
}

function hexToExtendedSrgb(hex) {
  const h = hex.trim().replace(/^#/, "");
  if (!/^[0-9a-fA-F]{6}$/.test(h)) {
    fail(`unsupported fill "${hex}" (need #RRGGBB)`);
  }
  const r = parseInt(h.slice(0, 2), 16) / 255;
  const g = parseInt(h.slice(2, 4), 16) / 255;
  const b = parseInt(h.slice(4, 6), 16) / 255;
  return `extended-srgb:${r.toFixed(5)},${g.toFixed(5)},${b.toFixed(5)},1.00000`;
}

function splitLayers(paths) {
  const norm = (f) => f.trim().toUpperCase();
  const glass = paths.filter((p) => norm(p.fill) === GLASS_FILL);
  const body = paths.filter((p) => norm(p.fill) !== GLASS_FILL);
  if (body.length === 0) fail("source.svg: glyph has no body paths (non-#F1ECEC fills)");
  if (glass.length === 0) fail("source.svg: glyph has no glass cap (fill #F1ECEC)");
  return { body, glass };
}

function writeIosContentsJson() {
  // iOS 14+ single-size 1024 slots. We provide any (universal) + dark + tinted
  // appearances, all from the same source design. "any" and "dark" use the
  // flattened art (dark-background design works for both luminosity modes).
  const json = {
    images: [
      {
        filename: "icon-1024.png",
        idiom: "universal",
        platform: "ios",
        size: "1024x1024",
      },
      {
        appearances: [{ appearance: "luminosity", value: "dark" }],
        filename: "icon-1024.png",
        idiom: "universal",
        platform: "ios",
        size: "1024x1024",
      },
      {
        appearances: [{ appearance: "luminosity", value: "tinted" }],
        filename: "icon-tinted-1024.png",
        idiom: "universal",
        platform: "ios",
        size: "1024x1024",
      },
    ],
    info: { author: "xcode", version: 1 },
  };
  writeFileSync(
    join(IOS_APPICONSET, "Contents.json"),
    JSON.stringify(json, null, 2) + "\n",
    "utf8",
  );
}

function writeIconComposer(parsed, square) {
  const { body, glass } = splitLayers(parsed.paths);
  const assetsDir = join(IOS_ICON_COMPOSER, "Assets");
  mkdirSync(assetsDir, { recursive: true });

  const bodySvg = buildSquareSvg({
    backgroundFill: "none",
    square,
    paths: body,
    fillOverride: "#FFFFFF",
  });
  const glassSvg = buildSquareSvg({
    backgroundFill: "none",
    square,
    paths: glass,
    fillOverride: "#FFFFFF",
  });
  renderPng(bodySvg, join(assetsDir, "body.png"));
  renderPng(glassSvg, join(assetsDir, "glass.png"));

  const background = hexToExtendedSrgb(parsed.backgroundFill);
  const bodyFill = hexToExtendedSrgb(body[0].fill);
  const glassFill = hexToExtendedSrgb(glass[0].fill);
  const json = {
    "fill-specializations": [
      { value: { solid: background } },
      { appearance: "dark", value: { solid: background } },
    ],
    groups: [
      {
        name: "D Mark",
        layers: [
          {
            name: "Body",
            "image-name": "body.png",
            glass: true,
            "fill-specializations": [
              { value: { solid: bodyFill } },
              { appearance: "dark", value: { solid: bodyFill } },
            ],
          },
          {
            name: "Glass",
            "image-name": "glass.png",
            glass: true,
            "fill-specializations": [
              { value: { solid: glassFill } },
              { appearance: "dark", value: { solid: glassFill } },
            ],
            translucency: { enabled: true, value: 0.28 },
          },
        ],
        shadow: { kind: "neutral", opacity: 0.4 },
        specular: true,
        translucency: { enabled: true, value: 0.16 },
        lighting: "combined",
      },
    ],
    "supported-platforms": { squares: ["iOS"] },
  };
  writeFileSync(join(IOS_ICON_COMPOSER, "icon.json"), JSON.stringify(json, null, 2) + "\n", "utf8");
}

function maybeValidateIconComposer() {
  let ictool;
  try {
    const xcode = execFileSync("xcode-select", ["-p"], { encoding: "utf8" }).trim();
    ictool = join(dirname(xcode), "Applications/Icon Composer.app/Contents/Executables/ictool");
  } catch {
    console.log("app-icon: Icon Composer ictool not found; skipped .icon render check");
    return;
  }
  if (!existsSync(ictool)) {
    console.log("app-icon: Icon Composer ictool not found; skipped .icon render check");
    return;
  }
  const out = join(tmpdir(), `opencode-app-icon-ictool-${randomBytes(4).toString("hex")}.png`);
  try {
    execFileSync(
      ictool,
      [
        IOS_ICON_COMPOSER,
        "--export-image",
        "--output-file",
        out,
        "--platform",
        "iOS",
        "--rendition",
        "Default",
        "--width",
        "64",
        "--height",
        "64",
        "--scale",
        "1",
      ],
      { stdio: "pipe" },
    );
  } catch (err) {
    fail(`ictool rejected AppIcon.icon: ${err.stdout?.toString() || err.stderr?.toString() || err.message}`);
  } finally {
    try {
      rmSync(out);
    } catch {}
  }
}

function androidVectorXml({ transform, paths, fillFor }) {
  const { translateX, translateY, scaleX, scaleY } = svgTransformToAndroid(transform);
  const pathsXml = paths
    .map((p) => {
      const fill = fillFor ? fillFor(p) : p.fill;
      return `    <path\n        android:pathData="${p.d}"\n        android:fillColor="${fill}" />`;
    })
    .join("\n");
  return `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="240"
    android:viewportHeight="300">
  <group android:scaleX="${scaleX}"
      android:scaleY="${scaleY}"
      android:translateX="${translateX}"
      android:translateY="${translateY}">
${pathsXml}
  </group>
</vector>
`;
}

function writeAndroidRes(relativePath, contents) {
  const out = join(ANDROID_RES, relativePath);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, contents, "utf8");
}

function writeAndroidForeground(parsed) {
  writeAndroidRes("drawable/ic_launcher_foreground.xml", androidVectorXml(parsed));
}

function writeAndroidBackgroundColor({ backgroundFill }) {
  writeAndroidRes(
    "values/ic_launcher_background.xml",
    `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">${backgroundFill}</color>
</resources>
`,
  );
}

function writeAndroidMonochrome(parsed) {
  writeAndroidRes(
    "drawable/ic_launcher_monochrome.xml",
    androidVectorXml({
      ...parsed,
      fillFor: () => "#FFFFFF",
    }),
  );
}

// Splash uses app chrome (`background-base` / light ink), not the launcher
// tile `#070707`. Light mode darkens the glass cap so it still reads on
// `#F8F8F8`; dark mode keeps the source two-tone.
const SPLASH_BACKGROUND_LIGHT = "#F8F8F8";
const SPLASH_BACKGROUND_DARK = "#1C1C1C";
const SPLASH_GLASS_LIGHT = "#171311";

function writeAndroidSplash(parsed) {
  const { body, glass } = splitLayers(parsed.paths);
  const bodyFill = body[0].fill;
  const glassFill = glass[0].fill;
  writeAndroidRes(
    "values/splash_colors.xml",
    `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="splash_background">${SPLASH_BACKGROUND_LIGHT}</color>
    <color name="splash_glyph_body">${bodyFill}</color>
    <color name="splash_glyph_glass">${SPLASH_GLASS_LIGHT}</color>
</resources>
`,
  );
  writeAndroidRes(
    "values-night/splash_colors.xml",
    `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="splash_background">${SPLASH_BACKGROUND_DARK}</color>
    <color name="splash_glyph_body">${bodyFill}</color>
    <color name="splash_glyph_glass">${glassFill}</color>
</resources>
`,
  );
  writeAndroidRes(
    "drawable/ic_splash.xml",
    androidVectorXml({
      ...parsed,
      fillFor: (p) =>
        p.fill.trim().toUpperCase() === GLASS_FILL.toUpperCase()
          ? "@color/splash_glyph_glass"
          : "@color/splash_glyph_body",
    }),
  );
}

function writeAndroidAdaptive() {
  const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
`;
  const outDir = join(ANDROID_RES, "mipmap-anydpi-v26");
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, "ic_launcher.xml"), adaptive, "utf8");
  writeFileSync(join(outDir, "ic_launcher_round.xml"), adaptive, "utf8");
}

function writeAndroidLegacyMipmaps(squareSvg) {
  const densities = [
    ["mipmap-mdpi", 48],
    ["mipmap-hdpi", 72],
    ["mipmap-xhdpi", 96],
    ["mipmap-xxhdpi", 144],
    ["mipmap-xxxhdpi", 192],
  ];
  for (const [folder, size] of densities) {
    const dir = join(ANDROID_RES, folder);
    mkdirSync(dir, { recursive: true });
    for (const name of ["ic_launcher", "ic_launcher_round"]) {
      const png = join(dir, `${name}.png`);
      const webp = join(dir, `${name}.webp`);
      renderPng(squareSvg, png, size);
      const decoded = decodePng(readFileSync(png));
      applyCircularAlpha(decoded.rgba, decoded.width, decoded.height);
      writeFileSync(png, encodePng(decoded.rgba, decoded.width, decoded.height, { alpha: true }));
      if (existsSync(webp)) unlinkSync(webp);
    }
  }
}

function main() {
  try {
    run();
  } catch (err) {
    fail(err.message);
  }
}

function run() {
  if (!existsSync(SOURCE_SVG)) fail(`source SVG not found: ${SOURCE_SVG}`);
  ensureRsvg();

  const src = readFileSync(SOURCE_SVG, "utf8");
  const parsed = parseSource(src);
  const square = deriveSquareTransform(parsed);

  mkdirSync(IOS_APPICONSET, { recursive: true });

  // iOS: flattened icon (any + dark) and tinted silhouette, rendered into a
  // square viewBox so the portrait source's design intent is preserved.
  const flattened = buildFlattenedSvg(parsed, square);
  const silhouette = buildSilhouetteSvg(parsed, square);
  renderPng(flattened, join(IOS_APPICONSET, "icon-1024.png"));
  renderPng(silhouette, join(IOS_APPICONSET, "icon-tinted-1024.png"));

  writeIosContentsJson();

  // iOS 26+: layered Liquid Glass package. Xcode generates pre-26 fallbacks
  // from this file; the appiconset PNGs stay as an explicit catalog copy.
  writeIconComposer(parsed, square);
  maybeValidateIconComposer();

  // Android: emit adaptive-icon layers from the same source.
  writeAndroidForeground(parsed);
  writeAndroidMonochrome(parsed);
  writeAndroidBackgroundColor(parsed);
  writeAndroidAdaptive();
  writeAndroidSplash(parsed);
  // FAD / aapt / pre-26 launchers show these PNGs unmasked. Keep the
  // adaptive layers full-bleed; only the fallback rasters get a pixel
  // circle (SVG clipPath tessellates into a polygon).
  writeAndroidLegacyMipmaps(
    buildFlattenedSvg(parsed, deriveInsetSquareTransform(parsed)),
  );

  console.log("app-icon: synced iOS AppIcon + AppIcon.icon + Android adaptive icon + splash from app-icon/source.svg");
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}