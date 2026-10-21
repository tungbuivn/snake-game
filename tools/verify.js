// Cross-checks the drawn snake against the game's own dumped centre points.
const fs = require('fs');
const { decodePng, px } = require('./analyze.js');

const stateBuf = fs.readFileSync(process.argv[2]);
const stateTxt = (stateBuf.length > 1 && stateBuf[0] === 0xFF && stateBuf[1] === 0xFE) ||
  stateBuf.includes(0)
  ? stateBuf.toString('utf16le')
  : stateBuf.toString('utf8');
const shot = decodePng(process.argv[3]);

const line = stateTxt.split(/\r?\n/).find((l) => l.startsWith('pts=')) || '';
const pts = line.slice(4).split(';').filter(Boolean).map((s) => s.split(',').map(Number));
const header = stateTxt.split(/\r?\n/)[0];

const isSnake = (p) =>
  (p[1] >= 70 && p[1] > p[0] * 1.10 && p[1] > p[2] * 1.05) ||
  (p[0] > 150 && p[1] > 180 && p[2] > 160);

function anyNear(x, y, r) {
  const x0 = Math.max(0, Math.round(x - r));
  const x1 = Math.min(shot.width - 1, Math.round(x + r));
  const y0 = Math.max(0, Math.round(y - r));
  const y1 = Math.min(shot.height - 1, Math.round(y + r));
  for (let yy = y0; yy <= y1; yy++) {
    for (let xx = x0; xx <= x1; xx++) {
      const dx = xx - x, dy = yy - y;
      if (dx * dx + dy * dy > r * r) continue;
      if (isSnake(px(shot, xx, yy))) return true;
    }
  }
  return false;
}

if (pts.length === 0) {
  console.log('no drawn points dumped');
  process.exit(1);
}

let centerHits = 0;
const misses = [];
pts.forEach((p, i) => {
  if (anyNear(p[0], p[1], 4)) centerHits++;
  else misses.push(['center', i, p]);
});

let midHits = 0;
for (let i = 1; i < pts.length; i++) {
  const mx = (pts[i - 1][0] + pts[i][0]) / 2;
  const my = (pts[i - 1][1] + pts[i][1]) / 2;
  if (anyNear(mx, my, 10)) midHits++;
  else misses.push(['mid', i, [Math.round(mx), Math.round(my)]]);
}

// head should be the bright pale green blob with dark eyes inside
const head = pts[0];
const p0 = px(shot, Math.round(head[0]), Math.round(head[1]));
let bright = 0, dark = 0;
const hr = Number((stateTxt.match(/cell=([\d.]+)/) || [0, 68])[1]) * 0.46;
for (let yy = Math.round(head[1] - hr * 1.2); yy <= Math.round(head[1] + hr * 1.2); yy++) {
  for (let xx = Math.round(head[0] - hr * 1.2); xx <= Math.round(head[0] + hr * 1.2); xx++) {
    if (xx < 0 || yy < 0 || xx >= shot.width || yy >= shot.height) continue;
    const p = px(shot, xx, yy);
    const lum = 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2];
    if (lum > 130) bright++;
    if (lum < 45) dark++;
  }
}

// ribbon width sample: horizontal run of snake colour at 2nd point row
let width = 0;
const probe = pts[Math.min(2, pts.length - 1)];
const py0 = Math.round(probe[1]);
let x = Math.round(probe[0]);
while (x > 0 && isSnake(px(shot, x, py0))) x--;
let x2 = Math.round(probe[0]);
while (x2 < shot.width - 1 && isSnake(px(shot, x2, py0))) x2++;
width = x2 - x;

console.log(header);
console.log(`segments=${pts.length} centreHits=${centerHits}/${pts.length} ` +
  `midHits=${midHits}/${pts.length - 1} ribbonWidth=${width}px`);
console.log('headPixel=' + p0.join(',') + ' headPts=' + head.join(',') +
  ` headBrightPx=${bright} headDarkPx=${dark}`);
if (misses.length) {
  console.log('MISSES ' + misses.length + ': ' + JSON.stringify(misses.slice(0, 12)));
} else {
  console.log('OK: ribbon covers every centre point and every gap between them');
}
