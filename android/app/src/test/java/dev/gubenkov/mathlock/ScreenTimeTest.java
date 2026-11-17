package dev.gubenkov.mathlock;

import static org.junit.Assert.*;

import java.util.TimeZone;

import org.junit.Test;

public class ScreenTimeTest {
    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Almaty");
    static final ScreenTime.Rules R = new ScreenTime.Rules(90, 60, 6);

    static long at(int y, int mo, int d, int h, int mi) {
        java.util.Calendar c = java.util.Calendar.getInstance(TZ);
        c.clear(); c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    @Test public void dayStartsAtResetHour() {
        assertEquals(20261116, ScreenTime.dayKey(at(2026, 11, 17, 5, 59), 6, TZ));
        assertEquals(20261117, ScreenTime.dayKey(at(2026, 11, 17, 6, 0), 6, TZ));
        assertEquals(20261117, ScreenTime.dayKey(at(2026, 11, 17, 23, 59), 6, TZ));
        assertEquals(20261117, ScreenTime.dayKey(at(2026, 11, 18, 0, 30), 6, TZ));
    }

    @Test public void baseEarnCapAndExtra() {
        ScreenTime.State s = new ScreenTime.State();
        ScreenTime.roll(s, 20261117);
        assertEquals(90 * 60, ScreenTime.leftSec(s, R));
        assertEquals(5, ScreenTime.earn(s, R, 5));
        assertEquals(55, ScreenTime.canEarnMin(s, R));
        assertEquals(55, ScreenTime.earn(s, R, 100));   // потолок
        assertEquals(0, ScreenTime.earn(s, R, 5));
        s.extraMin += 30;                                // новый уровень — вне потолка
        assertEquals((90 + 60 + 30) * 60, ScreenTime.leftSec(s, R));
    }

    @Test public void useAndTimeUp() {
        ScreenTime.State s = new ScreenTime.State();
        ScreenTime.roll(s, 1);
        ScreenTime.use(s, 3600, 120);                    // скачок — обрезан
        assertEquals(120, s.usedSec);
        for (int i = 0; i < 200; i++) ScreenTime.use(s, 30, 120);
        assertEquals(0, ScreenTime.leftSec(s, R));
        ScreenTime.clampUsed(s, R);                      // перерасход не съедает заработок
        ScreenTime.earn(s, R, 15);
        assertEquals(15 * 60, ScreenTime.leftSec(s, R));
    }

    @Test public void newDayResets() {
        ScreenTime.State s = new ScreenTime.State();
        ScreenTime.roll(s, 20261116);
        s.usedSec = 9999; s.earnedMin = 40; s.extraMin = 30; s.warned = 3;
        assertFalse(ScreenTime.roll(s, 20261116));
        assertTrue(ScreenTime.roll(s, 20261117));
        assertEquals(0, s.usedSec); assertEquals(0, s.earnedMin); assertEquals(0, s.extraMin); assertEquals(0, s.warned);
    }

    @Test public void warningsOnceAndRearm() {
        ScreenTime.State s = new ScreenTime.State();
        ScreenTime.roll(s, 1);
        s.usedSec = 90 * 60 - 299;
        assertEquals(ScreenTime.WARN_5, ScreenTime.warnNow(s, R));
        assertEquals(0, ScreenTime.warnNow(s, R));
        s.usedSec = 90 * 60 - 59;
        assertEquals(ScreenTime.WARN_1, ScreenTime.warnNow(s, R));
        assertEquals(0, ScreenTime.warnNow(s, R));
        ScreenTime.earn(s, R, 10);
        ScreenTime.rearmWarnings(s, R);
        assertEquals(0, s.warned);
    }

    @Test public void format() {
        assertEquals("40 с", ScreenTime.fmt(40));
        assertEquals("1 мин", ScreenTime.fmt(60));
        assertEquals("2 мин", ScreenTime.fmt(61));
        assertEquals("1 ч", ScreenTime.fmt(3600));
        assertEquals("1 ч 30 мин", ScreenTime.fmt(5400));
    }

    @Test public void parentMinusNeverBelowZero() {
        ScreenTime.State s = new ScreenTime.State();
        ScreenTime.roll(s, 1);
        ScreenTime.earn(s, R, 10);
        ScreenTime.addExtra(s, R, -500);
        assertEquals(0, ScreenTime.allowedSec(s, R));
        s.usedSec = 1200;
        ScreenTime.clampUsed(s, R);
        assertEquals(0, s.usedSec);
        ScreenTime.addExtra(s, R, 30);                   // вернул — ровно 30 мин, без «долга» в плюс
        assertEquals(30 * 60, ScreenTime.leftSec(s, R));
    }

    @Test public void readRewardBySpeed() {
        assertEquals(10, ScreenTime.readReward(10, -1));
        assertEquals(10, ScreenTime.readReward(10, 24));
        assertEquals(15, ScreenTime.readReward(10, 30));
        assertEquals(20, ScreenTime.readReward(10, 50));
        assertEquals(25, ScreenTime.readReward(10, 64));
        assertEquals(40, ScreenTime.readReward(10, 95));
        assertEquals(50, ScreenTime.readReward(10, 130));
        assertEquals(8, ScreenTime.readReward(5, 30));      // 7,5 → 8
    }
}
