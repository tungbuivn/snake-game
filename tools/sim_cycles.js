// Compares fruit-throughput of the AutoPilot strategy (forward-only inside the
// free arc + BFS-greedy shortcut) on different Hamiltonian cycles:
//   A) single boustrophedon cycle
//   B) two strip cycles merged with a 2-opt exchange
//   C) MangoSnake-style randomized cycle (random split/mend, N iterations)
// The fruit is spawned inside the free arc, exactly like GameView.spawnFood().
const COLS = 20, ROWS = 40, TOTAL = COLS * ROWS;
const UP = 8, RIGHT = 4, DOWN = 2, LEFT = 1;
const xy = (c) => [c % COLS, (c / COLS) | 0];
const idx = (x, y) => y * COLS + x;
const inside = (x, y) => x >= 0 && y >= 0 && x < COLS && y < ROWS;
const DIRS = [[1, 0], [-1, 0], [0, 1], [0, -1]];

/* ---------------- cycle builders ---------------- */
function boustroOrder(y0, y1) {
  const out = [];
  for (let y = y0; y <= y1; y++) out.push(idx(0, y));
  for (let x = 1; x < COLS; x++) {
    if (x & 1) for (let y = y1; y >= y0 + 1; y--) out.push(idx(x, y));
    else for (let y = y0 + 1; y <= y1; y++) out.push(idx(x, y));
  }
  for (let x = COLS - 1; x >= 1; x--) out.push(idx(x, y0));
  return out;
}

function orderToNext(order) {
  const next = new Int32Array(TOTAL);
  for (let i = 0; i < order.length; i++) next[order[i]] = order[(i + 1) % order.length];
  return next;
}

function cycleA() { return boustroOrder(0, ROWS - 1); }

function cycleB() {
  const mid = ROWS / 2;
  const next = new Int32Array(TOTAL);
  for (const [y0, y1] of [[0, mid - 1], [mid, ROWS - 1]]) {
    const o = boustroOrder(y0, y1);
    for (let i = 0; i < o.length; i++) next[o[i]] = o[(i + 1) % o.length];
  }
  const prev = new Int32Array(TOTAL);
  for (let c = 0; c < TOTAL; c++) prev[next[c]] = c;
  const adj = (p, q) => { const [px, py] = xy(p), [qx, qy] = xy(q); return Math.abs(px - qx) + Math.abs(py - qy) === 1; };
  for (let x = 0; x < COLS; x++) {
    const a1 = (mid - 1) * COLS + x, b2 = mid * COLS + x, a2 = next[a1], b1 = prev[b2];
    if (adj(a2, b1)) { next[a1] = b2; next[b1] = a2; break; }
  }
  return orderFromNext(next);
}

function orderFromNext(next) {
  const order = [];
  let cur = 0;
  for (let i = 0; i < TOTAL; i++) { order.push(cur); cur = next[cur]; }
  return order;
}

