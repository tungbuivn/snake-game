// Reads small PNG sprites (including palette / sub-byte depths) and reports
// their dominant colours, so a CC0 reference image can guide the palette even
// though this model cannot view images directly.
const fs = require('fs');
const zlib = require('zlib');

function decode(file) {
  const buf = fs.readFileSync(file);
  let pos = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
  const idat = [];
  let palette = null;
  let trns = null;
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === 'PLTE') {
      palette = data;
    } else if (type === 'tRNS') {
      trns = data;
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (interlace !== 0) throw new Error('interlaced png not supported');

  const channels = colorType === 6 ? 4 : colorType === 2 ? 3 :
    colorType === 0 ? 1 : colorType === 4 ? 2 : 0;
  const bitsPerPixel = colorType === 3 ? bitDepth : channels * bitDepth;
  const bpp = Math.max(1, Math.ceil(bitsPerPixel / 8));
  const stride = Math.ceil((width * bitsPerPixel) / 8);

  const raw = zlib.inflateSync(Buffer.concat(idat));
  const out = Buffer.alloc(stride * height);
  let prev = Buffer.alloc(stride);
  let rp = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[rp++];
    const line = raw.subarray(rp, rp + stride);
    rp += stride;
    const cur = Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp] : 0;
      const b = prev[x];
      const c = x >= bpp ? prev[x - bpp] : 0;
      let v = line[x];
      switch (filter) {
        case 0: break;
        case 1: v = (v + a) & 255; break;
        case 2: v = (v + b) & 255; break;
        case 3: v = (v + ((a + b) >> 1)) & 255; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
          break;
        }
        default: throw new Error('bad filter ' + filter);
      }
      cur[x] = v;
    }
    cur.copy(out, y * stride);
    prev = cur;
  }

  function sample(x, y) {
    if (colorType === 3) {
      const bitPos = x * bitDepth;
      const byte = out[y * stride + (bitPos >> 3)];
      const shift = 8 - bitDepth - (bitPos & 7);
      const idx = (byte >> shift) & ((1 << bitDepth) - 1);
      const r = palette[idx * 3], g = palette[idx * 3 + 1], b = palette[idx * 3 + 2];
      const a = trns && idx < trns.length ? trns[idx] : 255;
      return [r, g, b, a];
    }
    if (colorType === 6) {
      const i = (y * width + x) * 4;
      return [out[i], out[i + 1], out[i + 2], out[i + 3]];
    }
    if (colorType === 2) {
      const i = (y * width + x) * 3;
      return [out[i], out[i + 1], out[i + 2], 255];
    }
    if (colorType === 0) {
      const v = out[y * width + x];
      return [v, v, v, 255];
    }
    const i = (y * width + x) * 2;
    return [out[i], out[i], out[i], out[i + 1]];
  }

  return { width, height, bitDepth, colorType, sample };
}

function report(file) {
  const img = decode(file);
  const counts = new Map();
  for (let y = 0; y < img.height; y++) {
    for (let x = 0; x < img.width; x++) {
      const [r, g, b, a] = img.sample(x, y);
      if (a < 32) continue;
      const key = `${r},${g},${b}`;
      counts.set(key, (counts.get(key) || 0) + 1);
    }
  }
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  const total = img.width * img.height;
  console.log(`\n=== ${file} ${img.width}x${img.height} type=${img.colorType} depth=${img.bitDepth} ===`);
  console.log(sorted.slice(0, 12).map(([c, n]) =>
    `#${c.split(',').map((v) => Number(v).toString(16).padStart(2, '0')).join('')} x${n}`).join('  '));
  return img;
}

if (require.main === module) {
  for (const f of process.argv.slice(2)) report(f);
}
module.exports = { decode, report };
