package dev.gubenkov.reading;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class LeaderboardTest {
    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Almaty");   // UTC+5
    static final long NOON = 1793786400000L;   // 04.11.2026 15:00 Алматы
    static AdaptiveLevel.Attempt at(long t, float wcpm, float acc) { return new AdaptiveLevel.Attempt(t, "x", 5, wcpm, acc, false); }

    @Test public void scoreIsPaceTimesAccuracy() {
        assertEquals(54f, Leaderboard.score(60, 0.9f), 1e-4);
    }

    @Test public void duelPlacesByScoreWithTies() {
        Leaderboard.Duel d = new Leaderboard.Duel();
        d.add(new Leaderboard.Round("p1", "Аня", 20, 50, 1f));      // 50
        d.add(new Leaderboard.Round("p2", "Ваня", 15, 70, 0.8f));   // 56
        d.add(new Leaderboard.Round("p3", "Папа", 10, 100, 0.5f));  // 50 — ничья с Аней
        List<Leaderboard.Row> t = d.table();
        assertEquals("p2", t.get(0).best.key); assertEquals(1, t.get(0).place);
        assertEquals(2, t.get(1).place); assertEquals(2, t.get(2).place);
        assertEquals("p1", t.get(1).best.key);   // при равном счёте — кто читал раньше
        assertEquals("Ваня", d.winner().name);
    }

    @Test public void secondRoundKeepsBestAndCounts() {
        Leaderboard.Duel d = new Leaderboard.Duel();
        d.add(new Leaderboard.Round("p1", "Аня", 20, 50, 1f));
        d.add(new Leaderboard.Round("p1", "Аня", 18, 40, 1f));
        assertEquals(1, d.table().size()); assertEquals(2, d.table().get(0).rounds);
        assertEquals(50f, d.table().get(0).best.wcpm, 1e-4);
    }

    @Test public void reassignMergesRows() {
        Leaderboard.Duel d = new Leaderboard.Duel();
        d.add(new Leaderboard.Round("g1", "Игрок 1", 20, 50, 1f));
        d.add(new Leaderboard.Round("p2", "Ваня", 15, 40, 1f));
        d.reassign("g1", "p2", "Ваня");
        assertEquals(1, d.table().size()); assertEquals(50f, d.winner().wcpm, 1e-4); assertEquals("Ваня", d.winner().name);
    }

    @Test public void emptyDuelHasNoWinner() { assertNull(new Leaderboard.Duel().winner()); }

    @Test public void dayBoundaryInTimeZone() {
        long midnightAlmaty = 1793732400000L;   // 04.11.2026 00:00 UTC+5
        long four = midnightAlmaty + Leaderboard.DAY_START_MS;   // читательский день начинается в 4:00
        assertEquals(Leaderboard.day(four, TZ), Leaderboard.day(four - 1, TZ) + 1);
        assertEquals(Leaderboard.day(four, TZ), Leaderboard.day(NOON, TZ));
        // 00:30 — ещё вчерашний вечер
        assertEquals(Leaderboard.day(NOON, TZ) - 1, Leaderboard.day(midnightAlmaty + 1800000L, TZ));
    }

    @Test public void statsTodayStarsStreakBest() {
        List<AdaptiveLevel.Attempt> h = new ArrayList<>();
        long day = Leaderboard.DAY_MS;
        h.add(at(NOON - 3 * day, 30, 1f));          // 3 дня назад
        h.add(at(NOON - 2 * day, 35, 0.9f));        // позавчера: быстрее, но точность ниже 95% — не рекорд
        h.add(at(NOON - 1 * day, 33, 0.97f));       // вчера
        h.add(at(NOON - 3600000, 31, 1f));          // сегодня
        h.add(at(NOON - 60000, 32, 0.8f));          // сегодня, не точно — всё равно текст и звезда
        Leaderboard.Stats s = Leaderboard.stats(h, NOON, TZ);
        assertEquals(5, s.total); assertEquals(2, s.today); assertEquals(2, s.stars());
        assertEquals(4, s.streak); assertEquals(33f, s.best, 1e-4);
    }

    @Test public void streakSurvivesUntilEndOfToday() {
        List<AdaptiveLevel.Attempt> h = new ArrayList<>();
        h.add(at(NOON - 2 * Leaderboard.DAY_MS, 30, 1f)); h.add(at(NOON - Leaderboard.DAY_MS, 30, 1f));
        assertEquals(2, Leaderboard.stats(h, NOON, TZ).streak);                       // сегодня ещё не читал — серия держится
        assertEquals(0, Leaderboard.stats(h, NOON + Leaderboard.DAY_MS, TZ).streak);  // пропустил день — сгорела
    }
}
