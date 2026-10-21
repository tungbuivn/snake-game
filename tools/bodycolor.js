// Confirms the body is drawn with exactly one flat colour (no gradient).
const fs = require('fs');
const { decodePng, px } = require('./analyze.js');

const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const shot = decodePng(process.argv[3]);
const pts = (txt.split(/\r?\n/).find((l) => l.startsWith('pts=')) || '').slice(4)
  .split(';').filter(Boolean).map((s) => s.split(',').map(Number));

const colors = [];
for (let i = 1; i < pts.length; i++) {
  const p = px(shot, pts[i][0], pts[i][1]);
  colors.push([p[0], p[1], p[2]]);
}
if (!colors.length) {
  console.log('no body points');
  process.exit(0);
}
const ch = [0, 1, 2].map((c) => colors.map((q) => q[c]));
const spread = ch.map((vals) => Math.max(...vals) - Math.min(...vals));
const uniq = new Set(colors.map((c) => c.join(',')));
console.log(`samples=${colors.length} distinct colours=${uniq.size} ` +
  `channel spread R=${spread[0]} G=${spread[1]} B=${spread[2]}`);
console.log(uniq.size <= 3 && Math.max(...spread) <= 8
  ? 'OK: body uses one flat colour (no gradient)'
  : 'WARN: body colour still varies along its length');
