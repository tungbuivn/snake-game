// Removes the now-unused ribbon polygon renderer (buildRibbon, its capacity
// helper and drawRibbon); the body is a single flat-coloured stroke.
const fs = require('fs');
const file = 'D:/snake/app/src/main/java/com/dsh/snake/GameView.java';
let src = fs.readFileSync(file, 'utf8');

const startMarker = '    /**\n     * Sweeps the smooth centre line';
const endMarker = '    private void drawHead(';
const start = src.indexOf(startMarker);
const end = src.indexOf(endMarker);
if (start < 0 || end < 0 || end <= start) {
  console.log('markers not found', start, end);
  process.exit(1);
}
const removed = src.slice(start, end);
src = src.slice(0, start) + src.slice(end);
fs.writeFileSync(file, src, 'utf8');
console.log('removed', removed.split('\n').length, 'lines');
console.log('still references pathMeasure:', src.includes('pathMeasure'));
console.log('still references ribbonPath:', src.includes('ribbonPath'));
console.log('still references sampleL:', src.includes('sampleL'));
