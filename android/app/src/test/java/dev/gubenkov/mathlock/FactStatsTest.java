package dev.gubenkov.mathlock;

import static org.junit.Assert.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.Test;

public class FactStatsTest {
    @Test public void boxesMoveByAnswer() {
        FactStats f = new FactStats(null);
        f.record(7, 8, true, 2000, 0);
        f.record(8, 7, true, 2000, 0);   // тот же факт
        assertEquals(2, f.get(7, 8).box);
        f.record(7, 8, true, 9000, 0);   // долго — коробка не растёт
        assertEquals(2, f.get(7, 8).box);
        f.record(7, 8, false, 1000, 0);
        assertEquals(0, f.get(7, 8).box);
    }

    @Test public void weakFactsComeMoreOften() {
        FactStats f = new FactStats(null);
        for (int a = 2; a <= 9; a++) for (int b = a; b <= 9; b++) for (int k = 0; k < 5; k++) f.record(a, b, true, 1000, 0);
        f.record(7, 8, false, 1000, 0);
        Random rnd = new Random(1);
        Map<String, Integer> cnt = new HashMap<>();
        for (int i = 0; i < 5000; i++) { Problem p = f.pick(rnd, 2, 9, new ArrayList<>()); cnt.merge(FactStats.key(p.a, p.b), 1, Integer::sum); }
        int weak = cnt.getOrDefault("7x8", 0), strong = cnt.getOrDefault("3x4", 0);
        assertTrue("7×8 " + weak + " против 3×4 " + strong, weak > strong * 8);
    }

    @Test public void persists() throws Exception {
        File t = File.createTempFile("facts", ".tsv"); t.delete();
        FactStats f = new FactStats(t);
        f.record(6, 9, true, 1500, 42);
        FactStats g = new FactStats(t);
        assertEquals(1, g.get(9, 6).box);
        assertEquals(42, g.get(6, 9).last);
        t.delete();
    }

    @Test public void recentNotRepeated() {
        FactStats f = new FactStats(null);
        ArrayList<String> recent = new ArrayList<>(); recent.add("2x2");
        for (int i = 0; i < 200; i++) { Problem p = f.pick(new Random(i), 2, 2, recent); assertNotEquals("2x2", FactStats.key(p.a, p.b)); }
    }
}
