// Verifies that only the head and tail tips move: every interior body point must
// sit exactly on a cell centre. Then a bend is fixed geometry and cannot wobble.
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const cell = Number((txt.match(/cell=([\d.]+)/) || [0, 68])[1]);
const board = (txt.match(/board=([-\d.,]+)/) || [0, ''])[1].split(',').map(Number);
const [left, top] = board;
const pts = (txt.split(/\r?\n/).find((l) => l.startsWith('pts=')) || '').slice(4)
  .split(';').filter(Boolean).map((s) => s.split(',').map(Number));

let worstInterior = 0;
let worstTailOrHead = 0;
for (let i = 1; i < pts.length - 1; i++) {
  const cx = left + (Math.round((pts[i][0] - left - cell / 2) / cell) + 0.5) * cell;
  const cy = top + (Math.round((pts[i][1] - top - cell / 2) / cell) + 0.5) * cell;
  const dev = Math.max(Math.abs(pts[i][0] - cx), Math.abs(pts[i][1] - cy));
  worstInterior = Math.max(worstInterior, dev);
}
for (const i of [0, pts.length - 1]) {
  if (i < 0 || i >= pts.length) continue;
  const cx = left + (Math.round((pts[i][0] - left - cell / 2) / cell) + 0.5) * cell;
  const cy = top + (Math.round((pts[i][1] - top - cell / 2) / cell) + 0.5) * cell;
  const dev = Math.max(Math.abs(pts[i][0] - cx), Math.abs(pts[i][1] - cy));
  worstTailOrHead = Math.max(worstTailOrHead, dev);
}
console.log(`points=${pts.length} interiorMaxDev=${worstInterior.toFixed(2)}px ` +
  `tipMaxDev=${worstTailOrHead.toFixed(2)}px`);
console.log(worstInterior <= 1.5
  ? 'OK: interior body points sit on cell centres (bends are fixed geometry)'
  : 'WARN: interior points are interpolated (bends can wobble)');
