// Extracts stroke/fill colours from an SVG (text), used to study a public
// domain snake illustration without needing to view the image.
const fs = require('fs');
const svg = fs.readFileSync(process.argv[2], 'utf8');
const re = /(?:fill|stroke)\s*[:=]\s*['"]?(#[0-9a-fA-F]{3,8})/g;
const counts = new Map();
let m;
while ((m = re.exec(svg)) !== null) {
  const c = m[1].toLowerCase();
  counts.set(c, (counts.get(c) || 0) + 1);
}
const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);
console.log('bytes', svg.length);
console.log('colours:', sorted.map(([c, n]) => c + ' x' + n).join('  '));
console.log('elements:', (svg.match(/<path|<rect|<ellipse|<circle|<polygon|<g[\s>]/g) || []).length);
