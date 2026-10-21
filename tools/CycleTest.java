package com.dsh.snake;

import java.lang.reflect.Field;

/** Headless check of the AutoPilot cycles (run with: java -cp build/test com.dsh.snake.CycleTest). */
public class CycleTest {

    private static int[] cycleOf(int cols, int rows) throws Exception {
        AutoPilot pilot = new AutoPilot(cols, rows);
        Field f = AutoPilot.class.getDeclaredField("cycleCell");
        f.setAccessible(true);
        return (int[]) f.get(pilot);
    }

    private static int check(int cols, int rows) throws Exception {
        long t0 = System.nanoTime();
        int[] cycle = cycleOf(cols, rows);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        int total = cols * rows;
        if (cycle.length != total) {
            return -1;
        }
        boolean[] seen = new boolean[total];
        for (int i = 0; i < total; i++) {
            int c = cycle[i];
            int n = cycle[(i + 1) % total];
            if (seen[c]) {
                System.out.println("    duplicate cell " + c);
                return -1;
            }
            seen[c] = true;
            int d = Math.abs(c % cols - n % cols) + Math.abs(c / cols - n / cols);
            if (d != 1) {
                System.out.println("    non adjacent " + c + "->" + n);
                return -1;
            }
        }
        return (int) ms;
    }

    public static void main(String[] args) throws Exception {
        int[][] sizes = {{20, 40}, {20, 41}, {20, 12}, {20, 46}, {20, 13}, {20, 12}};
        for (int[] size : sizes) {
            int ms = check(size[0], size[1]);
            System.out.println("board " + size[0] + "x" + size[1] + " -> "
                    + (ms < 0 ? "INVALID" : "valid Hamiltonian cycle, " + ms + " ms"));
        }
        int[] a = cycleOf(20, 40);
        int[] b = cycleOf(20, 40);
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                diff++;
            }
        }
        System.out.println("two fresh pilots differ in " + diff + "/" + a.length + " cells");

        // Does the randomized builder itself succeed, or do we silently fall back?
        AutoPilot pilot = new AutoPilot(20, 40);
        int[] next = new int[800];
        java.lang.reflect.Method gen = AutoPilot.class.getDeclaredMethod("randomCycle", int[].class);
        gen.setAccessible(true);
        for (int i = 0; i < 3; i++) {
            java.util.Arrays.fill(next, 0);
            boolean built = (Boolean) gen.invoke(pilot, (Object) next);
            java.lang.reflect.Method val = AutoPilot.class.getDeclaredMethod("cycleValid", int[].class);
            val.setAccessible(true);
            boolean valid = built && (Boolean) val.invoke(pilot, (Object) next);
            System.out.println("randomCycle #" + i + " built=" + built + " valid=" + valid
                    + " next[0]=" + next[0] + " next[1]=" + next[1]);
        }
    }
}
