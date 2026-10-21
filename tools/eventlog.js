// Dumps the raw app event log with a readable timeline.
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2]);
const txt = ((buf.length > 1 && buf[0] === 0xFF && buf[1] === 0xFE) || buf.includes(0))
  ? buf.toString('utf16le')
  : buf.toString('utf8');
const lines = txt.replace(/^\uFEFF/, '').split(/\r?\n/).filter((l) => l.trim());
if (!lines.length) {
  console.log('empty log');
  process.exit(0);
}
let base = 0;
lines.forEach((line, i) => {
  const t = line.match(/^(\d{6,})/);
  if (!t) {
    console.log('  ?', line);
    return;
  }
  const ts = Number(t[1]);
  if (i === 0) base = ts;
  console.log(`${((ts - base) / 1000).toFixed(2)}s  ${line.slice(t[1].length).trim()}`);
});
