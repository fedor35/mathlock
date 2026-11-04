package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.*;

public class AchievementsTest {
    static final TimeZone TZ = TimeZone.getTimeZone("UTC");
    static final long DAY = 86400000L, T0 = 20000 * DAY + 10 * 3600000L;

    static AdaptiveLevel.Attempt at(long t, String id, int lvl, float wcpm, float acc) { return new AdaptiveLevel.Attempt(t, id, lvl, wcpm, acc, false); }

    static Map<String, Achievements.Badge> earned(AdaptiveLevel lv) {
        Map<String, Achievements.Badge> m = new HashMap<>();
        for (Achievements.Badge b : Achievements.all(lv, TZ)) if (b.earned()) m.put(b.id, b);
        return m;
    }

    @Test public void firstTextAndSpeed() {
        AdaptiveLevel lv = new AdaptiveLevel(5);
        lv.history.add(at(T0, "a", 5, 42, 1f));
        Map<String, Achievements.Badge> e = earned(lv);
        assertTrue(e.containsKey("texts_1"));
        assertTrue(e.containsKey("speed_40"));
        assertFalse(e.containsKey("speed_50"));
        assertTrue(e.containsKey("perfect_1"));
        assertEquals(e.keySet(), new HashSet<>(ids(Achievements.newest(lv, TZ))));
    }

    @Test public void sloppyReadingGivesNoSpeedBadge() {
        AdaptiveLevel lv = new AdaptiveLevel(5);
        lv.history.add(at(T0, "a", 5, 90, 0.7f));
        assertFalse(earned(lv).containsKey("speed_30"));
    }

    @Test public void fasterThanYesterdayAndThanLastTime() {
        AdaptiveLevel lv = new AdaptiveLevel(5);
        lv.history.add(at(T0, "a", 5, 40, 1f));
        lv.history.add(at(T0 + DAY, "b", 5, 38, 1f));
        assertFalse(earned(lv).containsKey("yesterday_1"));
        lv.history.add(at(T0 + DAY + 60000, "c", 5, 45, 1f));
        assertTrue(earned(lv).containsKey("yesterday_1"));
        assertTrue(earned(lv).containsKey("streak_2"));
        lv.history.add(at(T0 + DAY + 120000, "a", 5, 47, 1f));   // тот же текст «a»: 40 → 47 (+17%)
        assertTrue(earned(lv).containsKey("improve_1"));
        assertEquals(Collections.singletonList("improve_1"), ids(Achievements.newest(lv, TZ)));
    }

    @Test public void levelUpBadgeAndToNextLevel() {
        AdaptiveLevel lv = new AdaptiveLevel(5);   // цель L5 — 40 сл/мин
        assertEquals(3, lv.toNextLevel());
        lv.history.add(at(T0, "a", 5, 45, 1f));
        assertEquals(2, lv.toNextLevel());
        lv.history.add(at(T0 + 1, "a", 5, 50, 1f));   // повтор того же текста — окно не растёт
        assertEquals(2, lv.toNextLevel());
        lv.history.add(at(T0 + 2, "b", 5, 45, 1f));
        lv.history.add(at(T0 + 3, "c", 5, 45, 1f));
        assertEquals(6, lv.level());
        assertTrue(earned(lv).containsKey("level_6"));
        assertEquals(T0 + 3, earned(lv).get("level_6").earnedAt);
    }

    @Test public void plural() {
        assertEquals("текст", Achievements.texts(1)); assertEquals("текста", Achievements.texts(3));
        assertEquals("текстов", Achievements.texts(11)); assertEquals("текстов", Achievements.texts(25));
        assertEquals("дня", Achievements.days(2)); assertEquals("дней", Achievements.days(14));
    }

    @Test public void progressToNextTier() {
        AdaptiveLevel lv = new AdaptiveLevel(5);
        for (int i = 0; i < 7; i++) lv.history.add(at(T0 + i, "t" + i, 5, 47, 1f));
        Map<String, Achievements.Badge> all = new HashMap<>();
        for (Achievements.Badge b : Achievements.all(lv, TZ)) all.put(b.id, b);
        assertEquals(7, all.get("texts_10").have, 0); assertEquals(10, all.get("texts_10").need, 0);
        assertEquals(47, all.get("speed_50").have, 0.01);
        assertEquals(0, all.get("texts_5").need, 0);   // заработанная — без счётчика
    }

    static List<String> ids(List<Achievements.Badge> bs) { List<String> r = new ArrayList<>(); for (Achievements.Badge b : bs) r.add(b.id); return r; }
}
