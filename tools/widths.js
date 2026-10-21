// Measures the snake body width perpendicular to the centre line at each
// dumped segment, to prove corners no longer bulge or pinch.
const fs = require('fs');
const { decodePng, px } = require('./analyze.js');

const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const shot = decodePng(process.argv[3]);

const lines = txt.split(/\r?\n/);
const header = lines[0];
const cell = Number((txt.match(/cell=([\d.]+)/) || [0, 68])[1]);
const pts = (lines.find((l) => l.startsWith('pts=')) || '').slice(4)
  .split(';').filter(Boolean).map((s) => s.split(',').map(Number));

const isSnake = (p) =>
  (p[1] >= 60 && p[1] > p[0] * 1.05 && p[1] > p[2] * 1.02) ||
  (p[0] > 140 && p[1] > 170 && p[2] > 150);

function sample(x, y) {
  const xx = Math.round(x);
  const yy = Math.round(y);
  if (xx < 0 || yy < 0 || xx >= shot.width || yy >= shot.height) return false;
  return isSnake(px(shot, xx, yy));
}

function ray(x, y, nx, ny) {
  for (let d = 1; d <= 70; d += 0.5) {
    if (!sample(x + nx * d, y + ny * d)) return d;
  }
  return 70;
}

const maxW = cell * 0.60;
const rows = [];
for (let i = 0; i < pts.length; i++) {
  const prev = pts[Math.max(0, i - 1)];
  const next = pts[Math.min(pts.length - 1, i + 1)];
  let tx = next[0] - prev[0];
  let ty = next[1] - prev[1];
  const len = Math.hypot(tx, ty) || 1;
  tx /= len;
  ty /= len;
  const nx = -ty;
  const ny = tx;
  const [x, y] = pts[i];
  const up = ray(x, y, nx, ny);
  const down = ray(x, y, -nx, -ny);
  const width = up + down;
  const u = pts.length > 1 ? i / (pts.length - 1) : 0;
  // Body, head and tail all share the same width now (no taper).
  const expected = maxW;
  const angle = Math.atan2(ty, tx) * 180 / Math.PI;
  rows.push({ i, width: Math.round(width), expected: Math.round(expected), angle: Math.round(angle) });
}

let worst = 0;
let bulges = 0;
let pinches = 0;
let covered = 0;
rows.forEach((r) => {
  if (r.i === 0 || r.i === rows.length - 1) return; // head blob / tail tip
  if (r.width <= 5) { covered++; return; }          // hidden under the pause overlay
  const ratio = r.width / Math.max(1, r.expected);
  if (r.width > r.expected * 1.22) bulges++;
  if (r.width < r.expected * 0.55) pinches++;
  worst = Math.max(worst, Math.abs(ratio - 1));
});

console.log(header);
console.log(rows.map((r) => `#${r.i} w=${r.width} exp=${r.expected} ang=${r.angle}`).join('\n'));
console.log(`\nsegments=${pts.length} bulges=${bulges} pinches=${pinches} ` +
  `covered=${covered} worstDev=${(worst * 100).toFixed(0)}%`);
if (bulges || pinches) console.log('SUSPECT: body width deviates at some points');
else console.log('OK: constant body width from head to tail, no bulging at corners');
