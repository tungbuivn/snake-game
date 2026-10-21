// Samples the colour profile across the snake body (perpendicular to the
// centre line). A belly band + dorsal scales should make the profile
// asymmetric: lighter toward one edge, darker spots near the other.
const fs = require('fs');
const { decodePng, px } = require('./analyze.js');

const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const shot = decodePng(process.argv[3]);
const cell = Number((txt.match(/cell=([\d.]+)/) || [0, 68])[1]);
const pts = (txt.split(/\r?\n/).find((l) => l.startsWith('pts=')) || '').slice(4)
  .split(';').filter(Boolean).map((s) => s.split(',').map(Number));

const hw = cell * 0.60 / 2;
const buckets = 9;
const sums = new Array(buckets).fill(0);
const counts = new Array(buckets).fill(0);

for (let i = 1; i < pts.length - 1; i++) {
  const prev = pts[i - 1], next = pts[i + 1];
  let tx = next[0] - prev[0], ty = next[1] - prev[1];
  const l = Math.hypot(tx, ty) || 1;
  tx /= l; ty /= l;
  const nx = -ty, ny = tx;
  for (let b = 0; b < buckets; b++) {
    const off = -hw + (2 * hw) * (b + 0.5) / buckets;
    const x = Math.round(pts[i][0] + nx * off);
    const y = Math.round(pts[i][1] + ny * off);
    if (x < 0 || y < 0 || x >= shot.width || y >= shot.height) continue;
    const p = px(shot, x, y);
    const lum = 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2];
    sums[b] += lum;
    counts[b]++;
  }
}

const profile = sums.map((s, i) => (counts[i] ? s / counts[i] : 0).toFixed(0));
console.log('body length', pts.length, 'cross-section luminance (-edge .. +edge):');
console.log(profile.join('  '));
const first = Number(profile[0]);
const last = Number(profile[profile.length - 1]);
console.log(`asymmetry=${Math.abs(first - last).toFixed(0)} (belly/shading present if > 3)`);

// fine profile across one station, rendered as a luminance ramp
const mid = Math.floor(pts.length / 2);
const prev = pts[mid - 1], next = pts[mid + 1];
let tx = next[0] - prev[0], ty = next[1] - prev[1];
const l = Math.hypot(tx, ty) || 1;
tx /= l; ty /= l;
const nx = -ty, ny = tx;
const ramp = ' .:-=+*#%@';
let line = '';
const vals = [];
for (let k = -10; k <= 10; k++) {
  const off = (hw * 1.05) * k / 10;
  const x = Math.round(pts[mid][0] + nx * off);
  const y = Math.round(pts[mid][1] + ny * off);
  const p = px(shot, x, y);
  const lum = 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2];
  vals.push(Math.round(lum));
  line += ramp[Math.min(ramp.length - 1, Math.max(0, Math.round(lum / 26)))];
}
console.log('fine cross-section (-edge to +edge):', vals.join(','));
console.log('  ' + line);
