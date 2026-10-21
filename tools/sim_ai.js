// Offline simulation of the dynamic-cycle AutoPilot to validate the splice.
// Mirrors AutoPilot.decide / tryJump and asserts:
//   - next/prev always form ONE cycle over all cells with adjacency edges
//   - the body is always a contiguous arc of that cycle
const COLS = 6, ROWS = 6, TOTAL = COLS * ROWS;
const next = new Int32Array(TOTAL), prev = new Int32Array(TOTAL);

function buildCycle() {
  const order = [];
  for (let y = 0; y < ROWS; y++) order.push(y * COLS);
  for (let x = 1; x < COLS; x++) {
    if (x & 1) for (let y = ROWS - 1; y >= 1; y--) order.push(y * COLS + x);
    else for (let y = 1; y <= ROWS - 1; y++) order.push(y * COLS + x);
  }
  for (let x = COLS - 1; x >= 1; x--) order.push(x);
  if (order.length !== TOTAL) throw new Error('order ' + order.length);
  for (let i = 0; i < TOTAL; i++) {
    const a = order[i], b = order[(i + 1) % TOTAL];
    next[a] = b; prev[b] = a;
  }
}
const adj = (p, q) => Math.abs((p % COLS) - (q % COLS)) + Math.abs(Math.floor(p / COLS) - Math.floor(q / COLS)) === 1;

function checkCycle() {
  let cur = 0;
  for (let i = 0; i < TOTAL; i++) {
    if (!adj(cur, next[cur])) return 'non-adjacent edge ' + cur + '->' + next[cur];
    if (prev[next[cur]] !== cur) return 'prev mismatch at ' + cur;
    cur = next[cur];
  }
  return cur === 0 ? null : 'cycle broken (returned to ' + cur + ')';
}

function bodyOnCycle(body) {
  for (let i = 1; i < body.length; i++) if (next[body[i]] !== body[i - 1]) return false;
  return true;
}

let inArc = new Uint8Array(TOTAL), arc = [];
function buildArc(head, tail) {
  inArc.fill(0); arc = [];
  let cur = next[head];
  for (let i = 0; i <= TOTAL && cur !== tail; i++) { inArc[cur] = 1; arc.push(cur); cur = next[cur]; }
}

function inSegment(a, b, cell) {
  let cur = a;
  for (let i = 0; i <= TOTAL; i++) {
    if (cur === cell) return true;
    if (cur === b) return false;
    cur = next[cur];
  }
  return false;
}

function tryJump(head, tail, target) {
  if (!inArc[target]) return false;
  const a = next[head], b = prev[target];
  if (a === target) return true;
  for (const u of arc) {
    const v = next[u];
    if (u === head || v === tail || u === target) continue;
    if (inSegment(a, b, u) || inSegment(a, b, v)) continue;
    if (adj(a, u) && adj(b, v)) {
      next[u] = a; prev[a] = u; next[b] = v; prev[v] = b;
      next[head] = target; prev[target] = head;
      return true;
    }
  }
  return false;
}

buildCycle();
let body = [0, prev[0], prev[prev[0]]];
for (let step = 0; step < 5000; step++) {
  const head = body[0], tail = body[body.length - 1];
  const blocked = new Uint8Array(TOTAL);
  for (const c of body) blocked[c] = 1;
  if (!bodyOnCycle(body)) { console.log('body left the cycle at step', step, body, 'next[head]', next[head]); break; }
  buildArc(head, tail);
  // pick any free neighbour at random (worst case input)
  const nb = [];
  const hx = head % COLS, hy = Math.floor(head / COLS);
  for (const [dx, dy] of [[1,0],[-1,0],[0,1],[0,-1]]) {
    const nx = hx + dx, ny = hy + dy;
    if (nx < 0 || nx >= COLS || ny < 0 || ny >= ROWS) continue;
    const c = ny * COLS + nx;
    if (!blocked[c]) nb.push(c);
  }
  let target = nb.length ? nb[Math.floor(Math.random() * nb.length)] : next[head];
  if (target !== next[head]) {
    if (!tryJump(head, tail, target)) target = next[head];
  }
  const err = checkCycle();
  if (err) { console.log('CYCLE BROKEN at step', step, err, 'head', head, 'target', target, 'a', next[head]); break; }
  // apply the move
  body.unshift(target);
  body.pop();
  if (!bodyOnCycle(body)) { console.log('body not contiguous after move at', step, body); break; }
  // the free arc must contain every free cell
  const blocked2 = new Uint8Array(TOTAL);
  for (const c of body) blocked2[c] = 1;
  buildArc(body[0], body[body.length - 1]);
  let free = 0;
  for (let c = 0; c < TOTAL; c++) if (!blocked2[c]) free++;
  if (arc.length !== free) {
    console.log('arc incomplete at', step, 'arc', arc.length, 'free', free);
    break;
  }
}
console.log('done, final body ok');
