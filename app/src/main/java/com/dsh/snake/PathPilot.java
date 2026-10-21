package com.dsh.snake;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * "SHORT PATH" mode, modelled on github.com/ngocbh/snake (BFS/A* + Greedy):
 *
 *  1. Shortest path (BFS, Manhattan-consistent so it equals A* on a grid) to the
 *     fruit.
 *  2. Send a virtual snake along it; if the virtual head can still reach its own
 *     tail afterwards, follow the fruit path.
 *  3. Otherwise follow the tail, using the "longest path" trick: the shortest
 *     path is expanded with 2-cell zig-zags so the snake spirals instead of
 *     beelining into its tail.
 *  4. If the tail is unreachable, walk a long path towards the fruit.
 *  5. Otherwise wander (2-cell lookahead, then any free neighbour).
 *
 * There is no formal safety guarantee: if the snake still bites itself the game
 * cuts the body from the bite back to the tail (see GameView.biteSelf) and the
 * run continues - this mode is just for watching the snake eat.
 */
final class PathPilot {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final int cols;
    private final int rows;
    private final int total;
    private final boolean[] blocked;
    private final int[] dist;
    private final int[] parent;
    private final int[] queue;
    private int lastSteps = -1;

    PathPilot(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        this.total = cols * rows;
        blocked = new boolean[total];
        dist = new int[total];
        parent = new int[total];
        queue = new int[total];
    }

    int getStepsToFood() {
        return lastSteps;
    }

    int[] decide(int[] body, int food, int dirX, int dirY) {
        if (body.length == 0) {
            return null;
        }
        int head = body[0];
        int tail = body[body.length - 1];
        markBody(body);

        int[] toFood = bfs(head, food);
        lastSteps = toFood.length > 0 ? toFood.length : -1;

        // 1+2: shortest path, accepted if the virtual snake can still reach its tail.
        if (toFood.length > 0) {
            int[] virtualBody = simulate(body, toFood);
            markBody(virtualBody);
            boolean canReachTail = bfs(virtualBody[0], virtualBody[virtualBody.length - 1]).length > 0;
            if (canReachTail || body.length <= 2) {
                markBody(body);
                return dirTo(head, toFood[0]);
            }
        }
        markBody(body);

        // 3: follow the tail, along an expanded (longer, winding) path.
        int[] toTail = expand(body, head, bfs(head, tail));
        if (toTail.length > 0) {
            return dirTo(head, toTail[0]);
        }

        // 4: head towards the fruit along a long path.
        int[] longFood = expand(body, head, bfs(head, food));
        if (longFood.length > 0) {
            return dirTo(head, longFood[0]);
        }

        // 5: wander with a two-cell lookahead, then any free neighbour.
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

    /* ------------------------------------------------------------------ */
    /* Virtual snake                                                      */
    /* ------------------------------------------------------------------ */

    /** Body after following the path (length kept, tail moves along the path). */
    private int[] simulate(int[] body, int[] path) {
        int n = body.length;
        int[] nb = new int[n];
        System.arraycopy(body, 0, nb, 0, n);
        for (int cell : path) {
            for (int i = n - 1; i >= 1; i--) {
                nb[i] = nb[i - 1];
            }
            nb[0] = cell;
        }
        return nb;
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
            // Perpendicular neighbours of u and v form a 2-cell detour.
            int lx = -dir[1];
            int ly = dir[0];
            int ul = cell(u, lx, ly);
            int vl = cell(v, lx, ly);
            if (ul >= 0 && vl >= 0 && !used[ul] && !used[vl]) {
                used[ul] = true;
                used[vl] = true;
                out.add(i, ul);
                out.add(i + 1, vl);
                continue;   // process the inserted detour again
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
