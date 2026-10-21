// Checks that the body ribbon can never fold over itself: the centre line's
// minimum radius of curvature must stay larger than the body half-width.
// Mirrors the path construction in GameView.buildCentrePath + buildRibbon.
const fs = require('fs');

const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const cell = Number((txt.match(/cell=([\d.]+)/) || [0, 68])[1]);
const pts = (txt.split(/\r?\n/).find((l) => l.startsWith('pts=')) || '').slice(4)
  .split(';').filter(Boolean).map((s) => s.split(',').map(Number));

if (pts.length < 3) {
  console.log('too few points');
  process.exit(0);
}

const CUT = cell * 0.50;

// Sample the same rounded-corner path the app strokes: line, quadratic corner.
const samples = [];
function push(x, y) {
  const last = samples[samples.length - 1];
  if (last && Math.hypot(x - last[0], y - last[1]) < 0.6) return;
  samples.push([x, y]);
}
push(pts[0][0], pts[0][1]);
for (let i = 1; i < pts.length - 1; i++) {
  const [ax, ay] = pts[i - 1];
  const [bx, by] = pts[i];
  const [cx, cy] = pts[i + 1];
  let inx = bx - ax, iny = by - ay;
  let outx = cx - bx, outy = cy - by;
  const inLen = Math.hypot(inx, iny), outLen = Math.hypot(outx, outy);
  if (inLen < 1e-3 || outLen < 1e-3) { push(bx, by); continue; }
  inx /= inLen; iny /= inLen; outx /= outLen; outy /= outLen;
  const straight = Math.abs(inx - outx) < 1e-3 && Math.abs(iny - outy) < 1e-3;
  if (straight) { push(bx, by); continue; }
  const d = Math.min(CUT, Math.min(inLen, outLen) * 0.5);
  const ex = bx - inx * d, ey = by - iny * d;
  const fx = bx + outx * d, fy = by + outy * d;
  push(ex, ey);
  for (let s = 1; s <= 14; s++) {
    const t = s / 14;
    const u = 1 - t;
    push(u * u * ex + 2 * u * t * bx + t * t * fx,
      u * u * ey + 2 * u * t * by + t * t * fy);
  }
}
push(pts[pts.length - 1][0], pts[pts.length - 1][1]);

// Curvature from the angle change between consecutive sample segments.
const angles = [];
for (let i = 1; i < samples.length; i++) {
  angles.push(Math.atan2(samples[i][1] - samples[i - 1][1],
    samples[i][0] - samples[i - 1][0]));
}
let minR = Infinity;
let worstAt = -1;
for (let i = 2; i < angles.length; i++) {
  let da = angles[i] - angles[i - 1];
  while (da > Math.PI) da -= 2 * Math.PI;
  while (da < -Math.PI) da += 2 * Math.PI;
  const ds = Math.hypot(samples[i][0] - samples[i - 1][0],
    samples[i][1] - samples[i - 1][1]);
  if (ds < 1e-3) continue;
  const r = Math.abs(ds / da);
  if (r < minR) { minR = r; worstAt = i; }
}

// widest half-width anywhere: head segment
const maxHalfW = (cell * 0.60) / 2;
const ok = minR > maxHalfW * 1.02;
console.log(`cell=${cell} cut=${CUT.toFixed(1)} minRadius=${minR.toFixed(1)}px ` +
  `halfWidth=${maxHalfW.toFixed(1)}px samples=${samples.length}`);
console.log(ok
  ? 'OK: min radius > half width -> the ribbon cannot crease at corners'
  : `FOLD RISK at sample ${worstAt}`);
