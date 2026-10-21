// Lists every source line that contains non-ASCII characters, escaped, so the
// corrupted Vietnamese literals can be rewritten safely.
const fs = require('fs');
const file = process.argv[2];
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
const esc = (s) => s.replace(/[^\x20-\x7E]/g, (c) =>
  '\\u' + c.codePointAt(0).toString(16).padStart(4, '0'));
lines.forEach((l, i) => {
  if (/[^\x00-\x7F]/.test(l)) console.log((i + 1) + ': ' + esc(l.trim()));
});
