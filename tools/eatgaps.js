// Parses the app's eats.txt log: time between fruits plus death count.
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2]);
const txt = (buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0)
  ? buf.toString('utf16le')
  : buf.toString('utf8');

const eats = [];
let deaths = 0;
for (const line of txt.split(/\r?\n/)) {
  const ts = line.match(/^(\d+)/);
  if (!ts) continue;
  if (line.includes('died')) {
    deaths++;
    continue;
  }
  const m = line.match(/score=(\d+)/);
  if (m) eats.push({ ts: Number(ts[1]), score: Number(m[1]) });
}

if (!eats.length) {
  console.log('no eat records');
  process.exit(0);
}
const gaps = [];
for (let i = 1; i < eats.length; i++) gaps.push(eats[i].ts - eats[i - 1].ts);
const sorted = [...gaps].sort((a, b) => a - b);
const avg = Math.round(gaps.reduce((a, b) => a + b, 0) / gaps.length);
const median = sorted[Math.floor(sorted.length / 2)];
const p90 = sorted[Math.floor(sorted.length * 0.9)];
const max = sorted[sorted.length - 1];
const under = gaps.filter((g) => g <= 3000).length;
console.log(`eats=${eats.length} deaths=${deaths} maxScore=${eats[eats.length - 1].score}`);
console.log(`avg=${avg}ms median=${median}ms p90=${p90}ms max=${max}ms`);
console.log(`under 3s: ${under}/${gaps.length} (${Math.round((under / gaps.length) * 100)}%)`);
