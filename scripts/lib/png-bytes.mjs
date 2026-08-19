// Minimal 8-bit non-interlaced PNG decode/encode for store-icon exports.
// Supports color type 2 (RGB) and 6 (RGBA). Adds an sRGB chunk on write.
import { crc32, deflateSync, inflateSync } from "node:zlib";

const PNG_SIG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const PX_PER_METER_72DPI = 2835;

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  if (pb <= pc) return b;
  return c;
}

function readChunks(buf) {
  if (buf.length < 8 || !buf.subarray(0, 8).equals(PNG_SIG)) {
    throw new Error("not a PNG");
  }
  const chunks = [];
  let offset = 8;
  while (offset < buf.length) {
    const length = buf.readUInt32BE(offset);
    const type = buf.subarray(offset + 4, offset + 8).toString("ascii");
    const data = buf.subarray(offset + 8, offset + 8 + length);
    const crc = buf.readUInt32BE(offset + 8 + length);
    const expected = crc32(buf.subarray(offset + 4, offset + 8 + length)) >>> 0;
    if (crc !== expected) throw new Error(`PNG chunk CRC mismatch (${type})`);
    chunks.push({ type, data });
    offset += 12 + length;
    if (type === "IEND") break;
  }
  return chunks;
}

function unfilter(idat, width, height, bytesPerPixel) {
  const stride = width * bytesPerPixel;
  const raw = inflateSync(idat);
  const expected = height * (stride + 1);
  if (raw.length !== expected) {
    throw new Error(`PNG IDAT size mismatch (${raw.length} != ${expected})`);
  }
  const out = Buffer.alloc(height * stride);
  let src = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[src++];
    const row = out.subarray(y * stride, (y + 1) * stride);
    const prev = y === 0 ? null : out.subarray((y - 1) * stride, y * stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= bytesPerPixel ? row[x - bytesPerPixel] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= bytesPerPixel ? prev[x - bytesPerPixel] : 0;
      const f = raw[src++];
      switch (filter) {
        case 0:
          row[x] = f;
          break;
        case 1:
          row[x] = (f + a) & 255;
          break;
        case 2:
          row[x] = (f + b) & 255;
          break;
        case 3:
          row[x] = (f + ((a + b) >> 1)) & 255;
          break;
        case 4:
          row[x] = (f + paeth(a, b, c)) & 255;
          break;
        default:
          throw new Error(`unsupported PNG filter ${filter}`);
      }
    }
  }
  return out;
}

export function decodePng(buf) {
  const chunks = readChunks(buf);
  const ihdr = chunks.find((c) => c.type === "IHDR");
  if (!ihdr) throw new Error("PNG missing IHDR");
  const width = ihdr.data.readUInt32BE(0);
  const height = ihdr.data.readUInt32BE(4);
  const bitDepth = ihdr.data[8];
  const colorType = ihdr.data[9];
  const interlace = ihdr.data[12];
  if (bitDepth !== 8) throw new Error(`unsupported PNG bit depth ${bitDepth}`);
  if (interlace !== 0) throw new Error("interlaced PNG is not supported");
  if (colorType !== 2 && colorType !== 6) {
    throw new Error(`unsupported PNG color type ${colorType}`);
  }
  const bytesPerPixel = colorType === 6 ? 4 : 3;
  const idat = Buffer.concat(chunks.filter((c) => c.type === "IDAT").map((c) => c.data));
  const pixels = unfilter(idat, width, height, bytesPerPixel);
  const rgba = Buffer.alloc(width * height * 4);
  if (colorType === 6) {
    pixels.copy(rgba);
  } else {
    for (let i = 0, o = 0; i < pixels.length; i += 3, o += 4) {
      rgba[o] = pixels[i];
      rgba[o + 1] = pixels[i + 1];
      rgba[o + 2] = pixels[i + 2];
      rgba[o + 3] = 255;
    }
  }
  return { width, height, rgba };
}

function chunk(type, data) {
  const typeBuf = Buffer.from(type, "ascii");
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])) >>> 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

function encodePixels(rgba, width, height, withAlpha) {
  const bpp = withAlpha ? 4 : 3;
  const stride = width * bpp;
  const raw = Buffer.alloc(height * (stride + 1));
  let o = 0;
  for (let y = 0; y < height; y++) {
    raw[o++] = 0;
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      raw[o++] = rgba[i];
      raw[o++] = rgba[i + 1];
      raw[o++] = rgba[i + 2];
      if (withAlpha) raw[o++] = rgba[i + 3];
    }
  }
  return deflateSync(raw, { level: 9 });
}

export function encodePng(rgba, width, height, { alpha }) {
  if (rgba.length !== width * height * 4) {
    throw new Error("RGBA buffer size does not match width*height");
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = alpha ? 6 : 2;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  const phys = Buffer.alloc(9);
  phys.writeUInt32BE(PX_PER_METER_72DPI, 0);
  phys.writeUInt32BE(PX_PER_METER_72DPI, 4);
  phys[8] = 1;
  return Buffer.concat([
    PNG_SIG,
    chunk("IHDR", ihdr),
    chunk("sRGB", Buffer.from([0])),
    chunk("pHYs", phys),
    chunk("IDAT", encodePixels(rgba, width, height, alpha)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

export function inspectPng(buf) {
  const chunks = readChunks(buf);
  const ihdr = chunks.find((c) => c.type === "IHDR");
  const width = ihdr.data.readUInt32BE(0);
  const height = ihdr.data.readUInt32BE(4);
  const colorType = ihdr.data[9];
  const hasSrgb = chunks.some((c) => c.type === "sRGB");
  return {
    width,
    height,
    colorType,
    alpha: colorType === 6 || colorType === 4,
    srgb: hasSrgb,
    bytes: buf.length,
  };
}

export function assertOpaque(rgba) {
  for (let i = 3; i < rgba.length; i += 4) {
    if (rgba[i] !== 255) {
      throw new Error("store PNG must be fully opaque (no transparent pixels)");
    }
  }
}
