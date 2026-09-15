import { readFileSync } from "node:fs";

export const IOS_OUTPUT_TARGET = "ios-6.9";
export const IOS_EXPECTED = { width: 1320, height: 2868 };

const PNG_SIG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

export function readPngSize(filePath) {
  const buf = readFileSync(filePath);
  if (buf.length < 24) return null;
  if (!buf.subarray(0, 8).equals(PNG_SIG)) return null;
  if (buf.subarray(12, 16).toString("ascii") !== "IHDR") return null;
  return {
    width: buf.readUInt32BE(16),
    height: buf.readUInt32BE(20),
  };
}