/* -------- MangoSnake Cycle.java port (random split/mend) -------- */
const ARC = { 8: [0, -1], 4: [1, 0], 2: [0, 1], 1: [-1, 0] };
function zigzag(x, y) {
  if ((x === 1 && y % 2 === 0 && y > 0) || (x === COLS - 1 && y % 2 === 1)) return UP;
  if (x === 0 && y !== ROWS - 1) return DOWN;
  return y % 2 === 0 ? LEFT : RIGHT;
}
function cycleC(iterations, seed) {
  let s = seed >>> 0;
  const rnd = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  const map = new Int8Array(TOTAL);
  for (let y = 0; y < ROWS; y++) for (let x = 0; x < COLS; x++) map[idx(x, y)] = zigzag(x, y);
  const step = (cell) => { const d = ARC[map[cell]]; return idx((cell % COLS) + d[0], ((cell / COLS) | 0) + d[1]); };
  const loop = (start, stop) => {
    const out = []; let cur = start;
    for (let i = 0; i < TOTAL; i++) { cur = step(cur); if (cur < 0) return null; out.push(cur); if (cur === stop) return out; }
    return null;
  };
  const rewire = (p, q) => {
    const [px, py] = xy(p), [qx, qy] = xy(q);
    if (map[p] === UP || map[p] === DOWN) {
      if (px < qx) { map[p] = RIGHT; map[q] = LEFT; } else { map[p] = LEFT; map[q] = RIGHT; }
    } else {
      if (py < qy) { map[p] = DOWN; map[q] = UP; } else { map[p] = UP; map[q] = DOWN; }
    }
  };
  for (let it = 0; it < iterations; it++) {
    const cand = [];
    for (let y = 0; y < ROWS; y++) for (let x = 0; x < COLS; x++) {
      const a = idx(x, y), d = map[a];
      let bx, by, want;
      if (d === UP) { bx = x + 1; by = y - 1; want = DOWN; }
      else if (d === DOWN) { bx = x + 1; by = y + 1; want = UP; }
      else if (d === RIGHT) { bx = x + 1; by = y + 1; want = LEFT; }
      else { bx = x - 1; by = y + 1; want = RIGHT; }
      if (inside(bx, by) && map[idx(bx, by)] === want) cand.push([a, idx(bx, by)]);
    }
    if (!cand.length) continue;
    let [p, q] = cand[(rnd() * cand.length) | 0];
    let fwd = loop(p, q);
    if (!fwd) { fwd = loop(q, p); if (!fwd) continue; [p, q] = [q, p]; }
    rewire(p, q);
    const inLoop = new Set(fwd);
    const mc = [];
    for (let y = 0; y < ROWS; y++) for (let x = 0; x < COLS; x++) {
      const a = idx(x, y), d = map[a];
      let bx, by, want;
      if (d === UP) { bx = x + 1; by = y - 1; want = DOWN; }
      else if (d === DOWN) { bx = x + 1; by = y + 1; want = UP; }
      else if (d === RIGHT) { bx = x + 1; by = y + 1; want = LEFT; }
      else { bx = x - 1; by = y + 1; want = RIGHT; }
      if (!inside(bx, by)) continue;
      const b = idx(bx, by);
      if (map[b] !== want) continue;
      if (inLoop.has(a) === inLoop.has(b)) continue;
      mc.push([a, b]);
    }
    if (mc.length) { const [u, v] = mc[(rnd() * mc.length) | 0]; rewire(u, v); }
    else rewire(p, q); // no cross-loop pair -> undo the split (rewire is involutive)
  }
  const order = []; const seen = new Uint8Array(TOTAL);
  let cur = idx(0, ROWS - 1);
  for (let i = 0; i < TOTAL; i++) { if (seen[cur]) throw new Error('bad cycle'); seen[cur] = 1; order.push(cur); cur = step(cur); }
  return order;
}

