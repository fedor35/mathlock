package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;

public class SoundsTest {
    @Test public void successJingle() {
        short[] s = Sounds.success(44100);
        assertTrue("длина ~1 с", s.length > 44100 * 8 / 10 && s.length < 44100 * 12 / 10);
        int peak = 0; for (short v : s) peak = Math.max(peak, Math.abs(v));
        assertTrue("громкость 0,8 без клиппинга", peak > 25000 && peak < 32767);
        assertTrue("начинается с тишины, без щелчка", Math.abs(s[0]) < 100);
        assertTrue("заканчивается тихо", Math.abs(s[s.length - 1]) < 300);
    }

    @Test public void allJinglesClean() {
        java.util.Random r = new java.util.Random(1);
        for (short[] s : new short[][]{Sounds.coin(44100), Sounds.levelUp(44100), Sounds.sparkle(44100, r)}) {
            assertTrue(s.length > 44100 / 4 && s.length < 44100 * 2);
            int peak = 0; for (short v : s) peak = Math.max(peak, Math.abs(v));
            assertTrue(peak > 25000 && peak < 32767);
            assertTrue("без щелчка в начале", Math.abs(s[0]) < 300);
            assertTrue("без щелчка в конце", Math.abs(s[s.length - 1]) < 300);
        }
    }
}
