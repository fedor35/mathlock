package dev.gubenkov.reading;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class AdaptiveLevelTest {
    static AdaptiveLevel.Attempt at(int t, int lvl, float wcpm, float acc) { return new AdaptiveLevel.Attempt(t, "t" + t, lvl, wcpm, acc, false); }

    @Test public void promotesAfterThreeGoodReadings() {
        AdaptiveLevel a = new AdaptiveLevel(5);
        a.history.add(at(1, 5, 45, 1f)); a.history.add(at(2, 5, 44, 0.97f));
        assertEquals(5, a.level());
        a.history.add(at(3, 5, 42, 1f));
        assertEquals(6, a.level());
    }

    @Test public void slowReadingDoesNotPromote() {
        AdaptiveLevel a = new AdaptiveLevel(6);
        for (int i = 0; i < 10; i++) a.history.add(at(i, 6, 35, 1f));   // точно, но медленно: цель L6 = 50
        assertEquals(6, a.level());
    }

    @Test public void demotesAfterTwoHardReadings() {
        AdaptiveLevel a = new AdaptiveLevel(7);
        a.history.add(at(1, 7, 40, 0.8f)); assertEquals(7, a.level());
        a.history.add(at(2, 7, 40, 0.7f)); assertEquals(6, a.level());
    }

    @Test public void growingChildClimbsToTop() {
        // ребёнок 40 слов/мин, +1,5 слова/мин за попытку — за ~50 попыток доходит до L9
        AdaptiveLevel a = new AdaptiveLevel(5); float w = 40;
        for (int i = 0; i < 80; i++) { a.history.add(at(i, a.level(), w, 0.98f)); w += 1.5f; }
        assertEquals(9, a.level());
    }

    @Test public void tsvRoundTrip() {
        AdaptiveLevel.Attempt x = new AdaptiveLevel.Attempt(123, "ru-L6-001", 6, 51.5f, 0.96f, true);
        AdaptiveLevel.Attempt y = AdaptiveLevel.Attempt.parse(x.toTsv());
        assertEquals("ru-L6-001", y.textId); assertEquals(6, y.level); assertEquals(51.5f, y.wcpm, 0.01); assertTrue(y.challenge);
    }

    @Test public void pickPrefersUnreadAtLevelAndFallsBack() {
        AdaptiveLevel a = new AdaptiveLevel(5);
        List<String> ids = Arrays.asList("a", "b", "c", "d"); List<Integer> lv = Arrays.asList(4, 5, 5, 6);
        a.history.add(new AdaptiveLevel.Attempt(1, "b", 5, 40, 1, false));
        assertEquals(2, a.pick(ids, lv, 5, new Random(1)));
        assertEquals(3, a.pick(ids, lv, 6, new Random(1)));
        assertEquals(3, a.pick(ids, lv, 9, new Random(1)));   // L9 ещё нет — берём ближайший снизу
    }

    @Test public void challengeEveryFifth() {
        AdaptiveLevel a = new AdaptiveLevel(5);
        for (int i = 0; i < 4; i++) { assertFalse(a.nextIsChallenge()); a.history.add(at(i, 5, 20, 1f)); }
        assertTrue(a.nextIsChallenge());
        a.history.add(new AdaptiveLevel.Attempt(9, "x", 6, 10, 0.5f, true));
        assertFalse(a.nextIsChallenge());
        assertEquals("неудачный вызов не понижает", 5, a.level());
    }
}
