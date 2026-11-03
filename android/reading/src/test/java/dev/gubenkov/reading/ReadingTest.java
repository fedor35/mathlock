package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;

public class ReadingTest {
    @Test public void syllables() {
        assertEquals(Arrays.asList("Ма", "ма"), ReadingText.syllabify("Мама"));
        assertEquals(Arrays.asList("ко", "шка"), ReadingText.syllabify("кошка"));
        assertEquals(Arrays.asList("кар", "та"), ReadingText.syllabify("карта"));
        assertEquals(Arrays.asList("май", "ка"), ReadingText.syllabify("майка"));
        assertEquals(Arrays.asList("день"), ReadingText.syllabify("день"));
        assertEquals(Arrays.asList("паль", "то"), ReadingText.syllabify("пальто"));
        assertEquals(Arrays.asList("кот"), ReadingText.syllabify("кот"));
        assertEquals(Arrays.asList("сол", "нце"), ReadingText.syllabify("солнце"));
        assertEquals(Arrays.asList("объ", "я", "снил"), ReadingText.syllabify("объяснил"));
        assertEquals(Arrays.asList("конь", "ки"), ReadingText.syllabify("коньки"));
        assertEquals(Arrays.asList("ма", "як"), ReadingText.syllabify("маяк"));
        assertEquals(Arrays.asList("ру", "чей"), ReadingText.syllabify("ручей"));
        assertEquals(Arrays.asList("Жи", "ла", "бы", "ла"), ReadingText.syllabify("Жила-была"));
    }

    @Test public void stress() {
        ReadingText t = new ReadingText("Ма́ма мы́ла ра́му. Ёжик спит.");
        assertEquals(5, t.words.size());
        assertEquals("мама", t.words.get(0).letters);
        assertEquals(0, t.words.get(0).stressVowel);
        assertEquals("ежик", t.words.get(3).letters);
        assertEquals(0, t.words.get(3).stressVowel);
        assertEquals(0, t.words.get(4).stressVowel);
    }

    static ReadingMatcher.State[] st(String text, String hyp, boolean fin) {
        return new ReadingMatcher().match(new ReadingText(text), hyp, fin).states;
    }

    @Test public void syllableReadingAccepted() {
        ReadingMatcher.Result r = new ReadingMatcher().match(new ReadingText("Ма́ма мы́ла ра́му."), "ма ма мы ла ра му", true);
        assertTrue(r.complete);
    }

    @Test public void repeatsAccepted() {
        assertTrue(new ReadingMatcher().match(new ReadingText("Ма́ма мы́ла ра́му."), "ма мама мы мыла ра раму", true).complete);
    }

    @Test public void wrongWordRejected() {
        ReadingMatcher.State[] s = st("Ма́ма мы́ла ра́му.", "мама мыла лужу", true);
        assertEquals(ReadingMatcher.State.OK, s[0]);
        assertEquals(ReadingMatcher.State.OK, s[1]);
        assertEquals(ReadingMatcher.State.WRONG, s[2]);
    }

    @Test public void streamingProgress() {
        ReadingMatcher.Result r = new ReadingMatcher().match(new ReadingText("Ма́ма мы́ла ра́му."), "ма ма мы", false);
        assertEquals(ReadingMatcher.State.OK, r.states[0]);
        assertNotEquals(ReadingMatcher.State.WRONG, r.states[1]);
        assertEquals(ReadingMatcher.State.PENDING, r.states[2]);
        assertFalse(r.complete);
    }

    @Test public void skippedWordRejected() {
        ReadingMatcher.State[] s = st("Ко́т спи́т на ди́ване.", "кот на диване", true);
        assertEquals(ReadingMatcher.State.WRONG, s[1]);
    }

    @Test public void shortPrepositionForgiven() {
        assertTrue(new ReadingMatcher().match(new ReadingText("Ко́т спи́т на ди́ване."), "кот спит надиване", true).complete);
        assertTrue(new ReadingMatcher().match(new ReadingText("Мы́ шли́ в ле́с."), "мы шли лес", true).complete);
    }

    @Test public void partialLastWordNotComplete() {
        ReadingMatcher.Result r = new ReadingMatcher().match(new ReadingText("Мы́ игра́ли во дворе́."), "мы играли во д", false);
        assertFalse(r.complete);
        assertEquals(ReadingMatcher.State.PENDING, r.states[3]);
        assertTrue(new ReadingMatcher().match(new ReadingText("Мы́ игра́ли во дворе́."), "мы играли во дворе", false).complete);
    }
}
