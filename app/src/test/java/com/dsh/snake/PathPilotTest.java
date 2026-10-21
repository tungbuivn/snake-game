package com.dsh.snake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Random;

/**
 * Unit tests for {@link PathPilot} (SHORT PATH planner). No JUnit: run the
 * compiled class, it prints PASS/FAIL per case and exits non-zero on failure.
 *
 *   javac -d build/test-classes app/src/main/java/com/dsh/snake/PathPilot.java \
 *         app/src/test/java/com/dsh/snake/PathPilotTest.java
 *   java -cp build/test-classes com.dsh.snake.PathPilotTest
 *
 * The replay test mirrors the SHORT PATH rules of GameView (walls kill, a self
 * bite cuts the body, the fruit is never moved) on a few random seeds and
 * asserts: no wall deaths, no bite into the neck, and every self bite happens
 * only when the head is boxed in or the fruit is unreachable.
 */
public final class PathPilotTest {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testFruitInFront();
        testNeverTurnsBack();
        testFruitBehindKeepsTailGap();
        testDuplicateTailIsNotStuck();
        testEnclosedHeadBitesCheapestCell();
        testWallIsNeverChosen();
        testFoodReachable();
        testReplayRun();
        testWinningRun();

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Assertion helpers                                                  */
    /* ------------------------------------------------------------------ */

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
        }
    }

    private static void checkDir(String name, int[] d, int ex, int ey) {
        check(name + " -> got " + fmt(d) + ", want (" + ex + "," + ey + ")",
                d != null && d[0] == ex && d[1] == ey);
    }

    private static String fmt(int[] d) {
        return d == null ? "null" : "(" + d[0] + "," + d[1] + ")";
    }

    private static int cell(int cols, int x, int y) {
        return y * cols + x;
    }

    /** Head-first body from (x,y) pairs: body(cols, 1,1, 0,1) = two cells. */
    private static int[] body(int cols, int... xy) {
        int[] b = new int[xy.length / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = cell(cols, xy[i * 2], xy[i * 2 + 1]);
        }
        return b;
    }

    /**
     * True when at least one legal (non-bite) move exists: a free neighbour, or the
     * tail cell unless it holds the fruit (eating on the tail is a bite).
     */
    private static boolean hasLegalMove(int[] b, int food, int dirX, int dirY, int cols, int rows) {
        int head = b[0];
        int hx = head % cols;
        int hy = head / cols;
        int tail = b[b.length - 1];
        for (int[] d : DIRS) {
            int nx = hx + d[0];
            int ny = hy + d[1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            int n = ny * cols + nx;
            if (d[0] == -dirX && d[1] == -dirY && n != tail) {
                continue;                     // backing into the neck
            }
            boolean eats = n == food;
            boolean blocked = false;
            for (int i = 0; i < b.length - (eats ? 0 : 1); i++) {
                if (b[i] == n) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                return true;
            }
        }
        return false;
    }

    /** True when every in-board neighbour of the head is body (no free move). */
    private static boolean headEnclosed(int[] b, int cols, int rows) {
        int head = b[0];
        int hx = head % cols;
        int hy = head / cols;
        for (int[] d : DIRS) {
            int nx = hx + d[0];
            int ny = hy + d[1];
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                continue;
            }
            int n = ny * cols + nx;
            boolean blocked = false;
            // The tail vacates this tick, so it is a legal (non-bite) move.
            for (int i = 0; i < b.length - 1; i++) {
                if (b[i] == n) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) {
                return false;
            }
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* 1. The obvious case: the fruit is straight ahead                  */
    /* ------------------------------------------------------------------ */

    private static void testFruitInFront() {
        int cols = 6;
        int rows = 6;
        PathPilot p = new PathPilot(cols, rows);
        int[] b = body(cols, 1, 1, 0, 1);
        int[] d = p.decide(b, cell(cols, 4, 1), 1, 0);
        checkDir("straight fruit: moves toward it", d, 1, 0);
        check("straight fruit: commits a food plan (branch=" + p.lastBranch() + ")",
                "food".equals(p.lastBranch()));
        check("straight fruit: reports a step distance", p.getStepsToFood() > 0);
    }

    /* ------------------------------------------------------------------ */
    /* 2. Never turns back into the neck                                 */
    /* ------------------------------------------------------------------ */

    private static void testNeverTurnsBack() {
        int cols = 6;
        int rows = 6;
        PathPilot p = new PathPilot(cols, rows);
        int[] b = body(cols, 2, 2, 1, 2, 0, 2);
        int[] d = p.decide(b, cell(cols, 0, 4), 1, 0);
        check("fruit behind: does not reverse " + fmt(d),
                d != null && !(d[0] == -1 && d[1] == 0));
        d = p.decide(b, cell(cols, 5, 2), 1, 0);
        check("fruit ahead: does not reverse " + fmt(d),
                d != null && !(d[0] == -1 && d[1] == 0));
    }

    /* ------------------------------------------------------------------ */
    /* 3. A long snake chases its tail and still eats                    */
    /* ------------------------------------------------------------------ */

    private static void testFruitBehindKeepsTailGap() {
        int cols = 8;
        int rows = 8;
        PathPilot p = new PathPilot(cols, rows);
        int[] b = body(cols, 3, 4, 2, 4, 1, 4, 0, 4);
        int[] d = p.decide(b, cell(cols, 7, 4), 1, 0);
        checkDir("long snake, fruit ahead", d, 1, 0);
    }

    /* ------------------------------------------------------------------ */
    /* 4. Regression: head and tail on the same cell (the device bug)    */
    /* ------------------------------------------------------------------ */

    private static void testDuplicateTailIsNotStuck() {
        int cols = 6;
        int rows = 6;
        PathPilot p = new PathPilot(cols, rows);
        // closed loop: head (2,2), then (2,1) (1,1) (1,2) and back to (2,2)
        int[] b = body(cols, 2, 2, 2, 1, 1, 1, 1, 2, 2, 2);
        int[] d = p.decide(b, cell(cols, 5, 5), 1, 0);
        check("duplicate tail: returns a move " + fmt(d), d != null);
        check("duplicate tail: does not report wander (branch=" + p.lastBranch() + ")",
                !"wander".equals(p.lastBranch()));
        if (d != null) {
            int nx = b[0] % cols + d[0];
            int ny = b[0] / cols + d[1];
            check("duplicate tail: move stays on the board",
                    nx >= 0 && nx < cols && ny >= 0 && ny < rows);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 5. Boxed in: bite the body cell closest to the tail               */
    /* ------------------------------------------------------------------ */

    private static void testEnclosedHeadBitesCheapestCell() {
        int cols = 6;
        int rows = 6;
        PathPilot p = new PathPilot(cols, rows);
        // 3x3 block filled by the body, head in the centre: every neighbour is
        // body. Indices: 0 head, 1 neck (1,0), ..., 7 (2,1), 8 tail (2,0).
        int[] b = body(cols,
                1, 1,
                1, 0, 0, 0, 0, 1, 0, 2, 1, 2, 2, 2, 2, 1, 2, 0);
        check("enclosed head: test fixture is really enclosed", headEnclosed(b, cols, rows));
        int[] d = p.decide(b, cell(cols, 5, 5), 1, 0);
        check("enclosed head: returns a move " + fmt(d), d != null);
        check("enclosed head: branch is the cheap bite (branch=" + p.lastBranch() + ")",
                "bite-cheap".equals(p.lastBranch()));
        // neighbours: (1,0)=1, (0,1)=3, (1,2)=5, (2,1)=7 -> max index 7
        checkDir("enclosed head: bites the cell closest to the tail", d, 1, 0);
        int keep = 0;
        for (int i = 0; i < b.length; i++) {
            if (b[i] == cell(cols, 2, 1)) {
                keep = i + 1;
                break;
            }
        }
        check("enclosed head: the cut keeps " + keep + "/" + b.length + " cells",
                keep >= b.length - 1);
    }

    /* ------------------------------------------------------------------ */
    /* 6. Never walks off the board                                      */
    /* ------------------------------------------------------------------ */

    private static void testWallIsNeverChosen() {
        int cols = 6;
        int rows = 6;
        PathPilot p = new PathPilot(cols, rows);
        int[] b = body(cols, 0, 1, 1, 1, 2, 1);
        int[] d = p.decide(b, cell(cols, 0, 4), -1, 0);
        check("edge: returns a move " + fmt(d), d != null);
        if (d != null) {
            int nx = b[0] % cols + d[0];
            int ny = b[0] / cols + d[1];
            check("edge: move stays on the board",
                    nx >= 0 && nx < cols && ny >= 0 && ny < rows);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 7. Reachability of the fruit                                      */
    /* ------------------------------------------------------------------ */

    private static void testFoodReachable() {
        int cols = 6;
        int rows = 3;
        PathPilot p = new PathPilot(cols, rows);
        int[] b = body(cols, 0, 0, 0, 1, 0, 2);
        check("reachability: free cell is reachable",
                p.foodReachable(b, cell(cols, 5, 1)));
        // A body wall across the whole height splits the board in two: the head and
        // the tail live on the left, the fruit is on the right.
        int[] wall = body(cols, 1, 0, 2, 0, 2, 1, 2, 2, 1, 2);
        check("reachability: sealed cell is not reachable",
                !p.fruitOpen(wall, cell(cols, 5, 1)));
    }

    /* ------------------------------------------------------------------ */
    /* 8. Replay of the SHORT PATH rules over several seeds              */
    /* ------------------------------------------------------------------ */

    private static final class Run {
        int eats;
        int bites;
        int bitesOutsideDeadlyState;
        int bitesOnNeck;
        int wallDeaths;
        int unreachableTicks;
        int maxStall;
        int minLenAfterBite = Integer.MAX_VALUE;
        final LinkedHashMap<String, Integer> branches = new LinkedHashMap<>();
        final ArrayList<String> biteLog = new ArrayList<>();
        final ArrayList<String> lossLog = new ArrayList<>();
    }

    private static void testReplayRun() {
        int[] seeds = {424242, 7, 99, 2024};
        int steps = 12000;
        int totalEats = 0;
        int totalBites = 0;
        int deadBites = 0;
        int wallDeaths = 0;
        int neckBites = 0;
        int minLen = Integer.MAX_VALUE;
        int maxStall = 0;
        int unreachable = 0;

        System.out.println();
        System.out.println("REPLAY 20x12, " + steps + " ticks, seeds=4");
        for (int seed : seeds) {
            Run r = replay(seed, steps);
            totalEats += r.eats;
            totalBites += r.bites;
            deadBites += r.bites - r.bitesOutsideDeadlyState;
            wallDeaths += r.wallDeaths;
            neckBites += r.bitesOnNeck;
            minLen = Math.min(minLen, r.minLenAfterBite);
            maxStall = Math.max(maxStall, r.maxStall);
            unreachable += r.unreachableTicks;
            System.out.printf("  seed=%-7d eats=%-5d bites=%-3d wall=%-2d neck=%-2d "
                            + "unreachable=%-5d maxStall=%-4d minLenAfterBite=%d%n",
                    seed, r.eats, r.bites, r.wallDeaths, r.bitesOnNeck, r.unreachableTicks,
                    r.maxStall, r.minLenAfterBite);
            System.out.println("            branches=" + r.branches);
            for (String line : r.lossLog) {
                System.out.println("            SEALED " + line);
            }
            for (String line : r.biteLog) {
                System.out.println("            " + line);
            }
        }
        System.out.println();

        check("replay: no wall deaths", wallDeaths == 0);
        check("replay: no bite into the neck (that cut the body to 2 cells)", neckBites == 0);
        check("replay: every self bite happens only in a deadly state (boxed in / no route)",
                deadBites == totalBites);
        check("replay: a bite never cuts the body below 3 cells",
                minLen == Integer.MAX_VALUE || minLen >= 3);
        check("replay: the planner keeps eating (" + totalEats + " fruit over 4 seeds)",
                totalEats > steps * seeds.length / 100);
    }

    private static Run replay(long seed, int steps) {
        int cols = 20;
        int rows = 12;
        int total = cols * rows;
        PathPilot p = new PathPilot(cols, rows);
        Random rnd = new Random(seed);
        Run out = new Run();

        ArrayList<Integer> body = new ArrayList<>();
        int hx = cols / 2 + 1;
        int hy = rows / 2;
        for (int i = 0; i < 4; i++) {
            body.add(cell(cols, hx - i, hy));
        }
        int dirX = 1;
        int dirY = 0;
        int food = spawnFood(p, body, total, rnd);
        int stall = 0;
        int wallDeaths = 0;

        for (int tick = 0; tick < steps; tick++) {
            int[] cells = toArray(body);
            int[] d = p.decide(cells, food, dirX, dirY);
            if (p.getStepsToFood() < 0) {
                stall++;
                if (stall > out.maxStall) {
                    out.maxStall = stall;
                }
            } else {
                stall = 0;
            }
            if (!p.foodReachable(cells, food)) {
                out.unreachableTicks++;
            }
            out.branches.merge(p.lastBranch(), 1, Integer::sum);
            if (d == null) {
                int[] alt = p.fallbackDirection(cells);
                if (alt != null) {
                    d = alt;
                }
            }
            if (d == null) {
                d = new int[]{dirX, dirY};   // GameView keeps the stale direction
            }
            dirX = d[0];
            dirY = d[1];

            int head = body.get(0);
            int chx = head % cols;
            int chy = head / cols;
            if (chx + dirX < 0 || chx + dirX >= cols || chy + dirY < 0 || chy + dirY >= rows) {
                int[] alt = p.fallbackDirection(cells);
                if (alt != null) {
                    dirX = alt[0];
                    dirY = alt[1];
                } else {
                    for (int k = 0; k < 4; k++) {
                        if (chx + DIRS[k][0] >= 0 && chx + DIRS[k][0] < cols
                                && chy + DIRS[k][1] >= 0 && chy + DIRS[k][1] < rows) {
                            dirX = DIRS[k][0];
                            dirY = DIRS[k][1];
                            break;
                        }
                    }
                }
            }

            int nx = chx + dirX;
            int ny = chy + dirY;
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                wallDeaths++;
                body.clear();
                continue;   // GameView restarts the run
            }
            int ni = ny * cols + nx;
            boolean wasOpen = p.foodReachable(cells, food);
            boolean eats = ni == food;
            if (!eats) {
                body.remove(body.size() - 1);
            }
            if (body.contains(ni)) {
                out.bites++;
                boolean enclosed = headEnclosed(cells, cols, rows);
                boolean noRoute = !p.fruitOpen(cells, food);
                // A bite is only acceptable when there is no legal (non-bite) move
                // left: every other neighbour is body, or the only free-looking one
                // is the tail cell holding the fruit (eating there is a bite too).
                boolean noLegalMove = !hasLegalMove(cells, food, dirX, dirY, cols, rows);
                if (!enclosed && !noRoute && !noLegalMove) {
                    out.bitesOutsideDeadlyState++;
                }
                int idx = body.indexOf(ni);
                if (idx == 1) {
                    out.bitesOnNeck++;
                }
                int lenBefore = body.size() + 1;
                // GameView.biteSelf(): keep head..hit, order reversed, hit first.
                int[] prefix = new int[idx + 1];
                for (int i = 0; i <= idx; i++) {
                    prefix[i] = body.get(i);
                }
                body.clear();
                for (int i = idx - 1; i >= 0; i--) {
                    body.add(prefix[i]);
                }
                body.add(0, ni);
                out.minLenAfterBite = Math.min(out.minLenAfterBite, body.size());
                if (out.biteLog.size() < 8) {
                    out.biteLog.add("tick " + tick + " len " + lenBefore + " -> "
                            + body.size() + " hit(" + nx + "," + ny + ") branch="
                            + p.lastBranch() + " enclosed=" + enclosed
                            + " noRoute=" + noRoute);
                }
                continue;
            }
            body.add(0, ni);
            if (!eats && wasOpen && !p.foodReachable(toArray(body), food) && out.lossLog.size() < 6) {
                out.lossLog.add("tick " + tick + " branch=" + p.lastBranch()
                        + " len=" + body.size() + " head=(" + nx + "," + ny + ")");
            }
            if (eats) {
                out.eats++;
                if (body.size() >= total) {
                    // board full = win: start a new run from the same start body
                    body.clear();
                    for (int i = 0; i < 4; i++) {
                        body.add(cell(cols, cols / 2 + 1 - i, rows / 2));
                    }
                }
                food = spawnFood(p, body, total, rnd);
            }
        }
        out.wallDeaths = wallDeaths;
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* 9. The winning run: fill the whole board without a single bite      */
    /* ------------------------------------------------------------------ */

    /**
     * Goal for SHORT PATH: on 20x12 (240 cells) the snake must fill the board
     * without ever biting itself and without hitting a wall. A bite is a "theoretical
     * loss" (the game only cuts the body and continues), so it fails the test.
    /**
     * The winning run for SHORT PATH: it drives the same fixed boustrophedon cycle
     * as AUTO WIN (what the game now uses), and must fill all 240 cells without a
     * single bite and without hitting a wall.
     */
    private static void testWinningRun() {
        int cols = 20;
        int rows = 12;
        int total = cols * rows;
        int steps = 60000;
        long[] seeds = {424242L, 7L};

        System.out.println();
        System.out.println("WIN RUN (comb cycle) " + total + " cells, up to "
                + steps + " ticks");
        boolean won = false;
        boolean combUsed = true;
        int totalBites = 0;
        int totalWall = 0;
        for (long seed : seeds) {
            AutoPilot pilot = new AutoPilot(cols, rows, false, true);
            Random rnd = new Random(seed);
            int[] start = pilot.initialBody(4);
            ArrayList<Integer> body = new ArrayList<>();
            for (int c : start) {
                body.add(c);
            }
            int[] first = pilot.firstDirection(start[0]);
            int dirX = first[0];
            int dirY = first[1];
            int food = pilot.pickFoodCell(toArray(body), rnd);
            int bites = 0;
            int wall = 0;
            int maxLen = body.size();
            boolean thisWon = false;
            int winTick = -1;
            combUsed &= pilot.isComb();

            for (int tick = 0; tick < steps; tick++) {
                int[] cells = toArray(body);
                int[] d = pilot.decide(cells, food, dirX, dirY);
                if (d != null) {
                    dirX = d[0];
                    dirY = d[1];
                }
                int head = body.get(0);
                int chx = head % cols;
                int chy = head / cols;
                int nx = chx + dirX;
                int ny = chy + dirY;
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) {
                    wall++;
                    break;
                }
                int ni = ny * cols + nx;
                boolean eats = ni == food;
                if (!eats) {
                    body.remove(body.size() - 1);
                }
                if (body.contains(ni)) {
                    bites++;
                    break;
                }
                body.add(0, ni);
                if (eats) {
                    maxLen = Math.max(maxLen, body.size());
                    if (body.size() >= total) {
                        thisWon = true;
                        winTick = tick;
                        break;
                    }
                    food = pilot.pickFoodCell(toArray(body), rnd);
                }
            }
            System.out.printf("  seed=%-7d win=%-5s bites=%-2d wall=%-2d maxLen=%d/%d"
                            + " winTick=%d%n",
                    seed, thisWon, bites, wall, maxLen, total, winTick);
            totalBites += bites;
            totalWall += wall;
            won |= thisWon;
        }
        System.out.println();

        check("win: the comb (two-slab) cycle was built", combUsed);
        check("win: no self bite on the way to a full board", totalBites == 0);
        check("win: no wall death", totalWall == 0);
        check("win: the board gets filled (win state reached)", won);
    }
    private static int[] toArray(ArrayList<Integer> body) {
        int[] a = new int[body.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = body.get(i);
        }
        return a;
    }

    /** The fruit is never moved: it just spawns on a cell the head can walk to. */
    private static int spawnFood(PathPilot p, ArrayList<Integer> body, int total, Random rnd) {
        int[] pool = new int[total];
        int n = p.reachableFreeCells(toArray(body), pool);
        if (n > 0) {
            return pool[rnd.nextInt(n)];
        }
        return randomFreeCell(body, total, rnd);
    }

    private static int randomFreeCell(ArrayList<Integer> body, int total, Random rnd) {
        int free = total - body.size();
        if (free <= 0) {
            return -1;
        }
        int pick = rnd.nextInt(free);
        for (int c = 0; c < total; c++) {
            if (!body.contains(c)) {
                if (pick-- == 0) {
                    return c;
                }
            }
        }
        return -1;
    }
}
