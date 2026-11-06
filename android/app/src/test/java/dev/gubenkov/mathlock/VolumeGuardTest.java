package dev.gubenkov.mathlock;

import static org.junit.Assert.*;

import org.junit.Test;

public class VolumeGuardTest {
    static int cap(String hm) {
        int m = VolumeGuard.parseTime(hm);
        return VolumeGuard.capPct(m, 21 * 60, 9 * 60, 23 * 60, 7 * 60, 100, 50, 30);
    }

    @Test public void periodsByDefault() {
        assertEquals(100, cap("12"));
        assertEquals(100, cap("20:59"));
        assertEquals(50, cap("21"));
        assertEquals(50, cap("22:59"));
        assertEquals(30, cap("23"));
        assertEquals(30, cap("0:15"));
        assertEquals(30, cap("6:59"));
        assertEquals(50, cap("7"));
        assertEquals(50, cap("8:59"));
        assertEquals(100, cap("9"));
    }

    @Test public void sameHoursDisablePeriod() {
        assertEquals(50, VolumeGuard.capPct(0, 21 * 60, 9 * 60, 0, 0, 100, 50, 30));
    }

    @Test public void parse() {
        assertArrayEquals(new int[]{21 * 60, 9 * 60}, VolumeGuard.parseRange("21-9"));
        assertArrayEquals(new int[]{21 * 60 + 30, 8 * 60 + 30}, VolumeGuard.parseRange(" 21:30 – 8.30 "));
        assertNull(VolumeGuard.parseRange("25-9"));
        assertNull(VolumeGuard.parseRange("21"));
        assertEquals("21:30-9", VolumeGuard.fmtRange(21 * 60 + 30, 9 * 60));
        assertEquals(26, VolumeGuard.capIndex(50, 52));   // ползунок посередине
    }
}