/* ---------------- AutoPilot simulation ---------------- */
function validate(order) {
  if (order.length !== TOTAL) throw new Error('order length ' + order.length);
  const seen = new Uint8Array(TOTAL);
  for (let i = 0; i < order.length; i++) {
    const c = order[i];
    if (seen[c]) throw new Error('duplicate cell ' + c);
    seen[c] = 1;
    const nx = order[(i + 1) % order.length];
    const d = Math.abs((c % COLS) - (nx % COLS)) + Math.abs(((c / COLS) | 0) - ((nx / COLS) | 0));
    if (d !== 1) throw new Error('non adjacent ' + c + '->' + nx + ' at ' + i);
  }
}
function simulate(order, fruits, seed) {
  validate(order);
  let s = seed >>> 0;
  const rnd = () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  const pos = new Int32Array(TOTAL);
  order.forEach((c, i) => (pos[c] = i));
  const fwd = (a, b) => (pos[b] - pos[a] + TOTAL) % TOTAL;

  const body = [];
  const start = pos[idx((COLS / 2) | 0, (ROWS / 2) | 0)];
  for (let i = 0; i < 4; i++) body.push(order[(start - i + TOTAL) % TOTAL]);

  const blocked = new Uint8Array(TOTAL);
  const dist = new Int32Array(TOTAL).fill(-1);
  const queue = new Int32Array(TOTAL);

  const markBody = () => { blocked.fill(0); for (const c of body) blocked[c] = 1; };
  const computeDist = (food) => {
    dist.fill(-1);
    if (food < 0) return;
    let qh = 0, qt = 0;
    queue[qt++] = food; dist[food] = 0;
    while (qh < qt) {
      const cur = queue[qh++]; const [cx, cy] = xy(cur);
      for (const [dx, dy] of DIRS) {
        const nx = cx + dx, ny = cy + dy;
        if (!inside(nx, ny)) continue;
        const n = idx(nx, ny);
        if (dist[n] >= 0 || blocked[n]) continue;
        dist[n] = dist[cur] + 1; queue[qt++] = n;
      }
    }
  };
  const spawnFood = () => {
    const head = body[0], tail = body[body.length - 1];
    markBody();
    let tailDist = fwd(head, tail); if (tailDist === 0) tailDist = TOTAL;
    const free = [];
    for (let d = 1; d < tailDist; d++) { const c = order[(pos[head] + d) % TOTAL]; if (!blocked[c]) free.push(c); }
    if (free.length) return free[(rnd() * free.length) | 0];
    const all = []; for (let c = 0; c < TOTAL; c++) if (!blocked[c]) all.push(c);
    return all.length ? all[(rnd() * all.length) | 0] : -1;
  };

  let food = spawnFood();
  let steps = 0, eaten = 0, maxSteps = 0, rescued = 0, deaths = 0, firstDeath = null;
  let tailPasses = 0, firstTailPass = null, stalled = 0, respawns = 0;
  const perFruit = [];
  let sinceEat = 0;
  const seen = new Int32Array(TOTAL).fill(-1);
  let stamp = 0;
  const area = (start) => {
    stamp++;
    let qh = 0, qt = 0, cnt = 0;
    queue[qt++] = start; seen[start] = stamp;
    while (qh < qt) {
      const c = queue[qh++]; cnt++;
      const [cx, cy] = xy(c);
      for (const [dx, dy] of DIRS) {
        const nx = cx + dx, ny = cy + dy;
        if (!inside(nx, ny)) continue;
        const n = idx(nx, ny);
        if (seen[n] === stamp || blocked[n]) continue;
        seen[n] = stamp; queue[qt++] = n;
      }
    }
    return cnt;
  };
  while (eaten < fruits && steps < 400000) {
    const head = body[0], tail = body[body.length - 1];
    markBody();
    const hIdx = pos[head];
    let tailDist = fwd(head, tail); if (tailDist === 0) tailDist = TOTAL;
    // The fruit must stay inside the free arc, otherwise the tail overtakes it
    // and the head can never reach it again (permanent stall).
    let foodForward = food >= 0 ? fwd(head, food) : -1;
    if (!LEGACY && food >= 0 && foodForward >= tailDist) {
      food = spawnFood();
      markBody();
      foodForward = fwd(head, food);
      respawns++;
    }
    computeDist(food);
    const [hx, hy] = xy(head);
    const cand = [];
    for (const [dx, dy] of DIRS) {
      const nx = hx + dx, ny = hy + dy;
      if (!inside(nx, ny)) continue;
      const n = idx(nx, ny);
      const d = fwd(head, n);
      if (d === 0 || d > tailDist) continue;
      if (blocked[n] && n !== tail) continue;
      let score = 400000;
      if (food >= 0 && dist[n] >= 0) score = dist[n] * 8;
      if (LEGACY && foodForward > 0 && d > foodForward) score += 100000;
      if (d === tailDist) score += 4000;
      cand.push({ n, d, score });
    }
    let best = -1;
    if (cand.length) {
      if (LEGACY) {
        let bestScore = Infinity;
        for (const c of cand) if (c.score < bestScore) { bestScore = c.score; best = c.n; }
      } else {
        // Never overshoot the fruit on the cycle: those moves can only make the
        // head walk a full extra lap. Prefer no-overshoot candidates, and if all
        // of them overshoot take the shortest hop (guaranteed forward progress).
        const safeCand = foodForward > 0 ? cand.filter((c) => c.d <= foodForward) : cand;
        const pool = safeCand.length ? safeCand : cand;
        const useDist = pool.some((c) => c.score < 400000);
        let pick = pool[0];
        for (const c of pool) {
          if (useDist) { if (c.score < pick.score) pick = c; }
          else { if (c.d > pick.d) pick = c; }
        }
        best = pick.n;
      }
    }
    if (best < 0) {
      // AutoPilot falls back to the cycle-next cell.
      best = order[(pos[head] + 1) % TOTAL];
    }
    // GameView safety net: a blocked/wall target is replaced by the roomiest
    // free neighbour, and with no free neighbour at all the snake dies.
    if (best < 0 || (blocked[best] && best !== tail) || !inside(best % COLS, (best / COLS) | 0)) {
      rescued++;
      let alt = -1, altArea = -1;
      const [ahx, ahy] = xy(head);
      for (const [dx, dy] of DIRS) {
        const ax = ahx + dx, ay = ahy + dy;
        if (!inside(ax, ay)) continue;
        const c = idx(ax, ay);
        if (blocked[c]) continue;
        const a = area(c);
        if (a > altArea) { altArea = a; alt = c; }
      }
      if (alt < 0) {
        deaths++;
        if (deaths === 1 && !firstDeath) {
          firstDeath = 'len ' + body.length + ' head ' + head + ' tail ' + tail +
            ' food ' + food + ' steps ' + steps;
        }
        break;
      }
      best = alt;
    }
    if (best === tail && best !== food) {
      tailPasses++;
      if (!firstTailPass) {
        firstTailPass = 'step ' + steps + ' len ' + body.length + ' head ' + head +
          ' tail ' + tail + ' tailDist ' + tailDist + ' food ' + food;
      }
    }
    if (DEBUG && steps < 25) {
      console.log('  step', steps, 'len', body.length, 'head', head, 'tail', tail,
        'tailDist', tailDist, 'food', food, 'foodFwd', foodForward,
        'pick', best, 'dPick', fwd(head, best), 'distPick', dist[best]);
    }
    body.unshift(best);
    if (best === food) {
      eaten++;
      perFruit.push(sinceEat + 1);
      maxSteps = Math.max(maxSteps, sinceEat + 1);
      sinceEat = 0;
      food = spawnFood();
    } else {
      body.pop();
      sinceEat++;
    }
    steps++;
    if (sinceEat > 4000) {
      stalled = steps;
      console.log('  STALL detail: len', body.length, 'head', head, 'tail', tail,
        'food', food, 'foodFwd', foodForward, 'tailDist', tailDist);
      const tailPos = pos[tail];
      for (let k = 0; k < 4; k++) {
        const nx = (head % COLS) + DIRS[k][0], ny = ((head / COLS) | 0) + DIRS[k][1];
        if (!inside(nx, ny)) { console.log('   nb', DIRS[k], 'wall'); continue; }
        const n = idx(nx, ny);
        console.log('   nb', DIRS[k], 'cell', n, 'd', fwd(head, n),
          'blocked', blocked[n], 'dist', dist[n]);
      }
      break;
    }
  }
  perFruit.sort((a, b) => a - b);
  const avg = perFruit.reduce((a, b) => a + b, 0) / perFruit.length;
  const p95 = perFruit[Math.floor(perFruit.length * 0.95)] || 0;
  return { eaten, steps, avg, max: maxSteps, p95, perFruit, rescued, deaths, firstDeath, tailPasses, firstTailPass, stalled };
}

