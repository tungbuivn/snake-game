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
    private static final boolean TWO_STRIPS = false;

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
    private final int[] cycleCell;
    private final int[] cycleIdx;
    private final int[] dist;
    private final boolean[] blocked;
    private final int[] queue;
    private int stepsToFood = -1;

    AutoPilot(int cols, int rows) {
        this(cols, rows, RANDOM_CYCLE_DEFAULT);
    }

    AutoPilot(int cols, int rows, boolean randomCycle) {
        this.cols = cols;
        this.rows = rows;
        this.total = cols * rows;
        this.randomizeCycle = randomCycle;
        cycleCell = new int[total];
        cycleIdx = new int[total];
        dist = new int[total];
        blocked = new boolean[total];
        queue = new int[total];
        buildCycle();
    }

    /* ------------------------------------------------------------------ */
    /* Cycle construction                                                 */
    /* ------------------------------------------------------------------ */

    private void buildCycle() {
        int[] next = new int[total];
        boolean ok = randomizeCycle && randomCycle(next) && cycleValid(next);
        if (!ok) {
            if (TWO_STRIPS && rows >= 6) {
                int mid = rows / 2;
                linkStrip(next, 0, mid - 1);
                linkStrip(next, mid, rows - 1);
                if (!mergeStrips(next, mid - 1, mid)) {
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
        int start = cycleIdx[(rows / 2) * cols + cols / 2];
        int[] out = new int[length];
        for (int i = 0; i < length; i++) {
            int k = ((start - i) % total + total) % total;
            out[i] = cycleCell[k];
        }
        return out;
    }

    int[] firstDirection(int headCell) {
        int next = cycleCell[(cycleIdx[headCell] + 1) % total];
        return dirTo(headCell, next);
    }

    /** Free cells still ahead of the head on the cycle (diagnostics). */
    int arcSize(int[] body) {
        if (body.length == 0) {
            return 0;
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
        int head = body[0];
        int tail = body[body.length - 1];

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
            return new int[]{candDx[pick], candDy[pick]};
        }
        return firstDirection(head);
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
        int count = 0;
        for (int d = 1; d < tailDist; d++) {
            int c = cycleCell[(hIdx + d) % total];
            if (!blocked[c]) {
                count++;
            }
        }
        if (count > 0) {
            int pick = rnd.nextInt(count);
            for (int d = 1; d < tailDist; d++) {
                int c = cycleCell[(hIdx + d) % total];
                if (!blocked[c] && pick-- == 0) {
                    return c;
                }
            }
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
