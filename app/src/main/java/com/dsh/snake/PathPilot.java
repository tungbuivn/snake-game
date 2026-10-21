package com.dsh.snake;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * "SHORT PATH" mode: plan a route to the fruit, but only accept it when the
 * snake can still reach its own tail afterwards - "eat the fruit, keep a way to
 * follow the tail", so the run cannot get trapped.
 *
 *  1. BFS shortest path to the fruit.
 *  2. Simulate eating it (the virtual snake grows on the fruit) and check that
 *     the virtual tail is still reachable. If yes, take the first step.
 *  3. If that fails, retry with the expanded ("longest path") variant, which
 *     spirals instead of beelining into the body.
 *  4. No fruit plan survives: follow the tail along an expanded path - always
 *     safe, because the tail keeps moving away.
 *  5. Last resort: the free neighbour that keeps the tail reachable with the
 *     most open room; if even that fails, wander.
 */
final class PathPilot {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Longest fruit route that is accepted; it is validated step by step. */
    private static final int MAX_PLAN = 300;

    /** After this many ticks without a safe plan the checks are relaxed. */
    private static final int RELAX_AFTER = 120;

    /** After this many ticks the fruit route is taken no matter what. */
    private static final int FORCE_AFTER = 420;

    private final int cols;
    private final int rows;
    private final int total;
    private final boolean[] blocked;
    private final int[] dist;
    private final int[] parent;
    private final int[] queue;
    private boolean[] scratch;
    private int lastSteps = -1;
    private int stuckTicks;
    private String lastBranch = "";

    String lastBranch() {
        return lastBranch;
    }

    PathPilot(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        this.total = cols * rows;
        blocked = new boolean[total];
        dist = new int[total];
        parent = new int[total];
        queue = new int[total];
        scratch = new boolean[total];
    }

    int getStepsToFood() {
        return lastSteps;
    }

    /** Ticks spent without an accepted fruit plan (diagnostics). */
    int stuckTicks() {
        return stuckTicks;
    }

