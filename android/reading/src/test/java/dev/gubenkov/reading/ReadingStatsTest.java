package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;

/** Ночь 04→05.11: обрывки («дима и ег», пустая гипотеза, разговор после чтения) шли в журнал как попытки с точностью 3–20%. */
public class ReadingStatsTest {
    static final ReadingText T = new ReadingText("Дима и Егор пошли в лес. Там они нашли большой гриб и принесли его маме.");

    static ReadingStats stats(String hyp) {
        ReadingMatcher.Result r = new ReadingMatcher().match(T, hyp, true), live = new ReadingMatcher().match(T, hyp, false);
        int n = hyp.replace(" ", "").length(); int[] lf = new int[n];
        for (int i = 0; i < n; i++) lf[i] = i * 3;
        return ReadingStats.of(r, live, lf, 0.04f);
    }

    @Test public void fragmentDoesNotCount() {
        assertFalse(stats("дима и ег").counts());
        assertFalse(stats("").counts());
        assertFalse(stats("ого у меня сорок девять слов в минуту папа").counts());
    }

    @Test public void fullReadingCounts() {
        ReadingStats s = stats("дима и егор пошли в лес там они нашли большой гриб и принесли его маме");
        assertTrue(s.counts());
        assertEquals(1f, s.accuracy(), 1e-6);
    }

    @Test public void readingWithErrorsToTheEndCounts() {
        ReadingStats s = stats("дима и игоря пошли в лес там они нашли большой гоип и принесли его маме");
        assertTrue(s.counts());
        assertTrue(s.accuracy() < 1f);
    }

    @Test public void stoppedHalfwayDoesNotCount() {
        assertFalse(stats("дима и егор пошли в лес там они").counts());
    }
}