function report(name, order, fruits, seed) {
  const r = simulate(order, fruits, seed);
  console.log(name.padEnd(30), 'fruits', String(r.eaten).padStart(4),
    '| avg steps/fruit', r.avg.toFixed(2).padStart(6),
    '| p95', String(r.p95).padStart(3), '| max', String(r.max).padStart(3),
    '| rescued', String(r.rescued).padStart(3), '| deaths', r.deaths,
    '| tailpass', r.tailPasses, r.stalled ? '| STALL at ' + r.stalled : '',
    r.firstDeath ? '(' + r.firstDeath + ')' : '');
  if (r.firstTailPass) console.log('   first tail pass:', r.firstTailPass);
  return r;
}

const FRUITS = Number(process.argv[2] || 300);
const DEBUG = process.env.SIMDEBUG === '1';
const LEGACY = process.env.LEGACY === '1';
const ONLY = process.env.SIMONLY || '';
if (!ONLY || ONLY === 'A') report('A: single boustrophedon', cycleA(), FRUITS, 7);
if (!ONLY || ONLY === 'B') report('B: two strips + 2-opt', cycleB(), FRUITS, 7);
if (ONLY === 'C') {
  const it = Number(process.env.ITERS || 250);
  for (let sd = 1; sd <= Number(process.env.SEEDS || 4); sd++) {
    report('C: mango ' + it + ' seed ' + sd, cycleC(it, sd), FRUITS, 7);
  }
}
if (!ONLY) for (const it of [50, 100, 250]) report('C: mango random ' + it, cycleC(it, 99 + it), FRUITS, 7);