    int[] decide(int[] body, int food, int dirX, int dirY) {
        if (body.length == 0) {
            return null;
        }
        int head = body[0];
        int tail = body[body.length - 1];

        // 1. shortest route to the fruit
        markBody(body);
        int[] toFood = bfs(head, food);
        lastSteps = toFood.length > 0 ? toFood.length : -1;

        // 2. eat it only if EVERY step of the route keeps the tail reachable
        //    (checking just "after eating" is not enough: the route may pass
        //    through a pocket where the tail is already lost) and the state right
        //    after the fruit is safe as well.
        if (toFood.length > 0 && body.length > 2) {
            boolean ok = routeSafe(body, toFood, food, MAX_PLAN)
                    && tailReachable(simulateEat(body, toFood, food));
            if (ok) {
                stuckTicks = 0;
                lastBranch = "food-bfs";
                return dirTo(head, toFood[0]);
            }
        } else if (toFood.length > 0) {
            stuckTicks = 0;
            lastBranch = "food-tiny";
            return dirTo(head, toFood[0]);
        }

        // 2b. No plan survives the strict checks. If that keeps happening the
        //     snake would circle behind its tail for ever (infinite loop), so
        //     relax: accept the fruit route as soon as its FIRST step keeps the
        //     tail reachable, and after a longer stall accept it anyway so the
        //     run always makes progress.
        if (toFood.length > 0) {
            stuckTicks++;
            if (stuckTicks > RELAX_AFTER) {
                int[] step = moveVirtual(body, toFood[0], toFood[0] == food);
                if (tailReachable(step) || stuckTicks > FORCE_AFTER) {
                    stuckTicks = 0;
                    lastBranch = "food-relaxed";
                    markBody(body);
                    return dirTo(head, toFood[0]);
                }
            }
        } else {
            stuckTicks++;
        }

        // 3. same plan, but along the expanded (winding) route
        markBody(body);
        int[] longFood = expand(body, head, bfs(head, food));
        if (longFood.length > 0 && body.length > 2) {
            boolean ok = routeSafe(body, longFood, food, MAX_PLAN)
                    && tailReachable(simulateEat(body, longFood, food));
            if (ok) {
                stuckTicks = 0;
                lastBranch = "food-long";
                return dirTo(head, longFood[0]);
            }
        }

        // 4. follow the tail, always re-validated step by step: first the direct
        //    (monotone) route, then the expanded zig-zag route
        markBody(body);
        int[] direct = bfs(head, tail);
        if (direct.length > 0 && routeSafe(body, direct, -1, MAX_PLAN)) {
            lastBranch = "tail-direct";
            return dirTo(head, direct[0]);
        }
        int[] toTail = expand(body, head, direct);
        if (toTail.length > 0 && routeSafe(body, toTail, -1, MAX_PLAN)) {
            lastBranch = "tail-expanded";
            return dirTo(head, toTail[0]);
        }

        // 5. any neighbour that keeps the tail reachable, roomiest first. The
        // candidates are collected first: tailReachable()/floodArea() overwrite
        // the occupancy map, so reading it per candidate would pick body cells.
        markBody(body);
        int hx = head % cols;
        int hy = head / cols;
        int[] cand = new int[4];
        int[] candDelta = new int[4];
        int candN = 0;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (!inside(nx, ny)) {
                continue;
            }
            int n = ny * cols + nx;
            boolean isTail = n == tail;
            if (blocked[n] && !isTail) {
                continue;
            }
            cand[candN] = n;
            candDelta[candN] = k;
            candN++;
        }
        int[] bestDir = null;
        int bestArea = -1;
        for (int i = 0; i < candN; i++) {
            int n = cand[i];
            int[] next = moveVirtual(body, n, n == food);
            if (!tailReachable(next)) {
                continue;
            }
            int area = floodArea(next[0], next);
            if (area > bestArea) {
                bestArea = area;
                int k = candDelta[i];
                bestDir = new int[]{DIRS[k][0], DIRS[k][1]};
            }
        }
        if (bestDir == null) {
            // Lenient pass: the pocket is small because the board is nearly full,
            // but the tail is still reachable - better than wandering blindly.
            for (int i = 0; i < candN; i++) {
                int n = cand[i];
                int[] next = moveVirtual(body, n, n == food);
                if (tailReachable(next, false)) {
                    int k = candDelta[i];
                    bestDir = new int[]{DIRS[k][0], DIRS[k][1]};
                    break;
                }
            }
        }
        markBody(body);
        if (bestDir != null) {
            lastBranch = "neighbour-area";
            return bestDir;
        }
        lastBranch = "wander";
        return wander(head);
    }

    /* ------------------------------------------------------------------ */
    /* Virtual snake                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * True when walking the route never loses the tail (the classic "keep a way
     * to follow the tail" rule, checked at every single step, not just at the
     * end). Routes longer than {@link #MAX_PLAN} are rejected outright, so every
     * accepted plan is fully validated.
     */
    private boolean routeSafe(int[] body, int[] path, int food, int maxSteps) {
        if (path.length > maxSteps) {
            return false;
        }
        int[] cur = body;
        for (int i = 0; i < path.length; i++) {
            boolean eat = path[i] == food && i == path.length - 1;
            cur = moveVirtual(cur, path[i], eat);
            if (!tailReachable(cur)) {
                return false;
            }
        }
        return true;
    }

    /** Body after walking the whole path (grows by one on the fruit). */
    private int[] simulateEat(int[] body, int[] path, int food) {
        int n = body.length;
        int[] nb = new int[n + 1];
        System.arraycopy(body, 0, nb, 0, n);
        int len = n;
        for (int i = 0; i < path.length; i++) {
            int cell = path[i];
            boolean eat = cell == food && i == path.length - 1;
            if (eat) {
                for (int k = len - 1; k >= 0; k--) {
                    nb[k + 1] = nb[k];
                }
                len++;
            } else {
                for (int k = len - 1; k >= 1; k--) {
                    nb[k] = nb[k - 1];
                }
            }
            nb[0] = cell;
        }
        return Arrays.copyOf(nb, len);
    }

    /** One step of a virtual snake (the tail only frees up when not eating). */
    private int[] moveVirtual(int[] body, int cell, boolean eat) {
        int n = body.length;
        int len = eat ? n + 1 : n;
        int[] nb = new int[len];
        nb[0] = cell;
        int keep = Math.min(eat ? n : n - 1, len - 1);
        for (int i = 0; i < keep; i++) {
            nb[i + 1] = body[i];
        }
        return nb;
    }

    /**
     * True when the head can reach the tail through free cells <em>and</em> the
     * open pocket is at least as big as the body, so the snake cannot slowly
     * seal itself in.
     */
    private boolean tailReachable(int[] body) {
        return tailReachable(body, true);
    }

    private boolean tailReachable(int[] body, boolean requireArea) {
        if (body.length <= 2) {
            return true;
        }
        markBody(body);
        if (bfs(body[0], body[body.length - 1]).length == 0) {
            return false;
        }
        if (!requireArea) {
            return true;
        }
        return floodArea(body[0], body) >= body.length;
    }

    /* ------------------------------------------------------------------ */
    /* Longest path: shortest path + 2-cell zig-zag expansion              */
    /* ------------------------------------------------------------------ */

    private int[] expand(int[] body, int head, int[] base) {
        if (base.length == 0) {
            return base;
        }
        boolean[] used = new boolean[total];
        for (int c : body) {
            used[c] = true;
        }
        for (int c : base) {
            used[c] = true;
        }
        ArrayList<Integer> out = new ArrayList<>();
        for (int c : base) {
            out.add(c);
        }
        int i = 0;
        int guard = 0;
        int limit = 4 * total;
        while (i < out.size() && guard++ < limit) {
            int v = out.get(i);
            int u = i == 0 ? head : out.get(i - 1);
            int[] dir = dirTo(u, v);
            if (dir == null) {
                i++;
                continue;
            }
            int lx = -dir[1];
            int ly = dir[0];
            int ul = cell(u, lx, ly);
            int vl = cell(v, lx, ly);
            if (ul >= 0 && vl >= 0 && !used[ul] && !used[vl]) {
                used[ul] = true;
                used[vl] = true;
                out.add(i, ul);
                out.add(i + 1, vl);
                continue;
            }
            int rx = dir[1];
            int ry = -dir[0];
            int ur = cell(u, rx, ry);
            int vr = cell(v, rx, ry);
            if (ur >= 0 && vr >= 0 && !used[ur] && !used[vr]) {
                used[ur] = true;
                used[vr] = true;
                out.add(i, ur);
                out.add(i + 1, vr);
            }
            i++;
        }
        int[] res = new int[out.size()];
        for (int k = 0; k < res.length; k++) {
            res[k] = out.get(k);
        }
        return res;
    }

    /* ------------------------------------------------------------------ */
    /* Grid helpers                                                       */
    /* ------------------------------------------------------------------ */

    /** Free neighbour with the most open space (used to dodge a wall). */
    int[] fallbackDirection(int[] body) {
        if (body.length == 0) {
            return null;
        }
        markBody(body);
        return wander(body[0]);
    }

    /**
     * True when the head can walk to {@code food} through free cells. Used to keep
     * the fruit eatable: a fruit that the snake has sealed off is an endless loop.
     */
    boolean foodReachable(int[] body, int food) {
        if (body == null || body.length == 0 || food < 0) {
            return false;
        }
        markBody(body);
        return bfs(body[0], food).length > 0;
    }

    /**
     * Nearest free cell to the head (at least two steps away), or -1 when the head
     * is fully walled in. Used to bring a fruit the snake keeps circling into reach.
     */
    int nearestFreeCell(int[] body) {
        if (body == null || body.length == 0) {
            return -1;
        }
        markBody(body);
        int head = body[0];
        Arrays.fill(dist, -1);
        int qh = 0;
        int qt = 0;
        queue[qt++] = head;
        dist[head] = 0;
        int best = -1;
        while (qh < qt) {
            int cur = queue[qh++];
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (!inside(nx, ny)) {
                    continue;
                }
                int n = ny * cols + nx;
                if (dist[n] >= 0 || blocked[n]) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
                if (best < 0 && dist[n] >= 2) {
                    best = n;
                }
            }
        }
        return best;
    }

    /**
     * A free cell the head can really walk to. Used when a fruit ends up sealed
     * behind the snake's own body: without a path the run would circle for ever.
     * Farther cells are preferred (the fruit should not pop up under the nose),
     * and the choice inside that pool is random so it does not look scripted.
     */
    int reachableFreeCell(int[] body, java.util.Random rnd) {
        if (body == null || body.length == 0) {
            return -1;
        }
        markBody(body);
        int head = body[0];
        Arrays.fill(dist, -1);
        int qh = 0;
        int qt = 0;
        queue[qt++] = head;
        dist[head] = 0;
        int[] pool = new int[total];
        int poolN = 0;
        int farthest = -1;
        while (qh < qt) {
            int cur = queue[qh++];
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (!inside(nx, ny)) {
                    continue;
                }
                int n = ny * cols + nx;
                if (dist[n] >= 0 || blocked[n]) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
                farthest = n;
                if (dist[n] >= 3) {
                    pool[poolN++] = n;
                }
            }
        }
        if (poolN > 0) {
            return pool[rnd.nextInt(poolN)];
        }
        return farthest;
    }

    /** Two-cell lookahead first, then any free neighbour. */
    private int[] wander(int head) {
        int hx = head % cols;
        int hy = head / cols;
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            int fx = hx + 2 * DIRS[k][0];
            int fy = hy + 2 * DIRS[k][1];
            if (!inside(nx, ny) || !inside(fx, fy)) {
                continue;
            }
            if (!blocked[ny * cols + nx] && !blocked[fy * cols + fx]) {
                return new int[]{DIRS[k][0], DIRS[k][1]};
            }
        }
        for (int k = 0; k < 4; k++) {
            int nx = hx + DIRS[k][0];
            int ny = hy + DIRS[k][1];
            if (inside(nx, ny) && !blocked[ny * cols + nx]) {
                return new int[]{DIRS[k][0], DIRS[k][1]};
            }
        }
        return null;
    }

    /** Size of the open region reachable from {@code start} around a body. */
    private int floodArea(int start, int[] body) {
        markBody(body);
        markForFlood(body);
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
                if (!inside(nx, ny)) {
                    continue;
                }
                int n = ny * cols + nx;
                if (dist[n] >= 0 || scratch[n]) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
            }
        }
        return area;
    }

    private void markForFlood(int[] body) {
        Arrays.fill(scratch, false);
        for (int c : body) {
            scratch[c] = true;
        }
    }

    private boolean inside(int x, int y) {
        return x >= 0 && x < cols && y >= 0 && y < rows;
    }

    /** Cell at (pos + dir), or -1 when off the board. */
    private int cell(int pos, int dx, int dy) {
        int x = pos % cols + dx;
        int y = pos / cols + dy;
        if (!inside(x, y)) {
            return -1;
        }
        return y * cols + x;
    }

    private void markBody(int[] body) {
        Arrays.fill(blocked, false);
        for (int c : body) {
            blocked[c] = true;
        }
    }

    /** BFS through free cells, returns the cells after the start up to the goal. */
    private int[] bfs(int from, int to) {
        if (to < 0 || to == from) {
            return new int[0];
        }
        Arrays.fill(dist, -1);
        int qh = 0;
        int qt = 0;
        queue[qt++] = from;
        dist[from] = 0;
        parent[from] = -1;
        while (qh < qt) {
            int cur = queue[qh++];
            if (cur == to) {
                break;
            }
            int cx = cur % cols;
            int cy = cur / cols;
            for (int k = 0; k < 4; k++) {
                int nx = cx + DIRS[k][0];
                int ny = cy + DIRS[k][1];
                if (!inside(nx, ny)) {
                    continue;
                }
                int n = ny * cols + nx;
                if (dist[n] >= 0 || (blocked[n] && n != to)) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                parent[n] = cur;
                queue[qt++] = n;
            }
        }
        if (dist[to] < 0) {
            return new int[0];
        }
        int len = dist[to];
        int[] path = new int[len];
        int cur = to;
        for (int i = len - 1; i >= 0; i--) {
            path[i] = cur;
            cur = parent[cur];
        }
        return path;
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
