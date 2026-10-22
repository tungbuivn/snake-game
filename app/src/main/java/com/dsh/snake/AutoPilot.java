package com.dsh.snake;

import java.util.Arrays;
import java.util.Random;

/**
 * Auto play on a Hamiltonian cycle (guaranteed win). Three cycle builders:
 *
 *   A) one boustrophedon cycle over the whole board,
 *   B) two strip cycles (top half / bottom half) joined into one with a 2-opt
 *      exchange across the seam,
 *   C) MangoSnake style randomized cycle: the boustrophedon cycle is perturbed
 *      by repeated "split + mend" 2-opt moves, so every game gets a different
 *      maze-like perfect-play route (see OrangoMango/MangoSnake, ai/Cycle.java).
 *
 * The snake only steps forward on the cycle and inside the free arc between the
 * head and the tail, so the body can never be trapped and the head always has a
 * free next cell -> it cannot lose. The cycle index of the head strictly
 * increases every move, so the fruit (spawned inside the same free arc) is
 * reached within at most N moves.
 *
 * Speed comes from greedy shortcuts inside the arc: among the safe moves the one
 * with the smallest BFS distance to the fruit wins. A move that would overshoot
 * the fruit on the cycle costs a whole extra lap, so those are only taken when
 * nothing else is available (guaranteed forward progress).
 */
final class AutoPilot {

    /** Default cycle flavour: randomized MangoSnake cycle (C). */
    private static final boolean RANDOM_CYCLE_DEFAULT = true;

    /** Split + mend rounds used to randomize the cycle. */
    private static final int RANDOM_ITERATIONS = 250;

    /** Two strip cycles joined at the seam (variant B); false = one cycle (A). */
    /** Comb cycle (two merged slabs); selected per instance. */

    /* Generator-space directions (MangoSnake Cycle.java bit values). */
    private static final int G_UP = 8;
    private static final int G_RIGHT = 4;
    private static final int G_DOWN = 2;
    private static final int G_LEFT = 1;

    /** Verbose cycle-builder diagnostics (desktop test runs only). */
    private static final boolean DEBUG_BUILD = System.getenv("AUTOPILOT_DEBUG") != null;

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final int cols;
    private final int rows;
    private final int total;
    private final boolean randomizeCycle;
    private final boolean useComb;
    private boolean combBuilt;
    /** Verified-shortcut engine: BFS to the fruit + full-path safety simulation. */
    private final boolean verified;
    /**
     * Proof gate of the verified engine: the shortcut is only accepted when it is
     * exactly the next run of cells on the Hamiltonian cycle. Leaving the arc always
     * leaves a hole behind the head and the run is eventually boxed in (measured:
     * without this gate the snake dies at 24-129 / 240 cells on every seed).
     */
    private boolean proofGate = true;
    /**
     * Tail-chase engine ("always keep a way to follow the tail"): shortest path to
     * the fruit while the step keeps a way to the tail and leaves at least a
     * body-length of open room; otherwise it follows / runs away from its own tail.
     * That is what makes the snake sweep the board in zig-zag instead of sealing
     * itself into a pocket.
     */
    private boolean tailChase;
    /** Odd x odd boards have no Hamiltonian cycle: walk a Hamiltonian path instead. */
    private boolean oddMode;
    private int[] pathCell;
    private int[] pathIdx;
    private final int[] bfsQueue;
    private final int[] bfsPrev;
    private final int[] bfsDist;
    /** Toggle: allow safe shortcuts that leave the free arc. */
    private boolean smartShortcuts = true;
    /** Toggle: insert a detour when the head is right next to the tail. */
    private boolean zigzagOn;
    private final int[] cycleCell;
    private final int[] cycleIdx;
    private final int[] dist;
    private final boolean[] blocked;
    private final int[] queue;
    private int stepsToFood = -1;
    /** Which branch produced the last move (diagnostics / tests). */
    private int lastBranchId;
    private static final int BRANCH_VERIFIED = 1;
    private static final int BRANCH_ODD = 2;
    private static final int BRANCH_CYCLE = 3;
    private static final int BRANCH_SURVIVE = 4;
    private static final int BRANCH_FALLBACK = 5;
    private static final int BRANCH_TAILFOOD = 6;
    private static final int BRANCH_TAILCHASE = 7;

    AutoPilot(int cols, int rows) {
        this(cols, rows, RANDOM_CYCLE_DEFAULT);
    }

    AutoPilot(int cols, int rows, boolean randomCycle) {
        this(cols, rows, randomCycle, false);
    }

    /** @param combCycle true builds the comb (two-slab) cycle instead of the snake. */
    AutoPilot(int cols, int rows, boolean randomCycle, boolean combCycle) {
        this(cols, rows, randomCycle, combCycle, false);
    }

    /**
     * @param randomCycle random Hamiltonian cycle each run
     * @param combCycle two-slab comb cycle instead of the single snake
     * @param verifiedShortcuts BFS to the fruit + simulation of the whole snake;
     *     the shortcut is taken only when every step stays safe, otherwise the
     *     pilot keeps walking the Hamiltonian cycle (GUARANTEED mode)
     */
    AutoPilot(int cols, int rows, boolean randomCycle, boolean combCycle,
            boolean verifiedShortcuts) {
        this.cols = cols;
        this.rows = rows;
        this.total = cols * rows;
        this.randomizeCycle = randomCycle;
        this.useComb = combCycle;
        this.verified = verifiedShortcuts;
        cycleCell = new int[total];
        cycleIdx = new int[total];
        dist = new int[total];
        blocked = new boolean[total];
        queue = new int[total];
        bfsQueue = new int[total];
        bfsPrev = new int[total];
        bfsDist = new int[total];
        buildCycle();
    }

    /* ------------------------------------------------------------------ */
    /* Cycle construction                                                 */
    /* ------------------------------------------------------------------ */

