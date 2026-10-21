// Eat-rate stats with a steady-state window (last N gaps), from the app log.
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const eats = [];
for (const line of txt.split(/\r?\n/)) {
  const ts = line.match(/^(\d+)/);
  if (!ts) continue;
  const m = line.match(/score=(\d+)/);
  if (m) eats.push({ ts: Number(ts[1]), score: Number(m[1]) });
}
const gaps = [];
for (let i = 1; i < eats.length; i++) gaps.push(eats[i].ts - eats[i - 1].ts);
function stat(a, label) {
  if (!a.length) return console.log(label, 'n=0');
  const s = [...a].sort((x, y) => x - y);
  const avg = a.reduce((x, y) => x + y, 0) / a.length;
  const under3 = a.filter((g) => g <= 3000).length;
  console.log(label.padEnd(8), 'n=' + String(a.length).padStart(3),
    'avg=' + Math.round(avg) + 'ms',
    'med=' + s[Math.floor(a.length / 2)] + 'ms',
    'p90=' + s[Math.floor(a.length * 0.9)] + 'ms',
    'max=' + s[s.length - 1] + 'ms',
    '<=3s ' + Math.round((under3 / a.length) * 100) + '%');
}
console.log('eats=' + eats.length, 'span=' + Math.round((eats[eats.length - 1].ts - eats[0].ts) / 1000) + 's');
stat(gaps, 'all');
for (const n of [40, 20]) if (gaps.length >= n) stat(gaps.slice(-n), 'last' + n);
