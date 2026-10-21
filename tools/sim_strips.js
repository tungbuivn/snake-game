// Validates the two-strip cycle: one single cycle, adjacency edges, all cells.
const COLS = 20, ROWS = 40, TOTAL = COLS * ROWS;
const next = new Int32Array(TOTAL);

function boustrophedon(y0, y1) {
  const order = [];
  for (let y = y0; y <= y1; y++) order.push(y * COLS);
  for (let x = 1; x < COLS; x++) {
    if (x & 1) for (let y = y1; y >= y0 + 1; y--) order.push(y * COLS + x);
    else for (let y = y0 + 1; y <= y1; y++) order.push(y * COLS + x);
  }
  for (let x = COLS - 1; x >= 1; x--) order.push(y0 * COLS + x);
  return order;
}
function linkStrip(y0, y1) {
  const order = boustrophedon(y0, y1);
  for (let i = 0; i < order.length; i++) next[order[i]] = order[(i + 1) % order.length];
}
const adj = (p, q) => Math.abs((p % COLS) - (q % COLS)) + Math.abs(Math.floor(p / COLS) - Math.floor(q / COLS)) === 1;

const mid = ROWS / 2;
linkStrip(0, mid - 1);
linkStrip(mid, ROWS - 1);
// merge
const prev = new Int32Array(TOTAL);
for (let c = 0; c < TOTAL; c++) prev[next[c]] = c;
let merged = false;
for (let x = 0; x < COLS && !merged; x++) {
  const a1 = (mid - 1) * COLS + x;
  const b2 = mid * COLS + x;
  const a2 = next[a1], b1 = prev[b2];
  if (adj(a2, b1)) { next[a1] = b2; next[b1] = a2; merged = true; }
}
console.log('merged:', merged, 'at x =', merged ? 'found' : 'none');

let cur = 0, ok = true, bad = null;
for (let i = 0; i < TOTAL; i++) {
  if (!adj(cur, next[cur])) { ok = false; bad = `non-adjacent ${cur}->${next[cur]}`; break; }
  cur = next[cur];
}
console.log('single cycle:', ok && cur === 0, bad || '', 'visited', TOTAL);
const seen = new Set();
cur = 0;
for (let i = 0; i < TOTAL; i++) { seen.add(cur); cur = next[cur]; }
console.log('covers all cells:', seen.size === TOTAL);
