// Finds a corner in the dumped body and prints a zoomed mask so the elbow can
// be inspected without viewing the image directly.
const fs = require('fs');
const { decodePng, px } = require('./analyze.js');

const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const img = decodePng(process.argv[3]);
const pts = (txt.split(/\r?\n/).find((l) => l.startsWith('pts=')) || '').slice(4)
  .split(';').filter(Boolean).map((s) => s.split(',').map(Number));

const angle = (a, b) => Math.atan2(b[1] - a[1], b[0] - a[0]);
let corner = -1;
for (let i = 2; i < pts.length - 1; i++) {
  let d = Math.abs(angle(pts[i - 1], pts[i]) - angle(pts[i], pts[i + 1]));
  if (d > Math.PI) d = 2 * Math.PI - d;
  if (d > 0.7) { corner = i; break; }
}
if (corner < 0) {
  console.log('no corner in this frame');
  process.exit(2);
}
console.log('corner at', pts[corner].join(','), 'index', corner);

const isSnake = (p) => (p[1] >= 60 && p[1] > p[0] * 1.05 && p[1] > p[2] * 1.02);
const cx = pts[corner][0];
const cy = pts[corner][1];
const x0 = cx - 85, y0 = cy - 85, w = 170, h = 170;
const cols = 60, rows = 30;
const cw = w / cols, chh = h / rows;
for (let ry = 0; ry < rows; ry++) {
  let line = '';
  for (let rx = 0; rx < cols; rx++) {
    let n = 0;
    for (let sy = 0; sy < 3; sy++) {
      for (let sx = 0; sx < 3; sx++) {
        const x = Math.round(x0 + rx * cw + (sx + 0.5) * cw / 3);
        const y = Math.round(y0 + ry * chh + (sy + 0.5) * chh / 3);
        if (isSnake(px(img, x, y))) n++;
      }
    }
    line += n === 0 ? ' ' : n < 3 ? '.' : n < 6 ? 'o' : '#';
  }
  console.log(line);
}
