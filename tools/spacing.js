// Checks the spacing between consecutive body points. With arc-length body
// placement every gap must equal exactly one cell; a squeeze during a turn
// (the old "breathing" body) shows up as a gap far below one cell.
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const cell = Number((txt.match(/cell=([\d.]+)/) || [0, 68])[1]);
const pts = (txt.split(/\r?\n/).find((l) => l.startsWith('pts=')) || '').slice(4)
  .split(';').filter(Boolean).map((s) => s.split(',').map(Number));

if (pts.length < 2) {
  console.log('not enough points');
  process.exit(0);
}
const gaps = [];
for (let i = 1; i < pts.length; i++) {
  gaps.push(Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
}
const min = Math.min(...gaps);
const max = Math.max(...gaps);
const avg = gaps.reduce((a, b) => a + b, 0) / gaps.length;
console.log(`cell=${cell.toFixed(2)} gaps=${gaps.length} min=${min.toFixed(1)} ` +
  `avg=${avg.toFixed(1)} max=${max.toFixed(1)}`);
const worst = Math.abs(min - cell) / cell;
console.log(worst > 0.12
  ? `SQUEEZE: shortest gap is ${(worst * 100).toFixed(0)}% off one cell`
  : 'OK: every body gap equals one cell (no squeeze while turning)');
