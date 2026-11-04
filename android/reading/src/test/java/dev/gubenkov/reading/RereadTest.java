package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;

/** Перечитывание: возврат назад внутри одного чтения и повторное чтение красных слов новой записью. */
public class RereadTest {
    static final String TEXT = "Ма́ма мы́ла ра́му. Па́па чита́л газе́ту в саду́.";
    static final ReadingMatcher.State OK = ReadingMatcher.State.OK, WRONG = ReadingMatcher.State.WRONG, PENDING = ReadingMatcher.State.PENDING;

    static ReadingMatcher.Result m(String hyp, boolean fin, ReadingMatcher.Result prev) {
        return new ReadingMatcher().match(new ReadingText(TEXT), hyp, fin, prev);
    }

    @Test public void goingBackSeveralWordsDoesNotBreakCorrectWords() {
        ReadingMatcher.Result r = m("мама мыла раму папа читал мыла раму папа читал газету в саду", true, null);
        for (ReadingMatcher.State s : r.states) assertEquals(OK, s);
        assertTrue(r.complete);
    }

    @Test public void goingBackWhileStreamingKeepsOrder() {
        ReadingMatcher.Result r = m("мама мыла раму папа читал мама мыла раму папа читал газ", false, null);
        for (int w = 0; w < 5; w++) assertEquals("слово " + w, OK, r.states[w]);
        assertTrue("прогресс дошёл до газеты", r.progressWords >= 5);
    }

    @Test public void selfCorrectionCounts() {
        ReadingMatcher.Result r = m("мама мыла рыбу раму папа читал газету в саду", true, null);
        assertTrue(r.complete);
    }

    @Test public void secondRecordingRereadsRedWordOnly() {
        ReadingMatcher.Result first = m("мама мыла рыбу папа читал газету в саду", true, null);
        assertEquals(WRONG, first.states[2]);
        assertFalse(first.complete);
        ReadingMatcher.Result live = m("ра", false, first);   // ещё читает — зелёные не гаснут
        for (int w = 0; w < 7; w++) if (w != 2) assertEquals("слово " + w, OK, live.states[w]);
        ReadingMatcher.Result fix = m("раму", true, first);
        assertTrue(fix.complete);
        for (ReadingMatcher.State s : fix.states) assertEquals(OK, s);
    }

    @Test public void secondRecordingWithContextAround() {
        ReadingMatcher.Result first = m("мама мыла рыбу папа читал газету в саду", true, null);
        ReadingMatcher.Result fix = m("мыла раму папа", true, first);
        assertTrue(fix.complete);
    }

    @Test public void secondRecordingContinuesUnreadTail() {
        ReadingMatcher.Result first = m("мама мыла раму папа", true, null);   // остановился посередине
        assertFalse(first.complete);
        ReadingMatcher.Result live = m("читал газету", false, first);
        for (int w = 0; w < 4; w++) assertEquals(OK, live.states[w]);
        assertEquals(OK, live.states[4]);
        ReadingMatcher.Result fin = m("читал газету в саду", true, first);
        assertTrue(fin.complete);
    }

    @Test public void wrongRereadStaysRed() {
        ReadingMatcher.Result first = m("мама мыла рыбу папа читал газету в саду", true, null);
        ReadingMatcher.Result again = m("рыбу", true, first);
        assertEquals(WRONG, again.states[2]);
        assertEquals(OK, again.states[0]);
        assertFalse(again.complete);
    }
}
