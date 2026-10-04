package dev.gubenkov.mathlock;

import java.util.Random;

/** Один пример из таблицы умножения. */
public final class Problem {
    public final int a, b;
    public Problem(int a, int b) { this.a = a; this.b = b; }
    public int answer() { return a * b; }
    @Override public String toString() { return a + " × " + b + " = ?"; }

    public static Problem random(Random rnd, int minFactor, int maxFactor, Problem avoid) {
        if (minFactor < 1) minFactor = 1;
        if (maxFactor < minFactor) maxFactor = minFactor;
        for (int i = 0; i < 20; i++) {
            int a = minFactor + rnd.nextInt(maxFactor - minFactor + 1);
            int b = 2 + rnd.nextInt(8);
            Problem p = rnd.nextBoolean() ? new Problem(a, b) : new Problem(b, a);
            if (avoid == null || avoid.a != p.a || avoid.b != p.b) return p;
        }
        return new Problem(minFactor, 2);
    }
}
