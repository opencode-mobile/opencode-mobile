import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { toPascalCase } from "./utils.mjs";

function attr(tag, name, fallback = null) {
  const match = tag.match(new RegExp(`(?:^|\\s)${name}="([^"]*)"`, "i"));
  return match ? match[1] : fallback;
}

function num(tag, name, fallback = 0) {
  const value = attr(tag, name);
  return value == null ? fallback : Number(value);
}

function circleToPath(cx, cy, r) {
  return `M ${cx - r} ${cy} a ${r} ${r} 0 1 0 ${r * 2} 0 a ${r} ${r} 0 1 0 ${-r * 2} 0`;
}

function ellipseToPath(cx, cy, rx, ry) {
  return `M ${cx - rx} ${cy} a ${rx} ${ry} 0 1 0 ${rx * 2} 0 a ${rx} ${ry} 0 1 0 ${-rx * 2} 0`;
}

function rectToPath(x, y, width, height, rx, ry) {
  const radiusX = Math.min(rx || 0, width / 2);
  const radiusY = Math.min(ry || radiusX, height / 2);
  if (radiusX === 0 && radiusY === 0) {
    return `M ${x} ${y} h ${width} v ${height} h ${-width} z`;
  }
  return [
    `M ${x + radiusX} ${y}`,
    `h ${width - 2 * radiusX}`,
    `a ${radiusX} ${radiusY} 0 0 1 ${radiusX} ${radiusY}`,
    `v ${height - 2 * radiusY}`,
    `a ${radiusX} ${radiusY} 0 0 1 ${-radiusX} ${radiusY}`,
    `h ${-(width - 2 * radiusX)}`,
    `a ${radiusX} ${radiusY} 0 0 1 ${-radiusX} ${-radiusY}`,
    `v ${-(height - 2 * radiusY)}`,
    `a ${radiusX} ${radiusY} 0 0 1 ${radiusX} ${-radiusY}`,
    "z",
  ].join(" ");
}

function pointsToPath(points, close) {
  const pairs = points
    .trim()
    .split(/[\s,]+/)
    .filter(Boolean)
    .map(Number);
  if (pairs.length < 4 || pairs.length % 2 !== 0) {
    throw new Error(`Invalid SVG points: ${points}`);
  }
  const [x, y, ...rest] = pairs;
  let d = `M ${x} ${y}`;
  for (let i = 0; i < rest.length; i += 2) {
    d += ` L ${rest[i]} ${rest[i + 1]}`;
  }
  if (close) d += " Z";
  return d;
}

function elementToPath(tagName, tag) {
  switch (tagName) {
    case "path": {
      const d = attr(tag, "d");
      if (!d) throw new Error("SVG path is missing d");
      return d;
    }
    case "circle":
      return circleToPath(num(tag, "cx"), num(tag, "cy"), num(tag, "r"));
    case "ellipse":
      return ellipseToPath(num(tag, "cx"), num(tag, "cy"), num(tag, "rx"), num(tag, "ry"));
    case "line":
      return `M ${num(tag, "x1")} ${num(tag, "y1")} L ${num(tag, "x2")} ${num(tag, "y2")}`;
    case "polyline":
      return pointsToPath(attr(tag, "points", ""), false);
    case "polygon":
      return pointsToPath(attr(tag, "points", ""), true);
    case "rect":
      return rectToPath(
        num(tag, "x"),
        num(tag, "y"),
        num(tag, "width"),
        num(tag, "height"),
        num(tag, "rx"),
        num(tag, "ry"),
      );
    default:
      throw new Error(`Unsupported SVG element: ${tagName}`);
  }
}

function parseSvg(svgText) {
  const viewBoxMatch = svgText.match(/viewBox="([^"]+)"/);
  let viewportMinX = 0;
  let viewportMinY = 0;
  let viewportWidth = 24;
  let viewportHeight = 24;
  if (viewBoxMatch) {
    const parts = viewBoxMatch[1].trim().split(/\s+/).map(Number);
    if (parts.length === 4) {
      [viewportMinX, viewportMinY, viewportWidth, viewportHeight] = parts;
    }
  }

  const rootTag = svgText.match(/<svg\b[^>]*>/i)?.[0] ?? "";
  const strokeWidth = Number(attr(rootTag, "stroke-width", "2"));

  const paths = [];
  const tagRegex = /<(path|circle|ellipse|line|polyline|polygon|rect)\b([^>]*)\/?>/gi;
  let match;
  while ((match = tagRegex.exec(svgText)) !== null) {
    paths.push(elementToPath(match[1].toLowerCase(), match[0]));
  }
  if (paths.length === 0) {
    throw new Error("SVG contains no drawable elements");
  }

  return { viewportMinX, viewportMinY, viewportWidth, viewportHeight, strokeWidth, paths };
}

function escapeKotlinString(value) {
  return value.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

function generateIconProperty(iconName, svgText) {
  const pascal = toPascalCase(iconName);
  const privateName = `_${iconName}`;
  const { viewportMinX, viewportMinY, viewportWidth, viewportHeight, strokeWidth, paths } =
    parseSvg(svgText);
  const pathBlocks = paths
    .map((pathData) => {
      const escaped = escapeKotlinString(pathData);
      return `    addPath(
      pathData = PathParser().parsePathString("${escaped}").toNodes(),
      fill = null,
      stroke = SolidColor(Color.Black),
      strokeLineWidth = ${strokeWidth}f,
      strokeLineCap = StrokeCap.Round,
      strokeLineJoin = StrokeJoin.Round,
      pathFillType = PathFillType.NonZero,
    )`;
    })
    .join("\n");
  const needsViewBoxOffset = viewportMinX !== 0 || viewportMinY !== 0;
  const translationX = -viewportMinX;
  const translationY = -viewportMinY;
  const body = needsViewBoxOffset
    ? `    group(
      translationX = ${translationX}f,
      translationY = ${translationY}f,
    ) {
${pathBlocks}
    }`
    : pathBlocks;

  return `  val ${pascal}: ImageVector
    get() {
      if (${privateName} != null) return ${privateName}!!
      ${privateName} = ImageVector.Builder(
        name = "${pascal}",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = ${viewportWidth}f,
        viewportHeight = ${viewportHeight}f,
      ).apply {
${body}
      }.build()
      return ${privateName}!!
    }`;
}

export function generateAndroidIcons(icons, svgByName, outputFile) {
  const privateFields = icons.map((icon) => `private var _${icon.name}: ImageVector? = null`).join("\n");
  const properties = icons
    .map((icon) => {
      const svgText = svgByName.get(icon.name);
      if (!svgText) throw new Error(`Missing SVG for ${icon.name}`);
      return generateIconProperty(icon.name, svgText);
    })
    .join("\n\n");

  const usesGroup = icons.some((icon) => {
    const svgText = svgByName.get(icon.name);
    if (!svgText) return false;
    const parsed = parseSvg(svgText);
    return parsed.viewportMinX !== 0 || parsed.viewportMinY !== 0;
  });
  const groupImport = usesGroup ? "\nimport androidx.compose.ui.graphics.vector.group" : "";

  const kotlin = `// Generated by scripts/sync-icons.mjs — do not edit.
package dev.opencode.mobile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser${groupImport}
import androidx.compose.ui.unit.dp

object OpenCodeIcons {
${privateFields}

${properties}
}
`;

  mkdirSync(dirname(outputFile), { recursive: true });
  writeFileSync(outputFile, kotlin, "utf8");
}
