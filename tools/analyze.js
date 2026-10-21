// Minimal PNG decoder (8-bit RGB/RGBA, non-interlaced) + scene analysis.
// Used to verify the game actually renders: snake (green), food (red),
// HUD text (light), board panel (dark blue-grey) and game state overlays.
const fs = require('fs');
const zlib = require('zlib');

function decodePng(file) {
  const buf = fs.readFileSync(file);
  let pos = 8;
  let width = 0, height = 0, bitDepth = 0, colorType = 0, interlace = 0;
  const idat = [];
  let palette = null;
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
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'PLTE') {
      palette = data;
    } else if (type === 'IEND') {
      break;
    }
    pos += 12 + len;
  }
  if (bitDepth !== 8 || interlace !== 0) {
    throw new Error(`unsupported png bitDepth=${bitDepth} interlace=${interlace}`);
  }
  const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : colorType === 0 ? 1 : 0;
  if (!channels && colorType !== 3) throw new Error('unsupported colorType ' + colorType);

  const raw = zlib.inflateSync(Buffer.concat(idat));
  const bpp = channels;
  const stride = width * bpp;
  const out = Buffer.alloc(width * height * bpp);
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
        case 1: v = (v + a) & 0xff; break;
        case 2: v = (v + b) & 0xff; break;
        case 3: v = (v + ((a + b) >> 1)) & 0xff; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
          const pr = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          v = (v + pr) & 0xff;
          break;
        }
        default: throw new Error('bad filter ' + filter);
      }
      cur[x] = v;
    }
    cur.copy(out, y * stride);
    prev = cur;
  }
  return { width, height, channels, data: out };
}

function px(img, x, y) {
  const i = (y * img.width + x) * img.channels;
  if (img.channels === 1) return [img.data[i], img.data[i], img.data[i]];
  return [img.data[i], img.data[i + 1], img.data[i + 2]];
}

function classify(r, g, b) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  if (g > 110 && g > r * 1.25 && g > b * 1.35) return 'G';           // snake green
  if (r > 130 && r > g * 1.55 && r > b * 1.35) return 'R';           // food red
  if (b >= 33 && b <= 85 && r < 32 && g < 48 && b > g + 14) return '#'; // board panel (blue)
  if (max < 30) return ' ';                                          // deep background
  if (max < 100) return '.';
  if (min > 110 && max - min < 60) return 'T';                       // light text / white
  return '-';
}

function analyze(file, name) {
  const img = decodePng(file);
  const counts = { G: 0, R: 0, T: 0, '.': 0, ' ': 0, '-': 0 };
  let gx = 0, gy = 0, rCount = 0, rx = 0, ry = 0, light = 0;
  let gMinX = 1e9, gMaxX = -1, gMinY = 1e9, gMaxY = -1;
  for (let y = 0; y < img.height; y++) {
    for (let x = 0; x < img.width; x++) {
      const [r, g, b] = px(img, x, y);
      const c = classify(r, g, b);
      counts[c]++;
      if (c === 'G') {
        gx += x; gy += y;
        if (x < gMinX) gMinX = x;
        if (x > gMaxX) gMaxX = x;
        if (y < gMinY) gMinY = y;
        if (y > gMaxY) gMaxY = y;
      }
      if (c === 'R') { rCount++; rx += x; ry += y; }
      if (r > 150 && g > 150 && b > 150) light++;
    }
  }
  const total = img.width * img.height;
  const pct = (n) => ((n / total) * 100).toFixed(2) + '%';

  // coarse ASCII map (crop status bar area lightly) for a visual sanity check
  const cols = 48, rows = 64;
  const cw = Math.floor(img.width / cols), chh = Math.floor(img.height / rows);
  const lines = [];
  for (let ry = 0; ry < rows; ry++) {
    let line = '';
    for (let rx2 = 0; rx2 < cols; rx2++) {
      const tally = {};
      for (let sy = 0; sy < 6; sy++) {
        for (let sx = 0; sx < 6; sx++) {
          const x = Math.min(img.width - 1, rx2 * cw + Math.floor((sx + 0.5) * cw / 6));
          const y = Math.min(img.height - 1, ry * chh + Math.floor((sy + 0.5) * chh / 6));
          const [r, g, b] = px(img, x, y);
          const c = classify(r, g, b);
          tally[c] = (tally[c] || 0) + 1;
        }
      }
      let best = ' ', bestN = -1;
      for (const k of Object.keys(tally)) {
        // prefer colorful classes so the map is informative
        const weight = tally[k] * (k === 'G' || k === 'R' ? 3 : k === 'T' ? 2 : 1);
        if (weight > bestN) { bestN = weight; best = k; }
      }
      line += best;
    }
    lines.push(line);
  }

  console.log(`\n=== ${name} (${file}) ${img.width}x${img.height} ch=${img.channels} ===`);
  console.log(`green(G)=${pct(counts.G)} red(R)=${pct(counts.R)} light(T)=${pct(counts.T)} ` +
    `panel(.)=${pct(counts['.'])} bg( )=${pct(counts[' '])} other=${pct(counts['-'])}`);
  if (counts.G > 0) {
    console.log(`snake centroid=(${(gx / counts.G).toFixed(0)},${(gy / counts.G).toFixed(0)}) ` +
      `bbox=(${gMinX},${gMinY})-(${gMaxX},${gMaxY}) size=${gMaxX - gMinX}x${gMaxY - gMinY}`);
  }
  if (rCount > 0) {
    console.log(`food centroid=(${(rx / rCount).toFixed(0)},${(ry / rCount).toFixed(0)}) pixels=${rCount}`);
  }
  console.log('map:');
  console.log(lines.join('\n'));
  return { img, counts, gx, gy, rx, ry, rCount, light };
}

if (require.main === module) {
  const files = process.argv.slice(2);
  for (const f of files) {
    analyze(f, f.replace(/\\/g, '/').split('/').pop());
  }
}

module.exports = { decodePng, analyze, px, classify };
