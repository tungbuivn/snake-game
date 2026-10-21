package com.dsh.snake;

import java.util.Arrays;

/**
 * "SHORT PATH" mode: every tick the planner clones the snake and the board and
 * looks exactly one step ahead.
 *
 *  1. For each of the three legal first steps (turning back into the neck is not
 *     a move) run a BFS from that stepped cell to the fruit on the cloned board.
 *     One valid route per direction is enough - the shortest route wins.
 *  2. Walk the virtual snake that step (it grows when the step lands on the
 *     fruit) and measure the gap between its tail and its head: the free-cell BFS
 *     distance, with the head counted as reachable. When the gap is at least two
 *     the snake still has a free cell to spare after eating, so it can keep
 *     chasing its tail - that step is committed. The plan is recomputed on the
 *     next tick, so only one move is ever committed.
 *  3. No direction survives: chase the tail. Every legal step is scored by the
 *     gap it leaves and the step that keeps the closest to a one-cell gap wins,
 *     so a fruit eaten on the way grows the body by one cell and the tail is
 *     still follow-able.
 *  4. Last resort: the free neighbour two cells ahead, then the cheapest self
 *     bite (the body cell closest to the tail, so the cut is as small as
 *     possible), then any on-board direction.
 *
 * After a long stall the plain fruit route is taken even when the gap check
 * fails, so the run can never circle behind its tail for ever.
 */
final class PathPilot {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Ticks without a committed fruit step before the route is forced. */
    private static final int FORCE_AFTER = 240;

    /**
     * Ticks without a route to the fruit before the planner bites the body open to
     * escape a sealed pocket (the game only cuts the body, it does not end the run).
     */
    private static final int BREAKOUT_AFTER = 45;