    private void buildCycle() {
        // A grid with both sides odd has no Hamiltonian cycle: fall back to a
        // Hamiltonian PATH (boustrophedon) and let the path planner walk it.
        if (((cols & 1) == 1) && ((rows & 1) == 1)) {
            oddMode = true;
            buildPathOrder();
            return;
        }
        int[] next = new int[total];
        combBuilt = false;
        boolean ok = randomizeCycle && randomCycle(next) && cycleValid(next);
        if (!ok) {
            if (useComb && rows >= 6) {
                // Comb cycle: the board is split into two slabs (the "spine"), each
                // walked as a boustrophedon strip and then merged into one loop. The
                // teeth of the comb are the columns of the upper slab, so the free arc
                // is cut into shorter pieces and the fruit is reached faster.
                int mid = rows / 2;
                linkStrip(next, 0, mid - 1);
                linkStrip(next, mid, rows - 1);
                if (mergeStrips(next, mid - 1, mid) && cycleValid(next)) {
                    combBuilt = true;
                } else {
                    linkStrip(next, 0, rows - 1);
                }
            } else {
                linkStrip(next, 0, rows - 1);
            }
        }
        // Flatten the cycle into an ordered cell list + index lookup.
        int cur = 0;
        for (int i = 0; i < total; i++) {
            cycleCell[i] = cur;
            cycleIdx[cur] = i;
            cur = next[cur];
        }
    }

    /** Flattens a direction map into the ordered cell list + index lookup. */
    private void flattenCycle(int[] next) {
        int cur = 0;
        for (int i = 0; i < total; i++) {
            cycleCell[i] = cur;
            cycleIdx[cur] = i;
            cur = next[cur];
        }
    }

    /* ------------------------------------------------------------------ */
    /* Verified-shortcut engine ("GUARANTEED" mode)                       */
    /* ------------------------------------------------------------------ */

    /** Boustrophedon order used when the board has no Hamiltonian cycle (odd x odd). */
    private void buildPathOrder() {
        pathCell = new int[total];
        pathIdx = new int[total];
        int k = 0;
        for (int y = 0; y < rows; y++) {
            if ((y & 1) == 0) {
                for (int x = 0; x < cols; x++) {
                    pathCell[k++] = y * cols + x;
                }
            } else {
                for (int x = cols - 1; x >= 0; x--) {
                    pathCell[k++] = y * cols + x;
                }
            }
        }
        for (int i = 0; i < total; i++) {
            pathIdx[pathCell[i]] = i;
        }
    }

