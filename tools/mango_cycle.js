// Port of OrangoMango/MangoSnake's random Hamiltonian cycle generator
// (game/ai/Cycle.java) + comparison against the boustrophedon / 2-strip cycles
// used by AutoPilot. Validates the result is ONE Hamiltonian cycle.
const COLS = Number(process.env.COLS || 20), ROWS = Number(process.env.ROWS || 40);
const TOTAL = COLS * ROWS;

const ARC = { 8: [0, -1], 4: [1, 0], 2: [0, 1], 1: [-1, 0] };
const UP = 8, RIGHT = 4, DOWN = 2, LEFT = 1;
const inside = (x, y) => x >= 0 && y >= 0 && x < COLS && y < ROWS;
const idx = (x, y) => y * COLS + x;

function zigzag(x, y) {
  if (ROWS % 2 === 0) {
    if ((x === 1 && y % 2 === 0 && y > 0) || (x === COLS - 1 && y % 2 === 1)) return UP;
    if (x === 0) { if (y !== ROWS - 1) return DOWN; }
    return y % 2 === 0 ? LEFT : RIGHT;
  } else if (COLS % 2 === 0) {
    if ((y === 1 && x % 2 === 1) || (y === ROWS - 1 && x % 2 === 0)) return RIGHT;
    if (y === 0) return x === 0 ? DOWN : LEFT;
    return x % 2 === 0 ? DOWN : UP;
  }
  throw new Error('no cycle');
}

function buildMap() {
  const map = new Int8Array(TOTAL);
  for (let y = 0; y < ROWS; y++) for (let x = 0; x < COLS; x++) map[idx(x, y)] = zigzag(x, y);
  return map;
}

const step = (map, cell) => {
  const d = ARC[map[cell]];
  return d ? idx((cell % COLS) + d[0], Math.floor(cell / COLS) + d[1]) : -1;
};

// walk from start until stop (inclusive of arrival); null when it never arrives
function loop(map, start, stop) {
  const out = [];
  let cur = start;
  for (let i = 0; i < TOTAL; i++) {
    cur = step(map, cur);
    if (cur < 0) return null;
    out.push(cur);
    if (cur === stop) return out;
  }
  return null;
}

function split(map, rnd) {
  const cand = [];
  for (let y = 0; y < ROWS; y++) {
    for (let x = 0; x < COLS; x++) {
      const a = idx(x, y), d = map[a];
      let bx, by;
      if (d === UP) { bx = x + 1; by = y - 1; if (inside(bx, by) && map[idx(bx, by)] === DOWN) cand.push([a, idx(bx, by)]); }
      else if (d === DOWN) { bx = x + 1; by = y + 1; if (inside(bx, by) && map[idx(bx, by)] === UP) cand.push([a, idx(bx, by)]); }
      else if (d === RIGHT) { bx = x + 1; by = y + 1; if (inside(bx, by) && map[idx(bx, by)] === LEFT) cand.push([a, idx(bx, by)]); }
      else if (d === LEFT) { bx = x - 1; by = y + 1; if (inside(bx, by) && map[idx(bx, by)] === RIGHT) cand.push([a, idx(bx, by)]); }
    }
  }
  if (!cand.length) return null;
  const [p, q] = cand[(rnd() * cand.length) | 0];
  const fwd = loop(map, p, q);
  if (fwd) return [p, q, fwd];
  const back = loop(map, q, p);
  if (back) return [q, p, back];
  return null;
}

function rewire(map, p, q) {
  const px = p % COLS, py = Math.floor(p / COLS);
  const qx = q % COLS, qy = Math.floor(q / COLS);
  const pv = map[p] === UP || map[p] === DOWN;
  if (pv) {
    if (px < qx) { map[p] = RIGHT; map[q] = LEFT; } else { map[p] = LEFT; map[q] = RIGHT; }
  } else {
    if (py < qy) { map[p] = DOWN; map[q] = UP; } else { map[p] = UP; map[q] = DOWN; }
  }
}