    private final int cols;
    private final int rows;
    private final int total;
    private final boolean[] blocked;
    private final int[] dist;
    private final int[] parent;
    private final int[] queue;
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
    }

    int getStepsToFood() {
        return lastSteps;
    }

    /** Ticks spent without an accepted fruit step (diagnostics). */
    int stuckTicks() {
        return stuckTicks;
    }

    int[] decide(int[] body, int food, int dirX, int dirY) {
        if (body.length == 0) {
            return null;
        }
        int head = body[0];
        boolean haveFood = food >= 0;
        // Eating a fruit that sits on the tail cell leaves the head and the tail on
        // the same cell for one tick. Treat the duplicated tail as already gone:
        // that is exactly the body the next tick will move with.
        if (body.length > 2 && body[0] == body[body.length - 1]) {
            body = Arrays.copyOf(body, body.length - 1);
        }
        // A short snake has no room for a one-cell gap: for it the tail merely
        // being reachable is enough.
        int minGap = body.length > 5 ? 2 : 1;
        // Room the fruit pocket must keep. The fruit is never moved, so a step that
        // squeezes it into a tiny pocket seals the run - even when the pocket is
        // technically still "reachable" for one tick.
        int need = Math.max(3, (total - body.length) / 6);

        int hx = head % cols;
        int hy = head / cols;
        int tailCell = body[body.length - 1];

        int[] bestDir = null;
        int bestLen = Integer.MAX_VALUE;
        boolean bestForward = false;
        // Same route, but the gap check failed: kept only as the escape hatch for
        // a run that would otherwise circle behind its tail for ever.
        int[] anyDir = null;
        int anyLen = Integer.MAX_VALUE;
        boolean anyForward = false;
        int minSteps = -1;

        for (int k = 0; haveFood && k < 4; k++) {
            int dx = DIRS[k][0];
            int dy = DIRS[k][1];
            int nx = hx + dx;
            int ny = hy + dy;
            if (!inside(nx, ny)) {
                continue;
            }
            int next = ny * cols + nx;
            boolean eats = next == food;
            if (turnsIntoNeck(dx, dy, dirX, dirY, tailCell, next)) {
                continue;   // the neck is behind the head: not a legal turn
            }
            if (!canEnter(body, next, eats)) {
                continue;   // the body is in the way
            }

            // Virtual snake after this one step, on its own board. The tail cell is
            // treated as free: it vacates on the same tick, and forgetting that made
            // a corridor through the tail look sealed (the classic false "no route").
            int[] virt = moveVirtual(body, next, eats);
            int left;
            if (eats) {
                left = 0;                 // the fruit is eaten by this very step
            } else {
                markBody(virt);
                blocked[virt[virt.length - 1]] = false;
                left = bfs(virt[0], food).length;
                if (left == 0) {
                    continue;             // no route to the fruit this way
                }
            }
            int steps = left + 1;
            if (minSteps < 0 || steps < minSteps) {
                minSteps = steps;
            }
            boolean forward = dx == dirX && dy == dirY;

            if (prefer(left, forward, anyLen, anyForward)) {
                anyLen = left;
                anyForward = forward;
                anyDir = new int[]{dx, dy};
            }
            // The virtual snake must still leave a gap to its own tail (so the extra
            // cell from eating cannot break the tail chase), keep the fruit in a
            // pocket with room left, and keep a free neighbour for the next tick -
            // stepping into a one-cell dead end is exactly what forces the bite.
            boolean roomy = eats || freeArea(virt, food) >= need;
            if (tailGap(virt) >= minGap && roomy
                    && prefer(left, forward, bestLen, bestForward)) {
                bestLen = left;
                bestForward = forward;
                bestDir = new int[]{dx, dy};
            }
        }

        // Diagnostics / speed model: shortest fruit distance from the head.
        lastSteps = minSteps;

        if (bestDir != null) {
            stuckTicks = 0;
            lastBranch = "food";
            return bestDir;
        }

        // No step keeps the gap. After a long stall take the plain fruit route
        // anyway, so the run can never circle behind its tail for ever.
        stuckTicks++;
        if (anyDir != null && stuckTicks > FORCE_AFTER) {
            stuckTicks = 0;
            lastBranch = "food-forced";
            return anyDir;
        }

        // The fruit cannot be reached at all (it was never moved, so it may sit in a
        // sealed pocket): chasing the tail in a closed loop would freeze the score
        // for ever. After a while break the loop by stepping out through a free
        // neighbour. Biting is never an option any more: in this game a self bite is
        // a loss, so there is nothing to gain from choosing one.
        if (minSteps < 0 && stuckTicks > BREAKOUT_AFTER) {
            markBody(body);
            int[] escape = wander(head);
            if (escape != null) {
                stuckTicks = 0;
                lastBranch = "breakout-free";
                return escape;
            }
        }

        // 3. Chase the tail. Each legal step is scored, best first:
        //    - it keeps the fruit in a pocket with room left (the fruit is never
        //      moved, so squeezing it into a tiny pocket freezes the run),
        //    - it keeps the fruit reachable at all,
        //    - it leaves a safe gap to the tail (the closer to one free cell, the
        //      better: a fruit eaten on the way is absorbed by that spare cell).
        // A step that keeps the tail merely reachable is the last resort.
        int[] chaseDir = null;
        int chaseScore = -1;
        int chaseGap = Integer.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            int dx = DIRS[k][0];
            int dy = DIRS[k][1];
            int nx = hx + dx;
            int ny = hy + dy;
            if (!inside(nx, ny)) {
                continue;
            }
            int next = ny * cols + nx;
            boolean eats = next == food;
            if (turnsIntoNeck(dx, dy, dirX, dirY, tailCell, next)) {
                continue;
            }
            if (!canEnter(body, next, eats)) {
                continue;
            }
            int[] virt = moveVirtual(body, next, eats);
            int gap = tailGap(virt);
            if (gap < 1) {
                continue;
            }
            boolean open = eats || fruitOpen(virt, food);
            boolean roomy = eats || (open && freeArea(virt, food) >= need);
            int score = (eats ? 8 : 0) + (roomy ? 4 : 0) + (open ? 2 : 0)
                    + (gap >= minGap ? 1 : 0);
            if (score > chaseScore || (score == chaseScore && gap < chaseGap)) {
                chaseScore = score;
                chaseGap = gap;
                chaseDir = new int[]{dx, dy};
            }
        }
        if (chaseDir != null) {
            lastBranch = chaseGap >= minGap ? "tail-gap" : "tail-loose";
            return chaseDir;
        }

        // 4. Nothing clean left: the free neighbour two cells ahead, then wander.
        markBody(body);
        int[] free = wander(head);
        if (free != null) {
            lastBranch = "free-step";
            return free;
        }

        // 5. Every neighbour is body: bite the cell closest to the tail, so the cut
        // the game applies removes as few cells as possible (biting the neck would
        // cut the snake down to two cells, so the farthest body cell wins). The game
        // continues the run after the cut, so this is a "theoretical loss", not a
        // game over - the winning test treats it as a loss and requires zero bites.
        int[] biteDir = cheapestBite(body, hx, hy, dirX, dirY, tailCell);
        if (biteDir != null) {
            lastBranch = "bite-cheap";
            return biteDir;
        }
        lastBranch = "wander";
        return null;
    }

    /**
     * Adaptive lawnmower sweep (no precomputed path): go straight along the current
     * row, step down one row when blocked, sweep the next row the other way, repeat.
     * The body always trails behind the head, so the swept rows fill up and the board
     * ends up full - which is exactly a win. The fruit is never moved and only spawns
     * on cells the head can reach, so it always lies in the part still to sweep.
     *
     * The move is only accepted when the tail stays reachable, which keeps the snake
     * alive for the whole run.
     */

    /**
     * True when a move would back into the neck. Following the tail cell is legal
     * even when it lies behind the head (the tail vacates on the same tick) - and
     * treating that as a forbidden turn is exactly what made the planner bite
     * itself while a legal tail move was available.
     */
    private boolean turnsIntoNeck(int dx, int dy, int dirX, int dirY, int tailCell, int next) {
        if (dx != -dirX || dy != -dirY) {
            return false;
        }
        return next != tailCell;
    }

    /**
     * The cheapest self bite available from the head: the neighbouring body cell
     * closest to the tail, so the cut the game applies removes as few cells as
     * possible (biting the neck would leave two cells). Null when the tail cell is
     * the only body neighbour - that cell is a legal move, not a bite.
     */
    private int[] cheapestBite(int[] body, int hx, int hy, int dirX, int dirY, int tailCell) {
        int[] biteDir = null;
        int biteIdx = -1;
        for (int k = 0; k < 4; k++) {
            int dx = DIRS[k][0];
            int dy = DIRS[k][1];
            int nx = hx + dx;
            int ny = hy + dy;
            if (!inside(nx, ny)) {
                continue;
            }
            int next = ny * cols + nx;
            if (turnsIntoNeck(dx, dy, dirX, dirY, tailCell, next)) {
                continue;
            }
            int idx = indexOf(body, next);
            if (idx < 0 || idx >= body.length - 1) {
                continue;   // the tail cell is not a bite, it just vacates
            }
            if (idx > biteIdx) {
                biteIdx = idx;
                biteDir = new int[]{dx, dy};
            }
        }
        return biteDir;
    }

    /** Index of {@code cell} in the head-first body, -1 when it is not there. */
    private int indexOf(int[] body, int cell) {
        for (int i = 0; i < body.length; i++) {
            if (body[i] == cell) {
                return i;
            }
        }
        return -1;
    }

    /* ------------------------------------------------------------------ */
    /* Virtual snake / board clones                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Free-cell BFS distance from the tail to the head of {@code body} (the head
     * cell counts as reachable), or -1 when the head cannot reach the tail. The
     * value is the number of cells between tail and head, so 1 means the head sits
     * right behind the tail and 2 leaves a one-cell gap.
     */
    private int tailGap(int[] body) {
        if (body.length <= 2) {
            return 1;
        }
        int head = body[0];
        int tail = body[body.length - 1];
        markBody(body);
        blocked[head] = false;          // the head itself counts for measuring
        blocked[tail] = false;
        Arrays.fill(dist, -1);
        int qh = 0;
        int qt = 0;
        dist[tail] = 0;
        queue[qt++] = tail;
        while (qh < qt) {
            int cur = queue[qh++];
            if (cur == head) {
                return dist[cur];
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
                if (dist[n] >= 0 || blocked[n]) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
            }
        }
        return -1;
    }

    /**
     * True when the head may step into {@code cell}: it must not be part of the
     * body - except the tail, which moves away on the same tick unless the step
     * eats the fruit.
     */
    private boolean canEnter(int[] body, int cell, boolean eats) {
        int limit = eats ? body.length : body.length - 1;
        for (int i = 0; i < limit; i++) {
            if (body[i] == cell) {
                return false;
            }
        }
        return true;
    }

    /** True when a route of {@code len} beats the current best {@code bestLen}. */
    private static boolean prefer(int len, boolean forward, int bestLen, boolean bestForward) {
        if (bestLen == Integer.MAX_VALUE) {
            return true;
        }
        if (len != bestLen) {
            return len < bestLen;
        }
        return forward && !bestForward;
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
     * True when the head can walk to {@code food} through free cells. The fruit is
     * never moved any more, so this only feeds diagnostics and the tests.
     */
    boolean foodReachable(int[] body, int food) {
        if (body == null || body.length == 0 || food < 0) {
            return false;
        }
        return fruitOpen(body, food);
    }

    /**
     * True when the fruit can still be walked to from the head of {@code body}
     * <em>without</em> counting on the tail cell: that cell is free only for one
     * tick, so relying on it lets the snake think a corridor stays open while the
     * tail is already sliding away - which then seals the fruit for good.
     */
    boolean fruitOpen(int[] body, int food) {
        if (body == null || body.length == 0 || food < 0) {
            return true;
        }
        markBody(body);
        return bfs(body[0], food).length > 0;
    }

    /**
     * Size of the free region that contains {@code cell} on the board of
     * {@code body}: the body is a wall and the tail cell counts as free (it vacates
     * on the same tick). 0 when the cell itself is a body cell.
     */
    private int freeArea(int[] body, int cell) {
        if (cell < 0) {
            return 0;
        }
        markBody(body);
        blocked[body[body.length - 1]] = false;
        if (blocked[cell]) {
            return 0;
        }
        Arrays.fill(dist, -1);
        int qh = 0;
        int qt = 0;
        queue[qt++] = cell;
        dist[cell] = 0;
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
                if (dist[n] >= 0 || blocked[n]) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
            }
        }
        return area;
    }

    /**
     * {@code out} must have room for cols*rows entries.
     */
    int reachableFreeCells(int[] body, int[] out) {
        if (body == null || body.length == 0) {
            return 0;
        }
        markBody(body);
        blocked[body[body.length - 1]] = false;
        int head = body[0];
        Arrays.fill(dist, -1);
        int qh = 0;
        int qt = 0;
        queue[qt++] = head;
        dist[head] = 0;
        int n = 0;
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
                int c = ny * cols + nx;
                if (dist[c] >= 0 || blocked[c]) {
                    continue;
                }
                dist[c] = dist[cur] + 1;
                queue[qt++] = c;
                out[n++] = c;
            }
        }
        return n;
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

}