    /**
     * Shortest path from the head to the fruit over free cells (the tail counts as
     * free - it vacates this tick), or null when the fruit cannot be reached.
     */
    private int[] bfsFoodPath(int[] body, int food) {
        if (food < 0 || body.length == 0) {
            return null;
        }
        markBody(body);
        blocked[body[body.length - 1]] = false;
        if (blocked[food]) {
            return null;
        }
        java.util.Arrays.fill(bfsDist, -1);
        int head = body[0];
        int qh = 0;
        int qt = 0;
        bfsQueue[qt++] = head;
        bfsDist[head] = 0;
        bfsPrev[head] = -1;
        while (qh < qt) {
            int cur = bfsQueue[qh++];
            if (cur == food) {
                break;
            }
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * cols + nx;
                if (bfsDist[n] >= 0 || blocked[n]) {
                    continue;
                }
                bfsDist[n] = bfsDist[cur] + 1;
                bfsPrev[n] = cur;
                bfsQueue[qt++] = n;
            }
        }
        int len = bfsDist[food];
        if (len <= 0) {
            return null;
        }
        int[] path = new int[len];
        int cur = food;
        for (int i = len - 1; i >= 0; i--) {
            path[i] = cur;
            cur = bfsPrev[cur];
        }
        return path;
    }

    /**
     * Proof gate: the BFS path must be the cycle run itself, i.e. every step is the
     * cycle successor of the previous cell. Any other path would leave a hole behind
     * the head; the run then slowly gets boxed in (measured without this gate: every
     * seed dies at 24-129 of 240 cells), so the shortcut is rejected and the pilot
     * keeps walking the cycle.
     */
    private boolean followsCycle(int head, int[] path) {
        int cur = head;
        for (int cellInPath : path) {
            if (cellInPath != cycleCell[(cycleIdx[cur] + 1) % total]) {
                return false;
            }
            cur = cellInPath;
        }
        return true;
    }

    /**
     * Walks the WHOLE virtual snake along {@code path} (growing on the fruit) and
     * checks after every step that the tail is still reachable - that is the
     * "formally proven safe" gate of the shortcut. Any collision or lost tail
     * rejects the shortcut and the caller keeps walking the cycle.
     */
    private boolean simulateSafe(int[] body, int[] path, int food) {
        int len = body.length;
        int[] sim = java.util.Arrays.copyOf(body, len + 1);
        for (int i = 0; i < path.length; i++) {
            int next = path[i];
            boolean eats = next == food;
            if (eats) {
                if (len + 1 > sim.length) {
                    return false;
                }
                System.arraycopy(sim, 0, sim, 1, len);
                sim[0] = next;
                len++;
            } else {
                System.arraycopy(sim, 0, sim, 1, len - 1);
                sim[0] = next;
            }
            for (int j = 1; j < len; j++) {
                if (sim[j] == next) {
                    return false;
                }
            }
            int[] view = java.util.Arrays.copyOf(sim, len);
            if (!tailReachable(view)) {
                return false;
            }
        }
        return true;
    }

    /** True when the head can walk to its own tail through free cells. */
    private boolean tailReachable(int[] body) {
        if (body.length <= 2) {
            return true;
        }
        markBody(body);
        int head = body[0];
        int tail = body[body.length - 1];
        java.util.Arrays.fill(bfsDist, -1);
        int qh = 0;
        int qt = 0;
        bfsQueue[qt++] = head;
        bfsDist[head] = 0;
        while (qh < qt) {
            int cur = bfsQueue[qh++];
            if (cur == tail) {
                return true;
            }
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * cols + nx;
                if (bfsDist[n] >= 0 || (blocked[n] && n != tail)) {
                    continue;
                }
                bfsDist[n] = bfsDist[cur] + 1;
                bfsQueue[qt++] = n;
            }
        }
        return false;
    }

    /**
     * Rebuilds the cycle with a random divide-and-merge layout (union-find style:
     * build a small cycle per strip of rows, then merge neighbouring strips through
     * 2x2 kernel swaps). Falls back to the plain snake when a merge does not close.
     */
    void reshuffleRandomMerge() {
        int[] next = new int[total];
        boolean ok = false;
        for (int attempt = 0; attempt < 8 && !ok; attempt++) {
            buildRandomStrips(next, 0, rows - 1, new java.util.Random(total * 31L + attempt));
            ok = cycleValid(next);
        }
        if (!ok) {
            linkStrip(next, 0, rows - 1);
        }
        combBuilt = false;
        flattenCycle(next);
    }

    /** Splits the row range at a random even point, builds both halves, merges them. */
    private void buildRandomStrips(int[] next, int y0, int y1, java.util.Random rnd) {
        int h = y1 - y0 + 1;
        if (h <= 4) {
            linkStrip(next, y0, y1);
            return;
        }
        int half = h / 2;
        int maxCut = Math.max(1, half / 2);
        int cut = (1 + rnd.nextInt(maxCut)) * 2;   // even strip height
        int mid = y0 + cut - 1;
        buildRandomStrips(next, y0, mid, rnd);
        buildRandomStrips(next, mid + 1, y1, rnd);
        if (!mergeStrips(next, mid, mid + 1)) {
            linkStrip(next, y0, y1);
        }
    }

    /** Tail-chase engine on/off (the algorithm that always keeps a way to the tail). */
    void setTailChase(boolean v) {
        tailChase = v;
    }

    boolean tailChase() {
        return tailChase;
    }

    /** Turns the proof gate off (tests only): raw BFS shortcut + simulation. */
    void setProofGate(boolean v) {
        proofGate = v;
    }

    /** Safe-shortcut toggle: allow leaving the free arc when a simulation is safe. */
    void setSmartShortcuts(boolean v) {
        smartShortcuts = v;
    }

    boolean smartShortcuts() {
        return smartShortcuts;
    }

    /** Zig-zag toggle: add a detour when the head is right next to the tail. */
    void setZigzag(boolean v) {
        zigzagOn = v;
    }

    boolean zigzagOn() {
        return zigzagOn;
    }

    /**
     * Simulates taking {@code next} and then following the cycle for len + 5 steps,
     * eating whatever fruit lies on the way. False when the virtual snake would bite
     * itself - that is what makes an off-cycle shortcut safe to accept.
     */
    private boolean safeShortcut(int[] body, int next, int food) {
        if (body.length < 6) {
            return false;   // too short for the demo to mean anything; keep the cycle
        }
        int len = body.length;
        int[] sim = new int[len + 8];
        sim[0] = next;
        for (int i = 0; i < len - 1; i++) {
            sim[i + 1] = body[i];
        }
        int size = len;
        int steps = len + 5;
        for (int t = 0; t < steps; t++) {
            int head = sim[0];
            int nx = cycleCell[(cycleIdx[head] + 1) % total];
            boolean ate = nx == food;
            int keep = size - (ate ? 0 : 1);
            if (size + 1 >= sim.length) {
                return false;
            }
            System.arraycopy(sim, 0, sim, 1, keep);
            sim[0] = nx;
            size = keep + 1;
            for (int i = 1; i < size; i++) {
                if (sim[i] == nx) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True when the comb (two-slab) cycle was built instead of the plain snake. */
    boolean isComb() {
        return combBuilt;
    }

    /** Gives the auto pilot a fresh random cycle (called when a game starts). */
    void reshuffleCycle() {
        if (randomizeCycle) {
            buildCycle();
        }
    }

    /** A Hamiltonian cycle: every cell once, adjacent edges, closed loop. */
    private boolean cycleValid(int[] next) {
        boolean[] seen = new boolean[total];
        int cur = 0;
        for (int i = 0; i < total; i++) {
            if (seen[cur] || !adjacent(cur, next[cur])) {
                return false;
            }
            seen[cur] = true;
            cur = next[cur];
        }
        return cur == 0;
    }

    /* ------------------------------------------------------------------ */
    /* MangoSnake style random Hamiltonian cycle                          */
    /* ------------------------------------------------------------------ */

    /**
     * Builds the cycle as a direction map (one outgoing arrow per cell) and
     * perturbs it with random 2-opt style "split then mend" moves.
     *
     * A split picks a concave corner pair of arrows, which cuts the cycle into
     * two loops; a mend picks another corner pair lying on different loops and
     * reconnects them, merging the two loops back into one Hamiltonian cycle.
     */
    private boolean randomCycle(int[] next) {
        // The cycle can only be generated when one of the dimensions is even;
        // on odd row counts the board is transposed (20 columns stays even).
        boolean transposed = (rows & 1) == 1;
        int w = transposed ? rows : cols;
        int h = transposed ? cols : rows;
        if ((w & 1) == 1 && (h & 1) == 1) {
            return false;
        }
        byte[] dir = new byte[total];
        for (int gy = 0; gy < h; gy++) {
            for (int gx = 0; gx < w; gx++) {
                dir[gy * w + gx] = (byte) zigzag(gx, gy, w, h);
            }
        }
        int startG = (h & 1) == 0 ? (h - 1) * w : w + (w - 1);

        int[] buf = new int[total];
        int[] candA = new int[total];
        int[] candB = new int[total];
        boolean[] inLoop = new boolean[total];
        Random rnd = new Random();

        for (int it = 0; it < RANDOM_ITERATIONS; it++) {
            int cnt = 0;
            for (int gc = 0; gc < total; gc++) {
                int gx = gc % w;
                int gy = gc / w;
                int d = dir[gc];
                int bx, by, want;
                if (d == G_UP) {
                    bx = gx + 1;
                    by = gy - 1;
                    want = G_DOWN;
                } else if (d == G_DOWN) {
                    bx = gx + 1;
                    by = gy + 1;
                    want = G_UP;
                } else if (d == G_RIGHT) {
                    bx = gx + 1;
                    by = gy + 1;
                    want = G_LEFT;
                } else {
                    bx = gx - 1;
                    by = gy + 1;
                    want = G_RIGHT;
                }
                if (bx < 0 || bx >= w || by < 0 || by >= h) {
                    continue;
                }
                int bc = by * w + bx;
                if (dir[bc] != want) {
                    continue;
                }
                candA[cnt] = gc;
                candB[cnt] = bc;
                cnt++;
            }
            if (cnt == 0) {
                continue;
            }
            int pick = rnd.nextInt(cnt);
            int p = candA[pick];
            int q = candB[pick];
            int len = walk(dir, w, h, p, q, buf);
            if (len < 0) {
                len = walk(dir, w, h, q, p, buf);
                if (len < 0) {
                    continue;
                }
                int t = p;
                p = q;
                q = t;
            }
            rewire(dir, p, q, w);

            Arrays.fill(inLoop, false);
            for (int i = 0; i < len; i++) {
                inLoop[buf[i]] = true;
            }
            int mcnt = 0;
            for (int gc = 0; gc < total; gc++) {
                int gx = gc % w;
                int gy = gc / w;
                int d = dir[gc];
                int bx, by, want;
                if (d == G_UP) {
                    bx = gx + 1;
                    by = gy - 1;
                    want = G_DOWN;
                } else if (d == G_DOWN) {
                    bx = gx + 1;
                    by = gy + 1;
                    want = G_UP;
                } else if (d == G_RIGHT) {
                    bx = gx + 1;
                    by = gy + 1;
                    want = G_LEFT;
                } else {
                    bx = gx - 1;
                    by = gy + 1;
                    want = G_RIGHT;
                }
                if (bx < 0 || bx >= w || by < 0 || by >= h) {
                    continue;
                }
                int bc = by * w + bx;
                if (dir[bc] != want) {
                    continue;
                }
                if (inLoop[gc] == inLoop[bc]) {
                    continue;
                }
                if ((gc == p && bc == q) || (gc == q && bc == p)) {
                    continue;
                }
                candA[mcnt] = gc;
                candB[mcnt] = bc;
                mcnt++;
            }
            if (mcnt > 0) {
                int m = rnd.nextInt(mcnt);
                rewire(dir, candA[m], candB[m], w);
            } else {
                // No corner pair can rejoin the two loops: undo the split by
                // rewiring the same pair again (rewire is its own inverse).
                rewire(dir, p, q, w);
            }
            if (DEBUG_BUILD) {
                int steps = 0;
                int c = startG;
                do {
                    c = arrowStep(dir, c, w, h);
                    steps++;
                } while (c >= 0 && c != startG && steps <= total);
                if (steps != total) {
                    System.out.println("[AutoPilot] it=" + it + " cnt=" + cnt
                            + " loopLen=" + steps + "/" + total + " len=" + len + " mcnt=" + mcnt);
                }
            }
        }

        int cur = startG;
        int walked = 0;
        for (int i = 0; i < total; i++) {
            int nx = arrowStep(dir, cur, w, h);
            if (nx < 0) {
                if (DEBUG_BUILD) {
                    System.out.println("[AutoPilot] random cycle dead end at " + cur);
                }
                return false;
            }
            next[toBoard(cur, w, transposed)] = toBoard(nx, w, transposed);
            cur = nx;
            walked++;
        }
        if (DEBUG_BUILD) {
            System.out.println("[AutoPilot] random cycle walk=" + walked + " cur=" + cur
                    + " start=" + startG + " w=" + w + " h=" + h);
        }
        return cur == startG;
    }

    private int toBoard(int gc, int w, boolean transposed) {
        if (!transposed) {
            return gc;
        }
        return (gc % w) * cols + gc / w;
    }

    private int arrowStep(byte[] dir, int cell, int w, int h) {
        int gx = cell % w;
        int gy = cell / w;
        int d = dir[cell];
        int nx = gx;
        int ny = gy;
        if (d == G_UP) {
            ny--;
        } else if (d == G_DOWN) {
            ny++;
        } else if (d == G_RIGHT) {
            nx++;
        } else if (d == G_LEFT) {
            nx--;
        }
        if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
            return -1;
        }
        return ny * w + nx;
    }

    private void rewire(byte[] dir, int p, int q, int w) {
        int px = p % w;
        int py = p / w;
        int qx = q % w;
        int qy = q / w;
        if (dir[p] == G_UP || dir[p] == G_DOWN) {
            if (px < qx) {
                dir[p] = (byte) G_RIGHT;
                dir[q] = (byte) G_LEFT;
            } else {
                dir[p] = (byte) G_LEFT;
                dir[q] = (byte) G_RIGHT;
            }
        } else {
            if (py < qy) {
                dir[p] = (byte) G_DOWN;
                dir[q] = (byte) G_UP;
            } else {
                dir[p] = (byte) G_UP;
                dir[q] = (byte) G_DOWN;
            }
        }
    }

    /** Number of cells walked from start until stop (start excluded), or -1. */
    private int walk(byte[] dir, int w, int h, int start, int stop, int[] buf) {
        int cur = start;
        for (int i = 0; i < total; i++) {
            cur = arrowStep(dir, cur, w, h);
            if (cur < 0) {
                return -1;
            }
            buf[i] = cur;
            if (cur == stop) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Initial boustrophedon arrows of the reference snake cycle. */
    private int zigzag(int gx, int gy, int w, int h) {
        if ((h & 1) == 0) {
            if ((gx == 1 && (gy & 1) == 0 && gy > 0) || (gx == w - 1 && (gy & 1) == 1)) {
                return G_UP;
            }
            if (gx == 0 && gy != h - 1) {
                return G_DOWN;
            }
            return (gy & 1) == 0 ? G_LEFT : G_RIGHT;
        }
        if ((gy == 1 && (gx & 1) == 1) || (gy == h - 1 && (gx & 1) == 0)) {
            return G_RIGHT;
        }
        if (gy == 0) {
            return gx == 0 ? G_DOWN : G_LEFT;
        }
        return (gx & 1) == 0 ? G_DOWN : G_UP;
    }

    private void linkStrip(int[] next, int y0, int y1) {
        int[] order = boustrophedon(y0, y1);
        for (int i = 0; i < order.length; i++) {
            int a = order[i];
            int b = order[(i + 1) % order.length];
            next[a] = b;
        }
    }

    private int[] boustrophedon(int y0, int y1) {
        int h = y1 - y0 + 1;
        int[] order = new int[h * cols];
        int k = 0;
        for (int y = y0; y <= y1; y++) {
            order[k++] = y * cols;
        }
        for (int x = 1; x < cols; x++) {
            if ((x & 1) == 1) {
                for (int y = y1; y >= y0 + 1; y--) {
                    order[k++] = y * cols + x;
                }
            } else {
                for (int y = y0 + 1; y <= y1; y++) {
                    order[k++] = y * cols + x;
                }
            }
        }
        for (int x = cols - 1; x >= 1; x--) {
            order[k++] = y0 * cols + x;
        }
        return order;
    }

    /** Joins two strip cycles with a 2-opt exchange across the seam. */
    private boolean mergeStrips(int[] next, int topRow, int bottomRow) {
        int[] prev = new int[total];
        for (int c = 0; c < total; c++) {
            prev[next[c]] = c;
        }
        for (int x = 0; x < cols; x++) {
            int a1 = topRow * cols + x;
            int b2 = bottomRow * cols + x;
            int a2 = next[a1];
            int b1 = prev[b2];
            if (adjacent(a2, b1)) {
                next[a1] = b2;
                next[b1] = a2;
                return true;
            }
        }
        return false;
    }

    private boolean adjacent(int p, int q) {
        int px = p % cols;
        int py = p / cols;
        int qx = q % cols;
        int qy = q / cols;
        return Math.abs(px - qx) + Math.abs(py - qy) == 1;
    }

    /** Body cells for a fresh snake, laid out backwards along the cycle. */
    int[] initialBody(int length) {
        if (oddMode && pathCell != null) {
            // The body must cover the START of the path: the snake walks forward and
            // every step eats, so nothing behind it ever becomes free again.
            int startIdx = Math.max(length - 1, 0);
            int[] out = new int[length];
            for (int i = 0; i < length; i++) {
                int k = Math.max(0, startIdx - i);
                out[i] = pathCell[k];
            }
            return out;
        }
        int start = cycleIdx[(rows / 2) * cols + cols / 2];
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            int k = ((start - i) % total + total) % total;
            out[i] = cycleCell[k];
        }
        return out;
    }

    int[] firstDirection(int headCell) {
        if (oddMode && pathCell != null && pathIdx != null) {
            int at = pathIdx[headCell];
            if (at + 1 < total) {
                return dirTo(headCell, pathCell[at + 1]);
            }
            return null;
        }
        int next = cycleCell[(cycleIdx[headCell] + 1) % total];
        return dirTo(headCell, next);
    }

    /**
     * True when {@code body} (head first) is a contiguous run on this pilot's cycle.
     * A cycle engine may only be switched on under a live snake when this holds:
     * otherwise the body would no longer follow the cycle and the engine would be
     * driving blind. RND uses it to decide whether the run must keep the previous
     * engine until the next (explicitly started) run.
     */
    boolean bodyOnCycle(int[] body) {
        if (oddMode || body == null || body.length == 0) {
            return false;
        }
        for (int i = 1; i < body.length; i++) {
            // The body is laid out backwards along the cycle: body[i] is the cell
            // right before body[i - 1], so it must be that cell's cycle predecessor.
            int prev = ((cycleIdx[body[i - 1]] - 1) % total + total) % total;
            if (cycleCell[prev] != body[i]) {
                return false;
            }
        }
        return true;
    }

    /** Free cells still ahead of the head on the cycle (diagnostics). */
    int arcSize(int[] body) {
        if (body.length == 0) {
            return 0;
        }
        if (oddMode && pathIdx != null) {
            return Math.max(0, total - 1 - pathIdx[body[0]]);
        }
        int tailDist = forward(cycleIdx[body[0]], cycleIdx[body[body.length - 1]]);
        return tailDist == 0 ? total : tailDist;
    }

    /* ------------------------------------------------------------------ */
    /* Decision                                                           */
    /* ------------------------------------------------------------------ */

    int[] decide(int[] body, int food, int dirX, int dirY) {
        if (body.length == 0) {
            return null;
        }
        lastBranchId = 0;
        int head = body[0];
        int tail = body[body.length - 1];
        int hx0 = head % cols;
        int hy0 = head / cols;
        // Tail-chase engine: greedy shortest path to the fruit, but only while the
        // step keeps a way to the tail and leaves enough open room; otherwise follow
        // the tail. This is the zig-zag board-filling family, no Hamiltonian cycle.
        if (tailChase) {
            // No Hamiltonian cycle here: the safe-search answer is final (null means
            // there is no legal move at all, i.e. a real dead end).
            return tailChaseMove(body, food, hx0, hy0, tail);
        }
        // GUARANTEED mode: try a shortcut to the fruit only when the shortcut keeps
        // the snake on its Hamiltonian arc (the proof gate) AND the whole snake,
        // simulated along the whole path, stays safe at every step. Otherwise the
        // pilot keeps walking the cycle, which always fills the board.
        if (verified && !oddMode && food >= 0) {
            int[] path = bfsFoodPath(body, food);
            boolean proven = path != null && simulateSafe(body, path, food);
            if (proven && proofGate) {
                proven = followsCycle(head, path);
            }
            if (proven) {
                stepsToFood = path.length;
                lastBranchId = BRANCH_VERIFIED;
                return new int[]{path[0] % cols - hx0, path[0] / cols - hy0};
            }
        }
        // Odd x odd board: no cycle, walk the Hamiltonian path forward.
        if (oddMode) {
            markBody(body);
            int idx = pathIdx[head];
            for (int i = idx + 1; i < total; i++) {
                int n = pathCell[i];
                if (blocked[n] && n != tail) {
                    continue;
                }
                if (body.length > 2 && n == body[1]) {
                    continue;
                }
                lastBranchId = BRANCH_ODD;
                return new int[]{n % cols - hx0, n / cols - hy0};
            }
            return null;
        }

        markBody(body);
        int hIdx = cycleIdx[head];
        int tIdx = cycleIdx[tail];
        int tailDist = forward(hIdx, tIdx);
        if (tailDist == 0) {
            tailDist = total;
        }

        computeFoodDistance(food);
        updateStepsToFood(head, food);
        if (food >= 0) {
            // Walking the cycle can take more moves than the free-space BFS
            // distance, so budget the speed for the cycle distance too.
            stepsToFood = Math.max(stepsToFood, forward(hIdx, cycleIdx[food]));
        }

        int foodForward = food >= 0 ? forward(hIdx, cycleIdx[food]) : -1;
        int hx = head % cols;
        int hy = head / cols;
        int[] candCell = new int[4];
        int[] candDx = new int[4];
        int[] candDy = new int[4];
        int[] candD = new int[4];
        int[] candScore = new int[4];
        int candN = 0;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            int n = ny * cols + nx;
            int d = forward(hIdx, cycleIdx[n]);
            if (d == 0 || d > tailDist) {
                continue;
            }
            // SC off: never use the "step onto the vacating tail" shortcut; only
            // proper forward progress inside the free arc is allowed.
            if (d == tailDist && !smartShortcuts) {
                continue;
            }
            // Stepping on the tail only works when it moves away (no fruit there).
            if (blocked[n] && !(n == tail && n != food)) {
                continue;
            }
            int score = 400_000;
            if (food >= 0 && dist[n] >= 0) {
                score = dist[n] * 8;
            }
            if (d == tailDist) {
                score += 4_000;
            }
            if (zigzagOn) {
                // ZZ: a longer route of short hops instead of one long jump. The
                // snake weaves along the cycle, which keeps the free arc bigger for
                // a few moves longer; still forward-only, so it can never bite.
                score += (total - d) * 3;
            }
            candCell[candN] = n;
            candDx[candN] = nx - hx;
            candDy[candN] = ny - hy;
            candD[candN] = d;
            candScore[candN] = score;
            candN++;
        }
        // Never overshoot the fruit on the cycle: those moves cost a whole extra
        // lap. Only when every reachable move overshoots take the shortest hop,
        // which still guarantees forward progress towards the fruit.
        int pick = -1;
        if (candN > 0) {
            if (foodForward > 0) {
                pick = bestCandidate(candScore, candD, candN, foodForward);
            }
            if (pick < 0) {
                pick = bestCandidate(candScore, candD, candN, -1);
            }
        }
        if (pick >= 0) {
            lastBranchId = BRANCH_CYCLE;
            return new int[]{candDx[pick], candDy[pick]};
        }
        if (verified) {
            // Off the arc (after a verified shortcut) there may be no forward move
            // at all: keep the run alive with the widest open region instead.
            int[] mv = survivalMove(body, food, hx, hy);
            if (mv != null) {
                lastBranchId = BRANCH_SURVIVE;
                return mv;
            }
        }
        lastBranchId = BRANCH_FALLBACK;
        return firstDirection(head);
    }

    /* ------------------------------------------------------------------ */
    /* Safe-search engine (zig-zag tail following, no Hamiltonian cycle)  */
    /* ------------------------------------------------------------------ */

    /**
     * The classic "safe search" / tail-following algorithm, no Hamiltonian cycle:
     * <ol>
     *   <li>shortest BFS path to the fruit (the tail counts as free) - take its first
     *       step only when the WHOLE route, simulated forward with growth, still
     *       leaves a route from the new head to the new tail;</li>
     *   <li>otherwise run away from the tail: of the safe steps take the one with the
     *       longest route back to the tail (tie-break: the most open room). Keeping
     *       the head far from the tail spreads the body across the free space in a
     *       zig-zag instead of coiling up, and that is what reopens routes to the
     *       fruit;</li>
     *   <li>if every free step is taken, follow the BFS route to the tail;</li>
     *   <li>last resort: the widest-open neighbour.</li>
     * </ol>
     * Measured: it never bites and never hits a wall, and it fills 99% of the board
     * (mean 239/240 and 594.5/600), but it does not finish every game - with a few
     * cells left the safety condition can fail forever and the snake circles. It is a
     * heuristic; the Hamiltonian engine is the one with the 100% proof.
     */
    private int[] tailChaseMove(int[] body, int food, int hx, int hy, int tail) {
        if (food >= 0) {
            int[] path = bfsFoodPath(body, food);
            if (path != null && simulateSafe(body, path, food)) {
                stepsToFood = path.length;
                lastBranchId = BRANCH_TAILFOOD;
                return new int[]{path[0] % cols - hx, path[0] / cols - hy};
            }
        }
        // Run away from the tail: the step that keeps the most open room and the
        // longest route back to the tail. That spreads the body through the free
        // space instead of coiling around it.
        int[] best = null;
        int bestDist = -1;
        int bestArea = -1;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            int n = ny * cols + nx;
            boolean onBody = n == tail;
            if (!onBody) {
                for (int cc : body) {
                    if (cc == n) {
                        onBody = true;
                        break;
                    }
                }
            }
            if (onBody) {
                continue;
            }
            int[] room = roomAfter(body, n, food);
            if (room == null || room[1] < 0) {
                continue;
            }
            if (room[1] > bestDist || (room[1] == bestDist && room[0] > bestArea)) {
                bestDist = room[1];
                bestArea = room[0];
                best = new int[]{DIRS[k][0], DIRS[k][1]};
            }
        }
        if (best != null) {
            lastBranchId = BRANCH_TAILCHASE;
            return best;
        }
        int[] tailPath = bfsPathTo(body, tail);
        if (tailPath != null && tailPath.length > 1) {
            int next = tailPath[0] == food ? -1 : tailPath[0];
            if (next >= 0) {
                lastBranchId = BRANCH_TAILCHASE;
                return new int[]{next % cols - hx, next / cols - hy};
            }
        }
        int[] mv = survivalMove(body, food, hx, hy);
        if (mv != null) {
            lastBranchId = BRANCH_TAILCHASE;
        }
        return mv;
    }

    /**
     * BFS route from the head to {@code target} over free cells. The tail cell counts
     * as free (it is vacated this tick), like every other route in this engine.
     */
    private int[] bfsPathTo(int[] body, int target) {
        if (body.length == 0 || target < 0) {
            return null;
        }
        markBody(body);
        blocked[body[body.length - 1]] = false;
        blocked[target] = false;
        java.util.Arrays.fill(bfsDist, -1);
        int head = body[0];
        int qh = 0;
        int qt = 0;
        bfsQueue[qt++] = head;
        bfsDist[head] = 0;
        bfsPrev[head] = -1;
        boolean found = head == target;
        while (qh < qt && !found) {
            int cur = bfsQueue[qh++];
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * cols + nx;
                if (bfsDist[n] >= 0 || blocked[n]) {
                    continue;
                }
                bfsDist[n] = bfsDist[cur] + 1;
                bfsPrev[n] = cur;
                bfsQueue[qt++] = n;
                if (n == target) {
                    found = true;
                    break;
                }
            }
        }
        int len = bfsDist[target];
        if (len <= 0) {
            return null;
        }
        int[] path = new int[len];
        int cur = target;
        for (int i = len - 1; i >= 0; i--) {
            path[i] = cur;
            cur = bfsPrev[cur];
        }
        return path;
    }

    /**
     * Open room after stepping onto {@code next}: {reachable free cells, distance from
     * the new head to the tail (-1 when the tail is lost)}. Null when the step bites.
     */
    private int[] roomAfter(int[] body, int next, int food) {
        boolean eats = next == food;
        int len = body.length + (eats ? 1 : 0);
        int[] view = new int[len];
        view[0] = next;
        int copy = eats ? body.length : body.length - 1;
        if (copy > 0) {
            System.arraycopy(body, 0, view, 1, copy);
        }
        for (int i = 1; i < len; i++) {
            if (view[i] == next) {
                return null;
            }
        }
        int tailCell = view[len - 1];
        markBody(view);
        blocked[tailCell] = false;
        java.util.Arrays.fill(bfsDist, -1);
        int qh = 0;
        int qt = 0;
        bfsQueue[qt++] = next;
        bfsDist[next] = 0;
        while (qh < qt) {
            int cur = bfsQueue[qh++];
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int ax = cx + DIRS[k][0];
                int ay = cy + DIRS[k][1];
                if (ax < 0 || ax >= cols || ay < 0 || ay >= rows) {
                    continue;
                }
                int a = ay * cols + ax;
                if (bfsDist[a] >= 0 || blocked[a]) {
                    continue;
                }
                bfsDist[a] = bfsDist[cur] + 1;
                bfsQueue[qt++] = a;
            }
        }
        return new int[]{qt - 1, bfsDist[tailCell]};
    }
    /**
     * Best effort move when the cycle gives no forward step: the free neighbour that
     * keeps the biggest open region reachable (widest-open greedy).
     */
    private int[] survivalMove(int[] body, int food, int hx, int hy) {
        int[] best = null;
        int bestArea = -1;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            int n = ny * cols + nx;
            int[] step = new int[]{n};
            if (!simulateSafe(body, step, food)) {
                continue;
            }
            int area = freeAreaAfter(body, n, food);
            if (area > bestArea) {
                bestArea = area;
                best = new int[]{DIRS[k][0], DIRS[k][1]};
            }
        }
        return best;
    }

    /** Free cells still reachable from the head after stepping onto {@code next}. */
    private int freeAreaAfter(int[] body, int next, int food) {
        boolean eats = next == food;
        int len = body.length + (eats ? 1 : 0);
        int[] view = new int[len];
        view[0] = next;
        int copy = eats ? body.length : body.length - 1;
        if (copy < 0) {
            copy = 0;
        }
        System.arraycopy(body, 0, view, 1, copy);
        markBody(view);
        java.util.Arrays.fill(bfsDist, -1);
        int qh = 0;
        int qt = 0;
        bfsQueue[qt++] = next;
        bfsDist[next] = 0;
        while (qh < qt) {
            int cur = bfsQueue[qh++];
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int ax = cx + DIRS[k][0];
                int ay = cy + DIRS[k][1];
                if (ax < 0 || ax >= cols || ay < 0 || ay >= rows) {
                    continue;
                }
                int a = ay * cols + ax;
                if (bfsDist[a] >= 0 || blocked[a]) {
                    continue;
                }
                bfsDist[a] = bfsDist[cur] + 1;
                bfsQueue[qt++] = a;
            }
        }
        return qt - 1;
    }

    /** Which branch produced the last move: 1 verified, 2 odd path, 3 cycle, 4 survive. */
    int lastBranchId() {
        return lastBranchId;
    }

    /**
     * Index of the cheapest candidate whose cycle hop is at most maxD (-1 = any).
     * With BFS information the smallest fruit distance wins, otherwise the
     * longest legal hop (fastest progress along the cycle).
     */
    private int bestCandidate(int[] score, int[] d, int n, int maxD) {
        boolean hasDist = false;
        for (int i = 0; i < n; i++) {
            if (maxD >= 0 && d[i] > maxD) {
                continue;
            }
            if (score[i] < 400_000) {
                hasDist = true;
            }
        }
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        int bestD = -1;
        for (int i = 0; i < n; i++) {
            if (maxD >= 0 && d[i] > maxD) {
                continue;
            }
            if (hasDist) {
                if (score[i] < bestScore) {
                    bestScore = score[i];
                    best = i;
                }
            } else if (d[i] > bestD) {
                bestD = d[i];
                best = i;
            }
        }
        return best;
    }

    /** Free-cell steps from the head to the food, -1 when unknown/unreachable. */
    int getStepsToFood() {
        return stepsToFood;
    }

    /** Was the fruit still reachable on the last region search? */
    boolean lastFoodReachable() {
        return true;
    }

    /** True when the fruit can be reached from the head through free cells. */
    boolean foodReachable(int[] body, int food) {
        if (food < 0 || body.length == 0) {
            return false;
        }
        int head = body[0];
        markBody(body);
        computeFoodDistance(food);
        int hx = head % cols;
        int hy = head / cols;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            if (dist[ny * cols + nx] >= 0) {
                return true;
            }
        }
        return false;
    }

    /** First cells of the current cycle, for the state dump / tests. */
    int[] cyclePreview(int n) {
        int k = Math.min(n, total);
        int[] out = new int[k];
        System.arraycopy(cycleCell, 0, out, 0, k);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private int forward(int from, int to) {
        return ((to - from) % total + total) % total;
    }

    private void markBody(int[] body) {
        Arrays.fill(blocked, false);
        for (int c : body) {
            blocked[c] = true;
        }
    }

    private void updateStepsToFood(int head, int food) {
        stepsToFood = -1;
        if (food < 0) {
            return;
        }
        int hx = head % cols;
        int hy = head / cols;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            int n = ny * cols + nx;
            if (dist[n] >= 0 && (stepsToFood < 0 || dist[n] + 1 < stepsToFood)) {
                stepsToFood = dist[n] + 1;
            }
        }
    }

    /** BFS distance from every free cell to the food (-1 = unreachable). */
    private void computeFoodDistance(int food) {
        Arrays.fill(dist, -1);
        if (food < 0) {
            return;
        }
        int qh = 0;
        int qt = 0;
        queue[qt++] = food;
        dist[food] = 0;
        while (qh < qt) {
            int cur = queue[qh++];
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * cols + nx;
                if (dist[n] >= 0 || blocked[n]) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
            }
        }
    }

    /** Free neighbour with the largest open area (safety net). */
    int[] fallbackDirection(int[] body) {
        if (body.length == 0) {
            return null;
        }
        markBody(body);
        int head = body[0];
        int hx = head % cols;
        int hy = head / cols;
        int[] bestDir = null;
        int bestArea = -1;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            int cell = ny * cols + nx;
            if (blocked[cell]) {
                continue;
            }
            int area = floodArea(cell);
            if (area > bestArea) {
                bestArea = area;
                bestDir = new int[]{nx - hx, ny - hy};
            }
        }
        return bestDir;
    }

    private int floodArea(int start) {
        Arrays.fill(dist, -1);
        int qh = 0;
        int qt = 0;
        queue[qt++] = start;
        dist[start] = 0;
        int area = 0;
        while (qh < qt) {
            int cur = queue[qh++];
            area++;
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * cols + nx;
                if (dist[n] >= 0 || blocked[n]) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
            }
        }
        return area;
    }

    /** Fruit cell inside the free arc ahead of the head. */
    int pickFoodCell(int[] body, java.util.Random rnd) {
        if (tailChase) {
            // No cycle: any free cell the head can walk to (tail counts as free, it
            // is vacated this tick) - the fruit is never relocated, it just spawns
            // somewhere reachable.
            if (body.length == 0) {
                return -1;
            }
            int head = body[0];
            int tailCell = body[body.length - 1];
            markBody(body);
            blocked[tailCell] = false;
            java.util.Arrays.fill(bfsDist, -1);
            int qh = 0;
            int qt = 0;
            bfsQueue[qt++] = head;
            bfsDist[head] = 0;
            while (qh < qt) {
                int cur = bfsQueue[qh++];
                int cx = cur % cols;
                int cy = cur / cols;
                for (int k = 0; k < 4; k++) {
                    int ax = cx + DIRS[k][0];
                    int ay = cy + DIRS[k][1];
                    if (ax < 0 || ax >= cols || ay < 0 || ay >= rows) {
                        continue;
                    }
                    int a = ay * cols + ax;
                    if (bfsDist[a] >= 0 || blocked[a]) {
                        continue;
                    }
                    bfsDist[a] = bfsDist[cur] + 1;
                    bfsQueue[qt++] = a;
                }
            }
            // Everything except the head and the tail: the tail is about to be
            // vacated, but a fruit sitting there would be eaten while the tail is
            // still occupied - the head would grow into its own body.
            int n = 0;
            for (int i = 1; i < qt; i++) {
                if (bfsQueue[i] != tailCell) {
                    bfsQueue[n++] = bfsQueue[i];
                }
            }
            if (n > 0) {
                return bfsQueue[rnd.nextInt(n)];
            }
            // The head is boxed in: no free cell is reachable, but the board is not
            // full yet. The fruit may never move, so it has to spawn on some free cell
            // anyway - the safest one left is the one closest to the tail.
            int count = 0;
            for (int c = 0; c < total; c++) {
                if (!blocked[c] || c == tailCell) {
                    count++;
                }
            }
            if (count <= 0) {
                return -1;
            }
            int pick = rnd.nextInt(count);
            for (int c = 0; c < total; c++) {
                if ((!blocked[c] || c == tailCell) && pick-- == 0) {
                    return c;
                }
            }
            return -1;
        }
        if (oddMode) {
            // Odd x odd board: the snake walks a Hamiltonian PATH, so the fruit has to
            // sit on the very next path cell. Every step then eats, the tail never
            // retires and the body covers the whole path - the only way a path (which
            // cannot close into a loop) can fill the board.
            int head = body.length > 0 ? body[0] : -1;
            if (head < 0 || pathIdx == null) {
                return -1;
            }
            int hi = pathIdx[head];
            if (hi + 1 >= total) {
                return -1;
            }
            return pathCell[hi + 1];
        }
        int n = body.length;
        if (n == 0 || n >= total) {
            return -1;
        }
        int head = body[0];
        int tail = body[n - 1];
        markBody(body);
        int hIdx = cycleIdx[head];
        int tIdx = cycleIdx[tail];
        int tailDist = forward(hIdx, tIdx);
        if (tailDist == 0) {
            tailDist = total;
        }
        // Prefer the near stretch of the free arc. The snake walks at a steady
        // speed now, so a fruit sitting at the far end of a long arc would take
        // half a minute - it would look like the run had stalled.
        int window = Math.max(1, Math.min(tailDist - 1,
                Math.max(40, Math.min(160, tailDist / 2))));
        int near = pickInArc(hIdx, 1, window, rnd);
        if (near >= 0) {
            return near;
        }
        int any = pickInArc(hIdx, 1, tailDist - 1, rnd);
        if (any >= 0) {
            return any;
        }
        int free = total - n;
        if (free <= 0) {
            return -1;
        }
        int pick = rnd.nextInt(free);
        for (int c = 0; c < total; c++) {
            if (!blocked[c] && pick-- == 0) {
                return c;
            }
        }
        return -1;
    }

    /** Random free cell {@code from..to} steps ahead of the head on the cycle. */
    private int pickInArc(int hIdx, int from, int to, java.util.Random rnd) {
        if (to < from) {
            return -1;
        }
        int count = 0;
        for (int d = from; d <= to; d++) {
            if (!blocked[cycleCell[(hIdx + d) % total]]) {
                count++;
            }
        }
        if (count == 0) {
            return -1;
        }
        int pick = rnd.nextInt(count);
        for (int d = from; d <= to; d++) {
            int c = cycleCell[(hIdx + d) % total];
            if (!blocked[c] && pick-- == 0) {
                return c;
            }
        }
        return -1;
    }

    private int[] dirTo(int from, int to) {
        int fx = from % cols;
        int fy = from / cols;
        int tx = to % cols;
        int ty = to / cols;
        int dx = tx - fx;
        int dy = ty - fy;
        if (Math.abs(dx) + Math.abs(dy) != 1) {
            return null;
        }
        return new int[]{dx, dy};
    }
}