function mend(map, spl, rnd) {
  const inLoop = new Set(spl[2]);
  const cand = [];
  const test = (x, y, bx, by, want) => {
    if (!inside(bx, by)) return;
    const a = idx(x, y), b = idx(bx, by);
    if (map[b] !== want) return;
    if (inLoop.has(a) === inLoop.has(b)) return;
    cand.push([a, b]);
  };
  for (let y = 0; y < ROWS; y++) {
    for (let x = 0; x < COLS; x++) {
      const d = map[idx(x, y)];
      if (d === UP) test(x, y, x + 1, y - 1, DOWN);
      else if (d === DOWN) test(x, y, x + 1, y + 1, UP);
      else if (d === RIGHT) test(x, y, x + 1, y + 1, LEFT);
      else if (d === LEFT) test(x, y, x - 1, y + 1, RIGHT);
    }
  }
  // No cross-loop corner pair exists: rewire the split pair again, which is its
  // own inverse, so the split is undone (MangoSnake calls modifyPath twice).
  let pick = cand.length ? cand[(rnd() * cand.length) | 0] : spl;
  return pick;
}

function generate(iterations, seed) {
  let s = seed >>> 0;
  const rnd = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  const map = buildMap();
  let applied = 0;
  for (let i = 0; i < iterations; i++) {
    const spl = split(map, rnd);
    if (!spl) continue;
    rewire(map, spl[0], spl[1]);
    const tu = mend(map, spl, rnd);
    rewire(map, tu[0], tu[1]);
    if (tu !== spl) applied++;
  }
  return { map, applied };
}

// flatten map into cycle order, verifying it is one Hamiltonian cycle
function flatten(map) {
  const start = idx(0, ROWS - 1);
  const order = [];
  const seen = new Uint8Array(TOTAL);
  let cur = start;
  for (let i = 0; i < TOTAL; i++) {
    if (seen[cur]) return { error: 'revisit at ' + cur };
    seen[cur] = 1;
    order.push(cur);
    const nx = step(map, cur);
    if (nx < 0) return { error: 'dead end at ' + cur };
    const d = Math.abs((cur % COLS) - (nx % COLS)) + Math.abs(Math.floor(cur / COLS) - Math.floor(nx / COLS));
    if (d !== 1) return { error: 'non adjacent ' + cur + '->' + nx };
    cur = nx;
  }
  if (cur !== start) return { error: 'closed at ' + cur };
  return { order };
}

function boustro() {
  const order = [];
  for (let y = 0; y < ROWS; y++) order.push(idx(0, y));
  for (let x = 1; x < COLS; x++) {
    if (x & 1) for (let y = ROWS - 1; y >= 1; y--) order.push(idx(x, y));
    else for (let y = 1; y <= ROWS - 1; y++) order.push(idx(x, y));
  }
  for (let x = COLS - 1; x >= 1; x--) order.push(idx(x, 0));
  return order;
}

// forward distance helper on any cycle order
function stat(order, name) {
  const pos = new Int32Array(TOTAL);
  order.forEach((c, i) => (pos[c] = i));
  const fwd = (a, b) => (pos[b] - pos[a] + TOTAL) % TOTAL;
  let sumUniform = 0, n = 0, sumNear = 0, m = 0;
  for (let i = 0; i < 20000; i++) {
    const a = (Math.random() * TOTAL) | 0, b = (Math.random() * TOTAL) | 0;
    sumUniform += fwd(a, b); n++;
    // pairs that are Manhattan-near (<= 4) but on the cycle
    const ax = a % COLS, ay = (a / COLS) | 0, bx = b % COLS, by = (b / COLS) | 0;
    if (Math.abs(ax - bx) + Math.abs(ay - by) <= 4) { sumNear += fwd(a, b); m++; }
  }
  console.log(name.padEnd(26), 'mean fwd(uniform)', (sumUniform / n).toFixed(1),
    '| mean fwd(near)', (sumNear / Math.max(1, m)).toFixed(1), '(' + m + ' pairs)');
}

const iterations = Number(process.argv[2] || 250);
const seed = Number(process.argv[3] || 12345);
stat(boustro(), 'boustrophedon');
const { map, applied } = generate(iterations, seed);
const res = flatten(map);
console.log('mango generate(' + iterations + ', seed ' + seed + ') applied', applied, '->', res.error ? 'INVALID: ' + res.error : 'valid Hamiltonian cycle');
if (!res.error) stat(res.order, 'mango random (' + iterations + ')');
